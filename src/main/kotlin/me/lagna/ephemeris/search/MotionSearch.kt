// SPDX-License-Identifier: AGPL-3.0-or-later
package me.lagna.ephemeris.search

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** Longitude (degrees) and its speed (degrees per day) of one body at one instant. */
data class Motion(
    val longitude: Double,
    val speed: Double,
)

/** Motion of one body in one frame as a function of the Julian day (UT). */
fun interface MotionSource {
    fun at(jd: Double): Motion

    /**
     * Longitude alone. Crossing searches only need this; a source can override it with a cheaper
     * calculation (libswe without `SEFLG_SPEED`), while station searches still use [at].
     */
    fun longitudeAt(jd: Double): Double = at(jd).longitude
}

data class StationHit(
    val jd: Double,
    val turnsRetrograde: Boolean,
)

data class CrossingHit(
    val jd: Double,
    val longitude: Double,
    val retrograde: Boolean,
)

/** A target longitude: [value] unwrapped onto the search interval, [longitude] the same angle in [0, 360). */
data class Target(
    val value: Double,
    val longitude: Double,
)

/** Longitudes a body is searched for: a regular division of the circle or an explicit list. */
sealed interface LongitudeTargets {
    /**
     * Targets with [lower] <= value <= [upper] (unwrapped, [lower] < [upper]), generated lazily in ascending order,
     * or descending when [descending], so callers can stop early and keep hits in the order the body meets them.
     */
    fun valuesIn(
        lower: Double,
        upper: Double,
        descending: Boolean = false,
    ): Sequence<Target>

    /** [divisions] equal parts of the circle, the first at [offset]: 12 for signs, 27 for nakshatras, 108 for padas. */
    class Grid(
        val divisions: Int,
        offset: Double = 0.0,
    ) : LongitudeTargets {
        val step: Double = FULL_CIRCLE / divisions

        /** The offset folded into [0, step), so target i of each turn is `start + i * step`, ascending, below 360. */
        private val start: Double

        init {
            require(divisions in 1..MAX_DIVISIONS) { "divisions must be in 1..$MAX_DIVISIONS" }
            require(offset.isFinite()) { "offset must be finite" }
            start = ((offset % step) + step) % step
        }

        override fun valuesIn(
            lower: Double,
            upper: Double,
            descending: Boolean,
        ): Sequence<Target> {
            var first = ceil((lower - start) / step).toLong()
            while (value(first) < lower) first++
            while (value(first - 1) >= lower) first--
            var last = floor((upper - start) / step).toLong()
            while (value(last) > upper) last--
            while (value(last + 1) <= upper) last++
            val indices = if (descending) (last downTo first).asSequence() else (first..last).asSequence()
            return indices.map { k -> Target(value(k), longitude(k)) }
        }

        private fun longitude(k: Long): Double = start + Math.floorMod(k, divisions.toLong()) * step

        private fun value(k: Long): Double = Math.floorDiv(k, divisions.toLong()) * FULL_CIRCLE + longitude(k)

        companion object {
            /** About one division per arcsecond; results are capped per request anyway. */
            const val MAX_DIVISIONS = 1_296_000

            /** A grid from a step in degrees, which must divide the circle into a whole number of parts. */
            fun ofStep(
                stepDegrees: Double,
                offset: Double = 0.0,
            ): Grid {
                require(stepDegrees > 0.0 && stepDegrees <= FULL_CIRCLE) { "step must be in (0, 360]" }
                val parts = FULL_CIRCLE / stepDegrees
                val divisions = Math.round(parts)
                require(divisions <= MAX_DIVISIONS && abs(parts - divisions) <= DIVISION_TOLERANCE * parts) {
                    "step must divide 360 into a whole number of parts of at least 1 arcsecond (30, 360/27, ...)"
                }
                return Grid(divisions.toInt(), offset)
            }

            private const val DIVISION_TOLERANCE = 1e-9
        }
    }

    /** Explicit longitudes, folded into [0, 360), deduplicated and sorted once, then picked by binary search. */
    class Explicit(
        longitudes: Collection<Double>,
    ) : LongitudeTargets {
        private val sorted: DoubleArray =
            longitudes
                .onEach { require(it.isFinite()) { "longitudes must be finite" } }
                .map { MotionSearch.normalize(it) }
                .distinct()
                .sorted()
                .toDoubleArray()

        override fun valuesIn(
            lower: Double,
            upper: Double,
            descending: Boolean,
        ): Sequence<Target> {
            val firstTurn = floor(lower / FULL_CIRCLE).toLong()
            val lastTurn = floor(upper / FULL_CIRCLE).toLong()
            val turns = if (descending) (lastTurn downTo firstTurn).asSequence() else (firstTurn..lastTurn).asSequence()
            return turns.flatMap { turn ->
                val base = turn * FULL_CIRCLE
                val from = lowerBound(lower - base)
                val to = upperBound(upper - base)
                val indices = if (descending) (to - 1 downTo from).asSequence() else (from until to).asSequence()
                indices.map { Target(base + sorted[it], sorted[it]) }
            }
        }

        /** First index with sorted[i] >= x. */
        private fun lowerBound(x: Double): Int {
            var lo = 0
            var hi = sorted.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (sorted[mid] < x) lo = mid + 1 else hi = mid
            }
            return lo
        }

        /** First index with sorted[i] > x. */
        private fun upperBound(x: Double): Int {
            var lo = 0
            var hi = sorted.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (sorted[mid] <= x) lo = mid + 1 else hi = mid
            }
            return lo
        }
    }
}

/**
 * Exact instants of stations and longitude crossings.
 *
 * Stations are the sign changes of the speed: the speed is sampled every [stationStep] days, which must be
 * shorter than the shortest gap between two stations of the body, and each sign change is refined by
 * bisection. Crossings need only longitudes: they are sampled every [crossingStep] days (the body must move
 * well under 180 degrees in that time) with the stations added as extra samples, so the longitude is
 * monotonic between consecutive samples and every target in between is crossed exactly once and refined by
 * bisection as well.
 *
 * A [forwardOnly] source never moves backwards (the ascendant within the polar circles, the midheaven), so it
 * has no stations and may move any amount under a full turn between samples, however unevenly.
 */
class MotionSearch(
    private val source: MotionSource,
    private val stationStep: Double,
    private val canStation: Boolean,
    private val crossingStep: Double = DEFAULT_CROSSING_STEP_DAYS,
    private val tolerance: Double = DEFAULT_TOLERANCE_DAYS,
    private val forwardOnly: Boolean = false,
) {
    init {
        require(stationStep > 0.0) { "stationStep must be positive" }
        require(crossingStep > 0.0) { "crossingStep must be positive" }
        require(!(forwardOnly && canStation)) { "a forward-only source cannot station" }
    }

    fun stations(
        from: Double,
        to: Double,
    ): List<StationHit> {
        if (!canStation || to <= from) return emptyList()
        val hits = mutableListOf<StationHit>()
        var a = from
        var speedA = source.at(a).speed
        while (a < to) {
            val c = minOf(a + stationStep, to)
            val speedC = source.at(c).speed
            if (isNegative(speedA) != isNegative(speedC)) {
                hits += StationHit(refineStation(a, c, speedA), turnsRetrograde = isNegative(speedC))
            }
            a = c
            speedA = speedC
        }
        return hits
    }

    /**
     * Crossings of [targets] in chronological order. Stops after [limit] hits and throws
     * [TooManyResultsException] when there would be more.
     */
    fun crossings(
        from: Double,
        to: Double,
        targets: LongitudeTargets,
        limit: Int = Int.MAX_VALUE,
    ): List<CrossingHit> {
        if (to <= from) return emptyList()
        val knots = sampleTimes(from, to, if (forwardOnly) emptyList() else stations(from, to).map { it.jd }).iterator()
        val hits = mutableListOf<CrossingHit>()
        var a = knots.next()
        var longitudeA = source.longitudeAt(a)
        for (c in knots) {
            val longitudeC = source.longitudeAt(c)
            val delta = if (forwardOnly) normalize(longitudeC - longitudeA) else signedDelta(longitudeC - longitudeA)
            if (delta != 0.0) {
                val lower = minOf(longitudeA, longitudeA + delta)
                val upper = maxOf(longitudeA, longitudeA + delta)
                // Targets come in the order the body meets them: descending while it moves backwards.
                for (target in targets.valuesIn(lower, upper, descending = delta < 0.0)) {
                    if (target.value == longitudeA) continue // counted in the previous interval
                    if (hits.size == limit) throw TooManyResultsException(limit)
                    val longitude = target.longitude
                    val jd =
                        if (forwardOnly) {
                            refineForward(a, c, longitudeA, target.value - longitudeA)
                        } else {
                            refineCrossing(a, c, longitudeA, longitude)
                        }
                    hits += CrossingHit(jd, longitude, retrograde = delta < 0.0)
                }
            }
            a = c
            longitudeA = longitudeC
        }
        return hits
    }

    /**
     * [from], every [crossingStep] days after it, [to] and the [stations] (chronological), in order and without
     * duplicates. Lazy, so that a search stopped at its result limit never builds the samples of the whole range.
     */
    private fun sampleTimes(
        from: Double,
        to: Double,
        stations: List<Double>,
    ): Sequence<Double> =
        sequence {
            val regular = (generateSequence(from) { it + crossingStep }.takeWhile { it < to } + to).iterator()
            val extra = stations.filter { it > from && it < to }.iterator()
            var r: Double? = regular.next()
            var e: Double? = if (extra.hasNext()) extra.next() else null
            var last = Double.NaN
            while (r != null || e != null) {
                val next: Double
                if (e == null || (r != null && r <= e)) {
                    next = checkNotNull(r)
                    r = if (regular.hasNext()) regular.next() else null
                } else {
                    next = e
                    e = if (extra.hasNext()) extra.next() else null
                }
                if (next != last) yield(next)
                last = next
            }
        }

    private fun refineStation(
        start: Double,
        end: Double,
        speedAtStart: Double,
    ): Double {
        var lo = start
        var hi = end
        while (hi - lo > tolerance) {
            val mid = (lo + hi) / 2
            if (isNegative(source.at(mid).speed) == isNegative(speedAtStart)) lo = mid else hi = mid
        }
        return (lo + hi) / 2
    }

    private fun refineCrossing(
        start: Double,
        end: Double,
        longitudeAtStart: Double,
        target: Double,
    ): Double {
        val startSide = isNegative(signedDelta(longitudeAtStart - target))
        var lo = start
        var hi = end
        while (hi - lo > tolerance) {
            val mid = (lo + hi) / 2
            val offset = signedDelta(source.longitudeAt(mid) - target)
            if (offset != 0.0 && isNegative(offset) == startSide) lo = mid else hi = mid
        }
        return (lo + hi) / 2
    }

    /**
     * Bisection for a forward-only source, which covers less than a full turn between [start] and [end]: the
     * distance travelled since [start] grows monotonically, so it is compared with the target's [distance] instead
     * of comparing angles, which would break wherever the source moves more than 180 degrees between two probes.
     */
    private fun refineForward(
        start: Double,
        end: Double,
        longitudeAtStart: Double,
        distance: Double,
    ): Double {
        var lo = start
        var hi = end
        while (hi - lo > tolerance) {
            val mid = (lo + hi) / 2
            if (normalize(source.longitudeAt(mid) - longitudeAtStart) < distance) lo = mid else hi = mid
        }
        return (lo + hi) / 2
    }

    class TooManyResultsException(
        val limit: Int,
    ) : RuntimeException("more than $limit results; narrow the range")

    companion object {
        /** About 0.09 s. */
        const val DEFAULT_TOLERANCE_DAYS = 1e-6

        /** The Moon, the fastest body, moves about 15 degrees a day. */
        const val DEFAULT_CROSSING_STEP_DAYS = 1.0

        private fun isNegative(value: Double) = value < 0.0

        /** Angle difference folded into [-180, 180). */
        fun signedDelta(degrees: Double): Double =
            ((degrees + HALF_CIRCLE) % FULL_CIRCLE + FULL_CIRCLE) % FULL_CIRCLE - HALF_CIRCLE

        fun normalize(degrees: Double): Double = (degrees % FULL_CIRCLE + FULL_CIRCLE) % FULL_CIRCLE
    }
}

private const val FULL_CIRCLE = 360.0
private const val HALF_CIRCLE = 180.0
