// SPDX-License-Identifier: AGPL-3.0-or-later
package me.lagna.ephemeris.grpc

import com.google.protobuf.Duration
import com.google.protobuf.Timestamp
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.lagna.ephemeris.ServiceConfig
import me.lagna.ephemeris.cache.CacheKey
import me.lagna.ephemeris.cache.ResultCache
import me.lagna.ephemeris.search.CrossingHit
import me.lagna.ephemeris.search.LongitudeTargets
import me.lagna.ephemeris.search.Motion
import me.lagna.ephemeris.search.MotionSearch
import me.lagna.ephemeris.search.MotionSource
import me.lagna.ephemeris.swe.EclipseEvent
import me.lagna.ephemeris.swe.EphemerisException
import me.lagna.ephemeris.swe.Frame
import me.lagna.ephemeris.swe.JulianDay
import me.lagna.ephemeris.swe.Place
import me.lagna.ephemeris.swe.SweConstants
import me.lagna.ephemeris.swe.SweSession
import me.lagna.ephemeris.swe.SwissEphemeris
import me.lagna.ephemeris.v1.AngleCrossingsRequest
import me.lagna.ephemeris.v1.AyanamsaRequest
import me.lagna.ephemeris.v1.AyanamsaResponse
import me.lagna.ephemeris.v1.BodyPosition
import me.lagna.ephemeris.v1.Crossing
import me.lagna.ephemeris.v1.CrossingsRequest
import me.lagna.ephemeris.v1.CrossingsResponse
import me.lagna.ephemeris.v1.Eclipse
import me.lagna.ephemeris.v1.EclipseKind
import me.lagna.ephemeris.v1.EclipsesRequest
import me.lagna.ephemeris.v1.EclipsesResponse
import me.lagna.ephemeris.v1.EphemerisGrpcKt
import me.lagna.ephemeris.v1.HousesRequest
import me.lagna.ephemeris.v1.HousesResponse
import me.lagna.ephemeris.v1.HousesResult
import me.lagna.ephemeris.v1.InfoRequest
import me.lagna.ephemeris.v1.InfoResponse
import me.lagna.ephemeris.v1.InstantAtPlace
import me.lagna.ephemeris.v1.InstantPositions
import me.lagna.ephemeris.v1.LongitudeGrid
import me.lagna.ephemeris.v1.LongitudeList
import me.lagna.ephemeris.v1.MeanFrame
import me.lagna.ephemeris.v1.MeanModel
import me.lagna.ephemeris.v1.MeanPositionsRequest
import me.lagna.ephemeris.v1.MeanPositionsResponse
import me.lagna.ephemeris.v1.PairCrossing
import me.lagna.ephemeris.v1.PairCrossingsRequest
import me.lagna.ephemeris.v1.PairCrossingsResponse
import me.lagna.ephemeris.v1.PositionRangeRequest
import me.lagna.ephemeris.v1.PositionsRequest
import me.lagna.ephemeris.v1.PositionsResponse
import me.lagna.ephemeris.v1.ReturnCrossing
import me.lagna.ephemeris.v1.ReturnsRequest
import me.lagna.ephemeris.v1.ReturnsResponse
import me.lagna.ephemeris.v1.RiseSetEvent
import me.lagna.ephemeris.v1.RiseSetRequest
import me.lagna.ephemeris.v1.RiseSetResponse
import me.lagna.ephemeris.v1.RiseSetResult
import me.lagna.ephemeris.v1.RiseSetTime
import me.lagna.ephemeris.v1.SolarTime
import me.lagna.ephemeris.v1.SolarTimeRequest
import me.lagna.ephemeris.v1.SolarTimeResponse
import me.lagna.ephemeris.v1.Station
import me.lagna.ephemeris.v1.StationsRequest
import me.lagna.ephemeris.v1.StationsResponse
import me.lagna.ephemeris.v1.UpagrahasRequest
import me.lagna.ephemeris.v1.UpagrahasResponse
import java.time.DateTimeException
import java.time.Instant
import java.util.concurrent.RejectedExecutionException
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.math.abs
import me.lagna.ephemeris.v1.Body as ProtoBody

/** The `Ephemeris` gRPC service: validates requests, runs each one in a single libswe session, maps errors. */
class EphemerisService(
    private val ephemeris: SwissEphemeris,
    private val cache: ResultCache,
    private val limits: ServiceConfig.Limits = ServiceConfig.Limits(),
    private val sourceUrl: String = ServiceConfig.DEFAULT_SOURCE_URL,
    private val searchWithoutSpeed: Boolean = true,
) : EphemerisGrpcKt.EphemerisCoroutineImplBase() {
    private val supportedFrom = JulianDay.ofEpochSecond(limits.supportedFrom.epochSecond)
    private val supportedTo = JulianDay.ofEpochSecond(limits.supportedTo.epochSecond)

    override suspend fun getPositions(request: PositionsRequest): PositionsResponse =
        guarded {
            requireNotEmpty(request.instantsCount, "instants")
            requireNotEmpty(request.bodiesCount, "bodies")
            requireWithinLimit(request.instantsCount.toLong() * request.bodiesCount, "instants x bodies")
            val instants = request.instantsList.map { it to julianDay(it) }
            val bodies = request.bodiesList.map { it to ProtoMapping.body(it) }
            val frame = ProtoMapping.frame(request.zodiac, request.hasZodiac())
            val observer = if (request.hasObserver()) ProtoMapping.place(request.observer) else null
            ephemeris.session {
                val response = PositionsResponse.newBuilder()
                for ((timestamp, jd) in instants) {
                    response.addResults(positionsAt(timestamp, jd, bodies, frame, observer, request.equatorial))
                }
                response.build()
            }
        }

    override fun streamPositions(request: PositionRangeRequest): Flow<PositionsResponse> =
        flow {
            val plan =
                guarded {
                    requireNotEmpty(request.bodiesCount, "bodies")
                    val (from, to) = range(request.hasFrom(), request.from, request.hasTo(), request.to)
                    val step = request.stepDays
                    if (!(step > 0.0) || !step.isFinite()) invalid("step_days must be positive")
                    val samples = ((to - from) / step).toLong() + 1
                    if (samples.toDouble() * request.bodiesCount > limits.maxStreamItems) {
                        invalid("samples x bodies exceeds ${limits.maxStreamItems}; increase step_days or narrow the range")
                    }
                    StreamPlan(
                        from,
                        step,
                        samples,
                        request.bodiesList.map { it to ProtoMapping.body(it) },
                        ProtoMapping.frame(request.zodiac, request.hasZodiac()),
                    )
                }
            var index = 0L
            while (index < plan.samples) {
                val end = minOf(index + STREAM_CHUNK, plan.samples)
                val first = index
                val chunk =
                    guarded {
                        ephemeris.session {
                            val response = PositionsResponse.newBuilder()
                            for (i in first until end) {
                                val jd = plan.from + i * plan.step
                                response.addResults(positionsAt(JulianDay.toTimestamp(jd), jd, plan.bodies, plan.frame, null))
                            }
                            response.build()
                        }
                    }
                emit(chunk)
                index = end
            }
        }

    override suspend fun getAyanamsa(request: AyanamsaRequest): AyanamsaResponse =
        guarded {
            requireNotEmpty(request.instantsCount, "instants")
            requireWithinLimit(request.instantsCount.toLong(), "instants")
            val mode = ProtoMapping.siderealMode(request.ayanamsa)
            val jds = request.instantsList.map { julianDay(it) }
            ephemeris.session {
                AyanamsaResponse.newBuilder().addAllDegrees(jds.map { ayanamsa(it, mode) }).build()
            }
        }

    override suspend fun getHouses(request: HousesRequest): HousesResponse =
        guarded {
            requireNotEmpty(request.queriesCount, "queries")
            requireWithinLimit(request.queriesCount.toLong(), "queries")
            val system = ProtoMapping.houseSystem(request.system)
            val frame = ProtoMapping.frame(request.zodiac, request.hasZodiac())
            val queries =
                request.queriesList.map { query ->
                    if (!query.hasInstant()) invalid("every query needs an instant")
                    if (!query.hasLocation()) invalid("every query needs a location")
                    julianDay(query.instant) to ProtoMapping.place(query.location)
                }
            ephemeris.session {
                val response = HousesResponse.newBuilder()
                for ((jd, place) in queries) {
                    val houses = houses(jd, place, system, frame)
                    val result =
                        HousesResult
                            .newBuilder()
                            .setAscendant(houses.ascendant)
                            .setMidheaven(houses.midheaven)
                            .setArmc(houses.armc)
                            .setVertex(houses.vertex)
                            .addAllCusps(houses.cusps.orEmpty())
                            .setCuspsDefined(houses.cusps != null)
                    if (system == SRIPATI && houses.cusps != null) {
                        // Sripati sandhis are the midpoints of the Porphyry cusps, which are the bhava madhyas.
                        result.addAllBhavaMadhya(houses(jd, place, PORPHYRY, frame).cusps.orEmpty()).addAllBhavaSandhi(houses.cusps)
                    }
                    response.addResults(result)
                }
                response.build()
            }
        }

    override suspend fun getRiseSet(request: RiseSetRequest): RiseSetResponse =
        guarded {
            requireNotEmpty(request.queriesCount, "queries")
            requireWithinLimit(request.queriesList.sumOf { it.eventsCount.toLong() }, "query events")
            val requested = ProtoMapping.body(request.body)
            // A south node's events are its node's opposite events.
            val south = SweConstants.isSouthNode(requested)
            val body = SweConstants.northOf(requested)
            val queries =
                request.queriesList.map { query ->
                    if (!query.hasAfter()) invalid("every query needs an after instant")
                    if (!query.hasLocation()) invalid("every query needs a location")
                    requireNotEmpty(query.eventsCount, "events")
                    val modes =
                        query.eventsList.map { event ->
                            val mode =
                                if (south) {
                                    ProtoMapping.southNodeRiseSetMode(event, request.convention)
                                } else {
                                    ProtoMapping.riseSetMode(event, request.convention)
                                }
                            event to mode
                        }
                    Triple(julianDay(query.after), ProtoMapping.horizonPlace(query.location), modes)
                }
            ephemeris.session {
                val response = RiseSetResponse.newBuilder()
                for ((jd, place, modes) in queries) {
                    val result = RiseSetResult.newBuilder()
                    for ((event, mode) in modes) {
                        val time = RiseSetTime.newBuilder().setEvent(event)
                        riseTrans(jd, body, place, mode)?.let { time.setInstant(JulianDay.toTimestamp(it)) }
                        result.addTimes(time)
                    }
                    response.addResults(result)
                }
                response.build()
            }
        }

    override suspend fun findCrossings(request: CrossingsRequest): CrossingsResponse =
        guarded {
            val body = ProtoMapping.body(request.body)
            val frame = ProtoMapping.frame(request.zodiac, request.hasZodiac())
            val (from, to) = range(request.hasFrom(), request.from, request.hasTo(), request.to)
            val targets = targets(if (request.hasGrid()) request.grid else null, if (request.hasList()) request.list else null)
            cache.getOrCompute(CacheKey("FindCrossings", request), CrossingsResponse.parser()) {
                ephemeris.session {
                    crossingsResponse(search(body, frame, forCrossings = true).crossings(from, to, targets, limits.maxItems))
                }
            }
        }

    override suspend fun findStations(request: StationsRequest): StationsResponse =
        guarded {
            val body = ProtoMapping.body(request.body)
            val frame = ProtoMapping.frame(request.zodiac, request.hasZodiac())
            val (from, to) = range(request.hasFrom(), request.from, request.hasTo(), request.to)
            cache.getOrCompute(CacheKey("FindStations", request), StationsResponse.parser()) {
                ephemeris.session {
                    val response = StationsResponse.newBuilder()
                    for (hit in search(body, frame, forCrossings = false).stations(from, to)) {
                        if (response.stationsCount == limits.maxItems) throw MotionSearch.TooManyResultsException(limits.maxItems)
                        response.addStations(
                            Station
                                .newBuilder()
                                .setInstant(JulianDay.toTimestamp(hit.jd))
                                .setLongitude(position(hit.jd, body, frame).longitude)
                                .setTurnsRetrograde(hit.turnsRetrograde),
                        )
                    }
                    response.build()
                }
            }
        }

    override suspend fun findPairCrossings(request: PairCrossingsRequest): PairCrossingsResponse =
        guarded {
            if (!request.hasPair()) invalid("pair is required")
            val pair = ProtoMapping.pair(request.pair)
            val frame = ProtoMapping.frame(request.zodiac, request.hasZodiac())
            val (from, to) = range(request.hasFrom(), request.from, request.hasTo(), request.to)
            val targets = targets(if (request.hasGrid()) request.grid else null, if (request.hasList()) request.list else null)
            cache.getOrCompute(CacheKey("FindPairCrossings", request), PairCrossingsResponse.parser()) {
                ephemeris.session {
                    val hits = pairSearch(pair, frame).crossings(from, to, targets, limits.maxItems)
                    val response = PairCrossingsResponse.newBuilder()
                    for (hit in hits) {
                        response.addCrossings(
                            PairCrossing
                                .newBuilder()
                                .setInstant(JulianDay.toTimestamp(hit.jd))
                                .setValue(hit.longitude)
                                .setDecreasing(hit.retrograde),
                        )
                    }
                    response.build()
                }
            }
        }

    override suspend fun findAngleCrossings(request: AngleCrossingsRequest): CrossingsResponse =
        guarded {
            val ascendant = ProtoMapping.isAscendant(request.angle)
            if (!request.hasLocation()) invalid("location is required")
            val place = ProtoMapping.place(request.location)
            if (ascendant && abs(place.latitude) > MAX_ASCENDANT_LATITUDE) {
                invalid("the ascendant is only searched within the polar circles, |latitude| <= $MAX_ASCENDANT_LATITUDE")
            }
            val frame = ProtoMapping.frame(request.zodiac, request.hasZodiac())
            val (from, to) = range(request.hasFrom(), request.from, request.hasTo(), request.to)
            val targets = targets(if (request.hasGrid()) request.grid else null, if (request.hasList()) request.list else null)
            cache.getOrCompute(CacheKey("FindAngleCrossings", request), CrossingsResponse.parser()) {
                ephemeris.session {
                    val search =
                        MotionSearch(
                            source = MotionSource { jd -> Motion(angle(jd, place, ascendant, frame), Double.NaN) },
                            stationStep = ANGLE_SAMPLE_STEP,
                            canStation = false,
                            crossingStep = ANGLE_SAMPLE_STEP,
                            forwardOnly = true,
                        )
                    crossingsResponse(search.crossings(from, to, targets, limits.maxItems))
                }
            }
        }

    override suspend fun findEclipses(request: EclipsesRequest): EclipsesResponse =
        guarded {
            val solar = ProtoMapping.isSolar(request.kind)
            val (from, to) = range(request.hasFrom(), request.from, request.hasTo(), request.to)
            val place = if (request.hasLocation()) ProtoMapping.horizonPlace(request.location) else null
            val kind = request.kind
            cache.getOrCompute(CacheKey("FindEclipses", request), EclipsesResponse.parser()) {
                ephemeris.session {
                    val response = EclipsesResponse.newBuilder()
                    // A local maximum can be moved to a sunrise or moonrise after the true one, so look a little back.
                    var after = maxOf(from - ECLIPSE_MARGIN_DAYS, supportedFrom)
                    while (true) {
                        val event = if (solar) nextSolarEclipse(after, place) else nextLunarEclipse(after, place)
                        if (event.maximum > to) break
                        if (event.maximum >= from) {
                            if (response.eclipsesCount == limits.maxItems) throw MotionSearch.TooManyResultsException(limits.maxItems)
                            response.addEclipses(eclipse(kind, event))
                        }
                        after = event.maximum + ECLIPSE_MARGIN_DAYS
                    }
                    response.build()
                }
            }
        }

    override suspend fun findReturns(request: ReturnsRequest): ReturnsResponse =
        guarded {
            if (!request.hasNatal()) invalid("natal is required")
            val natal = julianDay(request.natal)
            val frame = ProtoMapping.frame(request.zodiac, request.hasZodiac())
            val (from, to) = range(request.hasFrom(), request.from, request.hasTo(), request.to)
            val body = if (request.subjectCase == ReturnsRequest.SubjectCase.BODY) ProtoMapping.body(request.body) else null
            val pair = if (request.subjectCase == ReturnsRequest.SubjectCase.PAIR) ProtoMapping.pair(request.pair) else null
            if (body == null && pair == null) invalid("set body or pair")
            cache.getOrCompute(CacheKey("FindReturns", request), ReturnsResponse.parser()) {
                ephemeris.session {
                    val value = if (pair != null) pairValue(natal, pair, frame) else longitude(natal, checkNotNull(body), frame)
                    val search = if (pair != null) pairSearch(pair, frame) else search(checkNotNull(body), frame, forCrossings = true)
                    // One extra result, in case one of them is the natal instant itself.
                    val hits =
                        search
                            .crossings(from, to, LongitudeTargets.Explicit(listOf(value)), limits.maxItems + 1)
                            .filter { abs(it.jd - natal) > NATAL_INSTANT_DAYS }
                    if (hits.size > limits.maxItems) throw MotionSearch.TooManyResultsException(limits.maxItems)
                    val response = ReturnsResponse.newBuilder().setNatalValue(value)
                    for (hit in hits) {
                        response.addReturns(
                            ReturnCrossing
                                .newBuilder()
                                .setInstant(JulianDay.toTimestamp(hit.jd))
                                .setBackwards(hit.retrograde),
                        )
                    }
                    response.build()
                }
            }
        }

    override suspend fun getSolarTime(request: SolarTimeRequest): SolarTimeResponse =
        guarded {
            val queries = instantsAtPlaces(request.queriesList)
            ephemeris.session {
                val response = SolarTimeResponse.newBuilder()
                for ((jd, place) in queries) {
                    val localMean = place.longitude * SECONDS_PER_DEGREE
                    val equation = equationOfTime(jd) * SECONDS_PER_DAY
                    response.addResults(
                        SolarTime
                            .newBuilder()
                            .setLocalMeanOffset(duration(localMean))
                            .setEquationOfTime(duration(equation))
                            .setLocalApparentOffset(duration(localMean + equation)),
                    )
                }
                response.build()
            }
        }

    override suspend fun getUpagrahas(request: UpagrahasRequest): UpagrahasResponse =
        guarded {
            val queries = instantsAtPlaces(request.queriesList, horizon = true)
            val settings =
                UpagrahaSettings(
                    frame = ProtoMapping.frame(request.zodiac, request.hasZodiac()),
                    riseMode = ProtoMapping.riseSetMode(RiseSetEvent.RISE, request.sunrise),
                    setMode = ProtoMapping.riseSetMode(RiseSetEvent.SET, request.sunrise),
                    kalaPoint = if (request.hasKalaPoint()) ProtoMapping.portionPoint(request.kalaPoint) else 0.0,
                    mandiPoint = if (request.hasMandiPoint()) ProtoMapping.portionPoint(request.mandiPoint) else null,
                )
            ephemeris.session {
                val response = UpagrahasResponse.newBuilder()
                for ((jd, place) in queries) response.addResults(upagrahas(jd, place, settings))
                response.build()
            }
        }

    override suspend fun getMeanPositions(request: MeanPositionsRequest): MeanPositionsResponse =
        guarded {
            requireNotEmpty(request.instantsCount, "instants")
            requireNotEmpty(request.bodiesCount, "bodies")
            requireWithinLimit(request.instantsCount.toLong() * request.bodiesCount, "instants x bodies")
            if (request.bodiesList.any { it !in MEAN_BODIES }) invalid("mean positions exist for the Sun, the Moon and Mercury to Saturn only")
            when (request.model) {
                MeanModel.MODERN -> Unit
                MeanModel.CLASSICAL ->
                    throw status(Status.UNIMPLEMENTED, "the CLASSICAL model awaits the constants of its tables; use MODERN")
                MeanModel.MEAN_MODEL_UNSPECIFIED, MeanModel.UNRECOGNIZED, null -> invalid("model must be specified")
            }
            val frame = ProtoMapping.frame(request.zodiac, request.hasZodiac())
            val instants = request.instantsList.map { it to julianDay(it) }
            ephemeris.session {
                val response = MeanPositionsResponse.newBuilder().setFrame(MeanFrame.REQUEST_ZODIAC)
                for ((timestamp, jd) in instants) response.addResults(meanPositions(timestamp, jd, request.bodiesList, frame))
                response.build()
            }
        }

    override suspend fun getInfo(request: InfoRequest): InfoResponse =
        InfoResponse
            .newBuilder()
            .setEngineVersion(ephemeris.version)
            .setSupportedFrom(timestamp(limits.supportedFrom))
            .setSupportedTo(timestamp(limits.supportedTo))
            .setMaxItemsPerRequest(limits.maxItems)
            .setMaxStreamItems(limits.maxStreamItems)
            .setSourceUrl(sourceUrl)
            .setServiceVersion(SERVICE_VERSION)
            .build()

    // ------------------------------------------------------------------------------------------ helpers

    private class StreamPlan(
        val from: Double,
        val step: Double,
        val samples: Long,
        val bodies: List<Pair<ProtoBody, Int>>,
        val frame: Frame,
    )

    @Suppress("LongParameterList")
    private fun SweSession.positionsAt(
        timestamp: Timestamp,
        jd: Double,
        bodies: List<Pair<ProtoBody, Int>>,
        frame: Frame,
        observer: Place?,
        equatorial: Boolean = false,
    ): InstantPositions {
        val result = InstantPositions.newBuilder().setInstant(timestamp)
        for ((protoBody, seBody) in bodies) {
            val p = position(jd, seBody, frame, observer)
            val position =
                BodyPosition
                    .newBuilder()
                    .setBody(protoBody)
                    .setLongitude(p.longitude)
                    .setLatitude(p.latitude)
                    .setDistanceAu(p.distance)
                    .setLongitudeSpeed(p.longitudeSpeed)
                    .setLatitudeSpeed(p.latitudeSpeed)
                    .setDistanceSpeed(p.distanceSpeed)
            if (equatorial) {
                val e = equatorial(jd, seBody, observer)
                position.setRightAscension(e.rightAscension).setDeclination(e.declination)
            }
            result.addPositions(position)
        }
        if (equatorial) {
            val nutation = nutation(jd)
            result.setTrueObliquity(nutation.trueObliquity).setNutationLongitude(nutation.longitude)
        }
        return result.build()
    }

    private fun SweSession.search(
        seBody: Int,
        frame: Frame,
        forCrossings: Boolean,
    ): MotionSearch =
        MotionSearch(
            source =
                object : MotionSource {
                    override fun at(jd: Double): Motion {
                        val p = position(jd, seBody, frame)
                        return Motion(p.longitude, p.longitudeSpeed)
                    }

                    override fun longitudeAt(jd: Double): Double =
                        if (searchWithoutSpeed) longitude(jd, seBody, frame) else at(jd).longitude
                },
            stationStep = if (forCrossings) ProtoMapping.crossingStationStep(seBody) else ProtoMapping.stationStep(seBody),
            canStation = ProtoMapping.canStation(seBody),
        )

    /** Targets from a request's `oneof targets`, which sets [grid], [list] or neither. */
    private fun targets(
        grid: LongitudeGrid?,
        list: LongitudeList?,
    ): LongitudeTargets {
        if (grid == null && list == null) invalid("set grid or list")
        if (grid != null) {
            if (!(grid.stepDegrees > 0.0 && grid.stepDegrees <= FULL_CIRCLE)) invalid("step_degrees must be in (0, 360]")
            if (!grid.offsetDegrees.isFinite()) invalid("offset_degrees must be finite")
            return try {
                LongitudeTargets.Grid.ofStep(grid.stepDegrees, grid.offsetDegrees)
            } catch (e: IllegalArgumentException) {
                invalid("step_degrees: ${e.message}")
            }
        }
        val longitudes = checkNotNull(list).longitudesList
        requireNotEmpty(longitudes.size, "longitudes")
        requireWithinLimit(longitudes.size.toLong(), "longitudes")
        if (longitudes.any { !it.isFinite() }) invalid("longitudes must be finite")
        return LongitudeTargets.Explicit(longitudes)
    }

    private fun crossingsResponse(hits: List<CrossingHit>): CrossingsResponse {
        val response = CrossingsResponse.newBuilder()
        for (hit in hits) {
            response.addCrossings(
                Crossing
                    .newBuilder()
                    .setInstant(JulianDay.toTimestamp(hit.jd))
                    .setLongitude(hit.longitude)
                    .setRetrograde(hit.retrograde),
            )
        }
        return response.build()
    }

    /** The difference or sum of two bodies' longitudes as one moving point. */
    private fun SweSession.pairSearch(
        pair: ProtoMapping.PairSpec,
        frame: Frame,
    ): MotionSearch =
        MotionSearch(
            source =
                object : MotionSource {
                    override fun at(jd: Double): Motion {
                        val a = position(jd, pair.bodyA, frame)
                        val b = position(jd, pair.bodyB, frame)
                        return Motion(
                            MotionSearch.normalize(a.longitude + pair.sign * b.longitude),
                            a.longitudeSpeed + pair.sign * b.longitudeSpeed,
                        )
                    }

                    override fun longitudeAt(jd: Double): Double =
                        if (searchWithoutSpeed) pairValue(jd, pair, frame) else at(jd).longitude
                },
            stationStep = ProtoMapping.pairStationStep(pair.bodyA, pair.bodyB),
            canStation = ProtoMapping.pairCanStation(pair.bodyA, pair.bodyB),
            crossingStep = ProtoMapping.pairCrossingStep(pair.bodyA, pair.bodyB),
        )

    private fun SweSession.pairValue(
        jd: Double,
        pair: ProtoMapping.PairSpec,
        frame: Frame,
    ): Double = MotionSearch.normalize(longitude(jd, pair.bodyA, frame) + pair.sign * longitude(jd, pair.bodyB, frame))

    private fun eclipse(
        kind: EclipseKind,
        event: EclipseEvent,
    ): Eclipse {
        val eclipse =
            Eclipse
                .newBuilder()
                .setKind(kind)
                .setType(ProtoMapping.eclipseType(event.type))
                .setMaximum(JulianDay.toTimestamp(event.maximum))
                .setMagnitude(event.magnitude)
        event.begin?.let { eclipse.setBegin(JulianDay.toTimestamp(it)) }
        event.end?.let { eclipse.setEnd(JulianDay.toTimestamp(it)) }
        event.totalityBegin?.let { eclipse.setTotalityBegin(JulianDay.toTimestamp(it)) }
        event.totalityEnd?.let { eclipse.setTotalityEnd(JulianDay.toTimestamp(it)) }
        return eclipse.build()
    }

    private fun range(
        hasFrom: Boolean,
        from: Timestamp,
        hasTo: Boolean,
        to: Timestamp,
    ): Pair<Double, Double> {
        if (!hasFrom || !hasTo) invalid("from and to are required")
        val start = julianDay(from)
        val end = julianDay(to)
        if (end <= start) invalid("to must be after from")
        return start to end
    }

    private fun julianDay(timestamp: Timestamp): Double {
        val instant =
            try {
                Instant.ofEpochSecond(timestamp.seconds, timestamp.nanos.toLong())
            } catch (e: DateTimeException) {
                invalid("invalid timestamp: ${e.message}")
            }
        if (instant.isBefore(limits.supportedFrom) || instant.isAfter(limits.supportedTo)) {
            throw OutOfRangeException("$instant is outside ${limits.supportedFrom} .. ${limits.supportedTo}")
        }
        val jd = JulianDay.of(timestamp)
        check(jd in supportedFrom..supportedTo)
        return jd
    }

    /** Validated {instant, place} queries of GetSolarTime and GetUpagrahas ([horizon]: the places see rises and sets). */
    private fun instantsAtPlaces(
        queries: List<InstantAtPlace>,
        horizon: Boolean = false,
    ): List<Pair<Double, Place>> {
        requireNotEmpty(queries.size, "queries")
        requireWithinLimit(queries.size.toLong(), "queries")
        return queries.map { query ->
            if (!query.hasInstant()) invalid("every query needs an instant")
            if (!query.hasLocation()) invalid("every query needs a location")
            julianDay(query.instant) to if (horizon) ProtoMapping.horizonPlace(query.location) else ProtoMapping.place(query.location)
        }
    }

    private fun requireNotEmpty(
        count: Int,
        what: String,
    ) {
        if (count == 0) invalid("$what must not be empty")
    }

    private fun requireWithinLimit(
        count: Long,
        what: String,
    ) {
        if (count > limits.maxItems) invalid("$what ($count) exceeds the limit of ${limits.maxItems}")
    }

    private class OutOfRangeException(
        message: String,
    ) : RuntimeException(message)

    private suspend fun <T> guarded(block: suspend () -> T): T =
        try {
            block()
        } catch (e: InvalidRequestException) {
            throw status(Status.INVALID_ARGUMENT, e.message)
        } catch (e: MotionSearch.TooManyResultsException) {
            throw status(Status.INVALID_ARGUMENT, e.message)
        } catch (e: OutOfRangeException) {
            throw status(Status.OUT_OF_RANGE, e.message)
        } catch (e: RejectedExecutionException) {
            throw status(Status.RESOURCE_EXHAUSTED, "the service is busy; retry with backoff")
        } catch (e: EphemerisException) {
            log.log(Level.WARNING, "libswe call failed", e)
            throw status(Status.INTERNAL, e.message)
        }

    private companion object {
        const val STREAM_CHUNK = 500L

        /** From the jar manifest; "dev" when running from classes (tests, IDE). */
        val SERVICE_VERSION: String = EphemerisService::class.java.`package`?.implementationVersion ?: "dev"
        const val FULL_CIRCLE = 360.0
        val MEAN_BODIES =
            setOf(ProtoBody.SUN, ProtoBody.MOON, ProtoBody.MERCURY, ProtoBody.VENUS, ProtoBody.MARS, ProtoBody.JUPITER, ProtoBody.SATURN)
        const val SECONDS_PER_DAY = 86_400.0

        /** Local mean time runs 4 minutes ahead per degree of longitude east. */
        const val SECONDS_PER_DEGREE = 240.0
        const val NANOS_PER_SECOND = 1_000_000_000L

        /** A crossing this close to the natal instant is the natal value itself, not a return (1 s). */
        const val NATAL_INSTANT_DAYS = 1.0 / 86_400
        const val SRIPATI = 'S'
        const val PORPHYRY = 'O'

        /**
         * Angles are sampled every 6 hours. Both only move forward and cover a full turn in one sidereal day, so any
         * step under a day is unambiguous; a longer one costs fewer libswe calls and a few more bisection steps.
         */
        const val ANGLE_SAMPLE_STEP = 0.25

        /** The polar circles are at 66.53 degrees or more over 1800-2400; beyond them the ascendant jumps. */
        const val MAX_ASCENDANT_LATITUDE = 66.5

        /**
         * Eclipses last under 0.5 day, so a search that starts this much earlier still finds one whose local maximum
         * moved to a later sunrise or moonrise, and one that starts this much after a maximum skips that eclipse.
         */
        const val ECLIPSE_MARGIN_DAYS = 0.5
        val log: Logger = Logger.getLogger(EphemerisService::class.java.name)

        fun status(
            status: Status,
            message: String?,
        ): StatusException = status.withDescription(message).asException()

        /** A protobuf Duration, to the nanosecond; seconds and nanos share the sign as protobuf requires. */
        fun duration(seconds: Double): Duration {
            val nanos = Math.round(seconds * NANOS_PER_SECOND)
            return Duration
                .newBuilder()
                .setSeconds(nanos / NANOS_PER_SECOND)
                .setNanos((nanos % NANOS_PER_SECOND).toInt())
                .build()
        }

        fun timestamp(instant: Instant): Timestamp =
            Timestamp
                .newBuilder()
                .setSeconds(instant.epochSecond)
                .setNanos(instant.nano)
                .build()
    }
}
