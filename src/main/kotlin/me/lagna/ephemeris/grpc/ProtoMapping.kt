// SPDX-License-Identifier: AGPL-3.0-or-later
package me.lagna.ephemeris.grpc

import me.lagna.ephemeris.swe.Frame
import me.lagna.ephemeris.swe.Place
import me.lagna.ephemeris.swe.SweConstants
import me.lagna.ephemeris.v1.Ayanamsa
import me.lagna.ephemeris.v1.Body
import me.lagna.ephemeris.v1.BodyPair
import me.lagna.ephemeris.v1.ChartAngle
import me.lagna.ephemeris.v1.DiscConvention
import me.lagna.ephemeris.v1.EclipseKind
import me.lagna.ephemeris.v1.EclipseType
import me.lagna.ephemeris.v1.GeoLocation
import me.lagna.ephemeris.v1.HouseSystem
import me.lagna.ephemeris.v1.PairCombination
import me.lagna.ephemeris.v1.PortionPoint
import me.lagna.ephemeris.v1.RiseSetEvent
import me.lagna.ephemeris.v1.Zodiac
import me.lagna.ephemeris.swe.EclipseType as SweEclipseType

/** Thrown for a malformed request; reported as INVALID_ARGUMENT. */
class InvalidRequestException(
    message: String,
) : RuntimeException(message)

internal fun invalid(message: String): Nothing = throw InvalidRequestException(message)

/** Maps protobuf values to libswe identifiers, rejecting unspecified and unknown values. */
internal object ProtoMapping {
    private const val MAX_LATITUDE = 90.0
    private const val MAX_LONGITUDE = 180.0

    fun body(body: Body): Int =
        when (body) {
            Body.SUN -> SweConstants.SE_SUN
            Body.MOON -> SweConstants.SE_MOON
            Body.MERCURY -> SweConstants.SE_MERCURY
            Body.VENUS -> SweConstants.SE_VENUS
            Body.MARS -> SweConstants.SE_MARS
            Body.JUPITER -> SweConstants.SE_JUPITER
            Body.SATURN -> SweConstants.SE_SATURN
            Body.URANUS -> SweConstants.SE_URANUS
            Body.NEPTUNE -> SweConstants.SE_NEPTUNE
            Body.PLUTO -> SweConstants.SE_PLUTO
            Body.MEAN_NODE -> SweConstants.SE_MEAN_NODE
            Body.TRUE_NODE -> SweConstants.SE_TRUE_NODE
            Body.MEAN_APOGEE -> SweConstants.SE_MEAN_APOG
            Body.CHIRON -> SweConstants.SE_CHIRON
            Body.MEAN_SOUTH_NODE -> SweConstants.SOUTH_MEAN_NODE
            Body.TRUE_SOUTH_NODE -> SweConstants.SOUTH_TRUE_NODE
            Body.BODY_UNSPECIFIED, Body.UNRECOGNIZED -> invalid("body must be specified")
        }

    fun siderealMode(ayanamsa: Ayanamsa): Int =
        when (ayanamsa) {
            Ayanamsa.LAHIRI -> SweConstants.SE_SIDM_LAHIRI
            Ayanamsa.RAMAN -> SweConstants.SE_SIDM_RAMAN
            Ayanamsa.KRISHNAMURTI -> SweConstants.SE_SIDM_KRISHNAMURTI
            Ayanamsa.FAGAN_BRADLEY -> SweConstants.SE_SIDM_FAGAN_BRADLEY
            Ayanamsa.YUKTESHWAR -> SweConstants.SE_SIDM_YUKTESHWAR
            Ayanamsa.TRUE_CHITRAPAKSHA -> SweConstants.SE_SIDM_TRUE_CITRA
            Ayanamsa.TRUE_REVATI -> SweConstants.SE_SIDM_TRUE_REVATI
            Ayanamsa.TRUE_PUSHYA -> SweConstants.SE_SIDM_TRUE_PUSHYA
            Ayanamsa.USHASHASHI -> SweConstants.SE_SIDM_USHASHASHI
            Ayanamsa.SURYASIDDHANTA -> SweConstants.SE_SIDM_SURYASIDDHANTA
            Ayanamsa.SURYASIDDHANTA_MSUN -> SweConstants.SE_SIDM_SURYASIDDHANTA_MSUN
            Ayanamsa.ARYABHATA -> SweConstants.SE_SIDM_ARYABHATA
            Ayanamsa.ARYABHATA_MSUN -> SweConstants.SE_SIDM_ARYABHATA_MSUN
            Ayanamsa.SS_REVATI -> SweConstants.SE_SIDM_SS_REVATI
            Ayanamsa.SS_CITRA -> SweConstants.SE_SIDM_SS_CITRA
            Ayanamsa.TRUE_MULA -> SweConstants.SE_SIDM_TRUE_MULA
            Ayanamsa.ARYABHATA_522 -> SweConstants.SE_SIDM_ARYABHATA_522
            Ayanamsa.LAHIRI_1940 -> SweConstants.SE_SIDM_LAHIRI_1940
            Ayanamsa.LAHIRI_VP285 -> SweConstants.SE_SIDM_LAHIRI_VP285
            Ayanamsa.KRISHNAMURTI_VP291 -> SweConstants.SE_SIDM_KRISHNAMURTI_VP291
            Ayanamsa.LAHIRI_ICRC -> SweConstants.SE_SIDM_LAHIRI_ICRC
            Ayanamsa.AYANAMSA_UNSPECIFIED, Ayanamsa.UNRECOGNIZED -> invalid("ayanamsa must be specified")
        }

    /** Tropical when the zodiac is absent or empty. */
    fun frame(
        zodiac: Zodiac,
        present: Boolean,
    ): Frame =
        if (!present) {
            Frame.TROPICAL
        } else {
            when (zodiac.frameCase) {
                Zodiac.FrameCase.SIDEREAL -> Frame(siderealMode(zodiac.sidereal.ayanamsa))
                Zodiac.FrameCase.TROPICAL, Zodiac.FrameCase.FRAME_NOT_SET -> Frame.TROPICAL
            }
        }

    fun houseSystem(system: HouseSystem): Char =
        when (system) {
            HouseSystem.WHOLE_SIGN -> 'W'
            HouseSystem.EQUAL -> 'E'
            HouseSystem.PLACIDUS -> 'P'
            HouseSystem.SRIPATI -> 'S'
            HouseSystem.KOCH -> 'K'
            HouseSystem.PORPHYRY -> 'O'
            HouseSystem.REGIOMONTANUS -> 'R'
            HouseSystem.CAMPANUS -> 'C'
            HouseSystem.HOUSE_SYSTEM_UNSPECIFIED, HouseSystem.UNRECOGNIZED -> invalid("house system must be specified")
        }

    fun riseSetMode(
        event: RiseSetEvent,
        convention: DiscConvention,
    ): Int {
        val kind =
            when (event) {
                RiseSetEvent.RISE -> SweConstants.SE_CALC_RISE
                RiseSetEvent.SET -> SweConstants.SE_CALC_SET
                RiseSetEvent.UPPER_TRANSIT -> return SweConstants.SE_CALC_MTRANSIT
                RiseSetEvent.LOWER_TRANSIT -> return SweConstants.SE_CALC_ITRANSIT
                RiseSetEvent.RISE_SET_EVENT_UNSPECIFIED, RiseSetEvent.UNRECOGNIZED -> invalid("event must be specified")
            }
        return when (convention) {
            DiscConvention.UPPER_LIMB_REFRACTED, DiscConvention.DISC_CONVENTION_UNSPECIFIED -> kind
            DiscConvention.CENTRE_NO_REFRACTION ->
                kind or SweConstants.SE_BIT_DISC_CENTER or SweConstants.SE_BIT_NO_REFRACTION
            DiscConvention.CENTRE_REFRACTED -> kind or SweConstants.SE_BIT_DISC_CENTER
            DiscConvention.UNRECOGNIZED -> invalid("unknown disc convention")
        }
    }

    /**
     * The libswe mode for a rise/set event of a south node, which libswe cannot compute directly: a point of the
     * ecliptic rises exactly when its opposite point sets, but only on the geometric horizon, so rise and set need
     * CENTRE_NO_REFRACTION; transits swap the same way at any convention.
     */
    fun southNodeRiseSetMode(
        event: RiseSetEvent,
        convention: DiscConvention,
    ): Int {
        val opposite =
            when (event) {
                RiseSetEvent.RISE -> RiseSetEvent.SET
                RiseSetEvent.SET -> RiseSetEvent.RISE
                RiseSetEvent.UPPER_TRANSIT -> RiseSetEvent.LOWER_TRANSIT
                RiseSetEvent.LOWER_TRANSIT -> RiseSetEvent.UPPER_TRANSIT
                RiseSetEvent.RISE_SET_EVENT_UNSPECIFIED, RiseSetEvent.UNRECOGNIZED -> invalid("event must be specified")
            }
        val riseOrSet = event == RiseSetEvent.RISE || event == RiseSetEvent.SET
        if (riseOrSet && convention != DiscConvention.CENTRE_NO_REFRACTION) {
            invalid("the south nodes rise and set only with convention CENTRE_NO_REFRACTION")
        }
        return riseSetMode(opposite, convention)
    }

    fun place(location: GeoLocation): Place {
        if (location.latitude !in -MAX_LATITUDE..MAX_LATITUDE) invalid("latitude must be within [-90, 90]")
        if (location.longitude !in -MAX_LONGITUDE..MAX_LONGITUDE) invalid("longitude must be within [-180, 180]")
        if (!location.altitudeM.isFinite()) invalid("altitude must be finite")
        return Place(location.latitude, location.longitude, location.altitudeM)
    }

    /** A place for libswe's eclipse functions, which only accept altitudes from -500 to 25,000 m. */
    fun eclipsePlace(location: GeoLocation): Place =
        place(location).also {
            if (it.altitude !in SweConstants.ECLIPSE_ALTITUDE_MIN..SweConstants.ECLIPSE_ALTITUDE_MAX) {
                invalid("altitude must be within [-500, 25000] m for eclipses")
            }
        }

    /** The Body of a classical planet (a weekday or portion lord). */
    fun protoBody(seBody: Int): Body =
        when (seBody) {
            SweConstants.SE_SUN -> Body.SUN
            SweConstants.SE_MOON -> Body.MOON
            SweConstants.SE_MARS -> Body.MARS
            SweConstants.SE_MERCURY -> Body.MERCURY
            SweConstants.SE_JUPITER -> Body.JUPITER
            SweConstants.SE_VENUS -> Body.VENUS
            SweConstants.SE_SATURN -> Body.SATURN
            else -> error("not a classical planet: $seBody")
        }

    /** Where in a portion an upagraha is taken, as a fraction of the portion. */
    fun portionPoint(point: PortionPoint): Double =
        when (point) {
            PortionPoint.PORTION_START -> 0.0
            PortionPoint.PORTION_MIDDLE -> 0.5
            PortionPoint.PORTION_END -> 1.0
            PortionPoint.PORTION_POINT_UNSPECIFIED, PortionPoint.UNRECOGNIZED -> invalid("portion point must be specified")
        }

    /** True for the ascendant, false for the midheaven. */
    fun isAscendant(angle: ChartAngle): Boolean =
        when (angle) {
            ChartAngle.ASCENDANT -> true
            ChartAngle.MIDHEAVEN -> false
            ChartAngle.CHART_ANGLE_UNSPECIFIED, ChartAngle.UNRECOGNIZED -> invalid("angle must be specified")
        }

    /** True for SOLAR, false for LUNAR. */
    fun isSolar(kind: EclipseKind): Boolean =
        when (kind) {
            EclipseKind.SOLAR -> true
            EclipseKind.LUNAR -> false
            EclipseKind.ECLIPSE_KIND_UNSPECIFIED, EclipseKind.UNRECOGNIZED -> invalid("kind must be specified")
        }

    /** Two different bodies; [sign] is how body_b's longitude and speed enter: -1 for DIFFERENCE, +1 for SUM. */
    data class PairSpec(
        val bodyA: Int,
        val bodyB: Int,
        val sign: Int,
    )

    fun pair(pair: BodyPair): PairSpec {
        val bodyA = body(pair.bodyA)
        val bodyB = body(pair.bodyB)
        if (bodyA == bodyB) invalid("body_a and body_b must differ")
        return PairSpec(bodyA, bodyB, pairSign(pair.combination))
    }

    private fun pairSign(combination: PairCombination): Int =
        when (combination) {
            PairCombination.DIFFERENCE -> -1
            PairCombination.SUM -> 1
            PairCombination.PAIR_COMBINATION_UNSPECIFIED, PairCombination.UNRECOGNIZED -> invalid("combination must be specified")
        }

    fun eclipseType(type: SweEclipseType): EclipseType =
        when (type) {
            SweEclipseType.TOTAL -> EclipseType.TOTAL
            SweEclipseType.ANNULAR -> EclipseType.ANNULAR
            SweEclipseType.HYBRID -> EclipseType.HYBRID
            SweEclipseType.PARTIAL -> EclipseType.PARTIAL
            SweEclipseType.PENUMBRAL -> EclipseType.PENUMBRAL
        }

    /** Bodies whose geocentric motion never reverses, so a station search can skip them. A south node moves as its node. */
    fun canStation(seBody: Int): Boolean =
        SweConstants.northOf(seBody) !in
            setOf(SweConstants.SE_SUN, SweConstants.SE_MOON, SweConstants.SE_MEAN_NODE, SweConstants.SE_MEAN_APOG)

    /**
     * Speed sampling step (days) for station searches, at least 4 times shorter than the shortest gap between
     * two stations of the body over 1800-2400 (Mercury 19.7 days, Venus 40.8, Mars 59.8, slower bodies 117+).
     * The true node is sampled every 0.1 day; its stations come in clusters and pairs closer than that are not
     * resolved. Neptune and Pluto occasionally station two or three times within an hour, wobbling by under
     * 0.5 arcseconds; such clusters are reported as one station.
     */
    fun stationStep(seBody: Int): Double =
        when (SweConstants.northOf(seBody)) {
            SweConstants.SE_MERCURY -> MERCURY_STATION_STEP
            SweConstants.SE_TRUE_NODE -> TRUE_NODE_STATION_STEP
            else -> DEFAULT_STATION_STEP
        }

    /**
     * Station step used to split crossing searches. For the true node a coarser step only skips loops of at most
     * 0.014 degrees; over 1900-2100 it missed none of the sign, nakshatra or pada crossings found by sampling every
     * 0.005 day, with 2.3 times fewer libswe calls.
     */
    fun crossingStationStep(seBody: Int): Double =
        if (SweConstants.northOf(seBody) == SweConstants.SE_TRUE_NODE) TRUE_NODE_CROSSING_STATION_STEP else stationStep(seBody)

    /**
     * Whether the combined longitude of a pair can reverse. With the Moon it cannot: over 1800-2400 the Moon moves at
     * least 11.7 degrees a day and no other body faster than 2.3, so the difference and the sum keep moving forward.
     */
    fun pairCanStation(
        seBodyA: Int,
        seBodyB: Int,
    ): Boolean = seBodyA != SweConstants.SE_MOON && seBodyB != SweConstants.SE_MOON

    /**
     * Speed sampling step (days) for the stations of a pair's difference or sum, which split its crossing searches.
     * Unlike a body's own stations, a pair's come arbitrarily close where the two speeds barely touch (over
     * 1900-2100: Sun + Mercury 0.54 day apart, Mercury + Venus 0.8, Jupiter + Saturn 0.04); a closer pair is not
     * resolved, which skips a loop of the combined longitude whose size grows with the cube of the step and with
     * how fast the speeds change. So pairs with Mercury, Venus, Mars or the true node are sampled every 0.5 day,
     * pairs of slower-changing bodies every 2 days.
     */
    fun pairStationStep(
        seBodyA: Int,
        seBodyB: Int,
    ): Double =
        if (SweConstants.northOf(seBodyA) in FAST_CHANGING || SweConstants.northOf(seBodyB) in FAST_CHANGING) {
            FAST_PAIR_STATION_STEP
        } else {
            SLOW_PAIR_STATION_STEP
        }

    /**
     * Longitude sampling step (days) for pair crossing searches: the combination must move well under 180 degrees
     * per step. With the Moon it moves up to 16.5 degrees a day; without it, at most 3.5.
     */
    fun pairCrossingStep(
        seBodyA: Int,
        seBodyB: Int,
    ): Double = if (pairCanStation(seBodyA, seBodyB)) PLANET_PAIR_CROSSING_STEP else MOON_PAIR_CROSSING_STEP

    private val FAST_CHANGING =
        setOf(SweConstants.SE_MERCURY, SweConstants.SE_VENUS, SweConstants.SE_MARS, SweConstants.SE_TRUE_NODE)
    private const val FAST_PAIR_STATION_STEP = 0.5
    private const val SLOW_PAIR_STATION_STEP = 2.0
    private const val MOON_PAIR_CROSSING_STEP = 1.0
    private const val PLANET_PAIR_CROSSING_STEP = 10.0
    private const val MERCURY_STATION_STEP = 4.0
    private const val DEFAULT_STATION_STEP = 10.0
    private const val TRUE_NODE_STATION_STEP = 0.1
    private const val TRUE_NODE_CROSSING_STATION_STEP = 0.5
}
