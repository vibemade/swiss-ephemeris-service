// SPDX-License-Identifier: AGPL-3.0-or-later
package me.lagna.ephemeris.grpc

import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.lagna.ephemeris.TestEphemeris
import me.lagna.ephemeris.cache.CaffeineResultCache
import me.lagna.ephemeris.cache.NoopResultCache
import me.lagna.ephemeris.timestamp
import me.lagna.ephemeris.toInstant
import me.lagna.ephemeris.v1.Ayanamsa
import me.lagna.ephemeris.v1.AyanamsaRequest
import me.lagna.ephemeris.v1.Body
import me.lagna.ephemeris.v1.CrossingsRequest
import me.lagna.ephemeris.v1.EphemerisGrpcKt
import me.lagna.ephemeris.v1.GeoLocation
import me.lagna.ephemeris.v1.HouseQuery
import me.lagna.ephemeris.v1.HouseSystem
import me.lagna.ephemeris.v1.HousesRequest
import me.lagna.ephemeris.v1.InfoRequest
import me.lagna.ephemeris.v1.LongitudeGrid
import me.lagna.ephemeris.v1.PositionRangeRequest
import me.lagna.ephemeris.v1.PositionsRequest
import me.lagna.ephemeris.v1.RiseSetEvent
import me.lagna.ephemeris.v1.RiseSetQuery
import me.lagna.ephemeris.v1.RiseSetRequest
import me.lagna.ephemeris.v1.Sidereal
import me.lagna.ephemeris.v1.StationsRequest
import me.lagna.ephemeris.v1.Zodiac
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.math.abs

class EphemerisServiceTest {
    private val cache = CaffeineResultCache(maxBytes = 1_000_000)
    private val name = InProcessServerBuilder.generateName()
    private val server: Server =
        InProcessServerBuilder
            .forName(name)
            .directExecutor()
            .addService(EphemerisService(TestEphemeris.instance, cache))
            .build()
            .start()
    private val channel: ManagedChannel = InProcessChannelBuilder.forName(name).directExecutor().build()
    private val client = EphemerisGrpcKt.EphemerisCoroutineStub(channel)

    private val kyiv =
        GeoLocation
            .newBuilder()
            .setLatitude(50.45)
            .setLongitude(30.52)
            .build()
    private val lahiri =
        Zodiac
            .newBuilder()
            .setSidereal(Sidereal.newBuilder().setAyanamsa(Ayanamsa.LAHIRI))
            .build()

    @AfterEach
    fun close() {
        channel.shutdownNow()
        server.shutdownNow()
    }

    @Test
    fun `positions keep the order of instants and bodies`() {
        runBlocking {
            val response =
                client.getPositions(
                    PositionsRequest
                        .newBuilder()
                        .addInstants(timestamp("2000-01-01T12:00:00Z"))
                        .addInstants(timestamp("2024-03-20T03:06:24Z"))
                        .addBodies(Body.MOON)
                        .addBodies(Body.SUN)
                        .build(),
                )
            response.resultsList shouldHaveSize 2
            response.getResults(0).positionsList.map { it.body } shouldBe listOf(Body.MOON, Body.SUN)
            response.getResults(0).getPositions(0).longitude shouldBe (223.3237514 plusOrMinus 1e-6)
            response.getResults(1).getPositions(1).longitude.let { minOf(it, 360 - it) } shouldBe (0.0 plusOrMinus 1e-4)
        }
    }

    @Test
    fun `sidereal positions use the requested ayanamsa`() {
        runBlocking {
            val response =
                client.getPositions(
                    PositionsRequest
                        .newBuilder()
                        .addInstants(timestamp("2000-01-01T12:00:00Z"))
                        .addBodies(Body.MOON)
                        .setZodiac(lahiri)
                        .build(),
                )
            response.getResults(0).getPositions(0).longitude shouldBe (199.4705290 plusOrMinus 1e-6)
        }
    }

    @Test
    fun `invalid and out-of-range requests map to gRPC statuses`() {
        runBlocking {
            val unspecified =
                shouldThrow<StatusException> {
                    client.getPositions(
                        PositionsRequest
                            .newBuilder()
                            .addInstants(timestamp("2000-01-01T12:00:00Z"))
                            .addBodies(Body.BODY_UNSPECIFIED)
                            .build(),
                    )
                }
            unspecified.status.code shouldBe Status.Code.INVALID_ARGUMENT

            val tooEarly =
                shouldThrow<StatusException> {
                    client.getPositions(
                        PositionsRequest
                            .newBuilder()
                            .addInstants(timestamp("1700-01-01T00:00:00Z"))
                            .addBodies(Body.SUN)
                            .build(),
                    )
                }
            tooEarly.status.code shouldBe Status.Code.OUT_OF_RANGE
        }
    }

    @Test
    fun `sign ingresses of the Sun in 2024 and the cache serves the repeat`() {
        runBlocking {
            val request =
                CrossingsRequest
                    .newBuilder()
                    .setBody(Body.SUN)
                    .setFrom(timestamp("2024-01-01T00:00:00Z"))
                    .setTo(timestamp("2025-01-01T00:00:00Z"))
                    .setGrid(LongitudeGrid.newBuilder().setStepDegrees(30.0))
                    .build()
            val crossings = client.findCrossings(request).crossingsList
            crossings shouldHaveSize 12
            val equinox = crossings.single { it.longitude == 0.0 }
            secondsBetween(equinox.instant.toInstant(), Instant.parse("2024-03-20T03:06:24Z")) shouldBeLessThanOrEqual 1L

            client.findCrossings(request).crossingsList shouldBe crossings
            cache.hitCount() shouldBe 1L
        }
    }

    @Test
    fun `crossings without speed are identical to crossings with speed`() {
        val withSpeedName = InProcessServerBuilder.generateName()
        val withSpeedServer =
            InProcessServerBuilder
                .forName(withSpeedName)
                .directExecutor()
                .addService(EphemerisService(TestEphemeris.instance, NoopResultCache, searchWithoutSpeed = false))
                .build()
                .start()
        val withSpeedChannel = InProcessChannelBuilder.forName(withSpeedName).directExecutor().build()
        val withSpeed = EphemerisGrpcKt.EphemerisCoroutineStub(withSpeedChannel)
        try {
            runBlocking {
                val requests =
                    listOf(
                        crossings(Body.MOON, "2024-01-01T00:00:00Z", "2025-01-01T00:00:00Z", 360.0 / 27, lahiri),
                        crossings(Body.MERCURY, "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z", 30.0, lahiri),
                        crossings(Body.TRUE_NODE, "2000-01-01T00:00:00Z", "2020-01-01T00:00:00Z", 30.0, null),
                    )
                for (request in requests) {
                    val fast = client.findCrossings(request).crossingsList
                    (fast.size > 0) shouldBe true
                    fast shouldBe withSpeed.findCrossings(request).crossingsList
                }
            }
        } finally {
            withSpeedChannel.shutdownNow()
            withSpeedServer.shutdownNow()
        }
    }

    private fun crossings(
        body: Body,
        from: String,
        to: String,
        step: Double,
        zodiac: Zodiac?,
    ): CrossingsRequest {
        val request =
            CrossingsRequest
                .newBuilder()
                .setBody(body)
                .setFrom(timestamp(from))
                .setTo(timestamp(to))
                .setGrid(LongitudeGrid.newBuilder().setStepDegrees(step))
        zodiac?.let { request.setZodiac(it) }
        return request.build()
    }

    @Test
    fun `Mercury stations in 2024`() {
        runBlocking {
            val stations =
                client
                    .findStations(
                        StationsRequest
                            .newBuilder()
                            .setBody(Body.MERCURY)
                            .setFrom(timestamp("2024-03-01T00:00:00Z"))
                            .setTo(timestamp("2024-06-01T00:00:00Z"))
                            .build(),
                    ).stationsList
            stations.map { it.turnsRetrograde } shouldBe listOf(true, false)
            secondsBetween(stations[0].instant.toInstant(), Instant.parse("2024-04-01T22:14:30Z")) shouldBeLessThanOrEqual 1L
            secondsBetween(stations[1].instant.toInstant(), Instant.parse("2024-04-25T12:54:08Z")) shouldBeLessThanOrEqual 1L
        }
    }

    @Test
    fun `houses report undefined Placidus cusps near the pole`() {
        runBlocking {
            val tromso =
                GeoLocation
                    .newBuilder()
                    .setLatitude(69.65)
                    .setLongitude(18.96)
                    .build()
            val response =
                client.getHouses(
                    HousesRequest
                        .newBuilder()
                        .setSystem(HouseSystem.PLACIDUS)
                        .addQueries(HouseQuery.newBuilder().setInstant(timestamp("2000-01-01T12:00:00Z")).setLocation(kyiv))
                        .addQueries(HouseQuery.newBuilder().setInstant(timestamp("2000-01-01T12:00:00Z")).setLocation(tromso))
                        .build(),
                )
            response.getResults(0).cuspsDefined shouldBe true
            response.getResults(0).cuspsList shouldHaveSize 12
            response.getResults(1).cuspsDefined shouldBe false
            response.getResults(1).cuspsList shouldHaveSize 0
        }
    }

    @Test
    fun `sunrise and sunset in Kyiv`() {
        runBlocking {
            val response =
                client.getRiseSet(
                    RiseSetRequest
                        .newBuilder()
                        .setBody(Body.SUN)
                        .addQueries(
                            RiseSetQuery
                                .newBuilder()
                                .setAfter(timestamp("2024-06-21T00:00:00Z"))
                                .setLocation(kyiv)
                                .addEvents(RiseSetEvent.RISE)
                                .addEvents(RiseSetEvent.SET),
                        ).build(),
                )
            val (rise, set) = response.getResults(0).timesList
            secondsBetween(rise.instant.toInstant(), Instant.parse("2024-06-21T01:46:00Z")) shouldBeLessThanOrEqual 2L
            secondsBetween(set.instant.toInstant(), Instant.parse("2024-06-21T18:13:38Z")) shouldBeLessThanOrEqual 2L
        }
    }

    @Test
    fun `positions stream in chunks`() {
        runBlocking {
            val chunks =
                client
                    .streamPositions(
                        PositionRangeRequest
                            .newBuilder()
                            .setFrom(timestamp("2024-01-01T00:00:00Z"))
                            .setTo(timestamp("2026-01-01T00:00:00Z"))
                            .setStepDays(1.0)
                            .addBodies(Body.MOON)
                            .build(),
                    ).toList()
            chunks.sumOf { it.resultsCount } shouldBe 732
            chunks shouldHaveSize 2
        }
    }

    @Test
    fun `ayanamsa matches swetest`() {
        runBlocking {
            val lahiri =
                client.getAyanamsa(
                    AyanamsaRequest
                        .newBuilder()
                        .addInstants(timestamp("2000-01-01T12:00:00Z"))
                        .setAyanamsa(Ayanamsa.LAHIRI)
                        .build(),
                )
            // swetest -b1.1.2000 -ut12:00 -ay1: 23°51'11.6009"
            lahiri.getDegrees(0) shouldBe (23.853222472 plusOrMinus 1e-6)
            val raman =
                client.getAyanamsa(
                    AyanamsaRequest
                        .newBuilder()
                        .addInstants(timestamp("2024-01-01T00:00:00Z"))
                        .setAyanamsa(Ayanamsa.RAMAN)
                        .build(),
                )
            // swetest -b1.1.2024 -ut0:00 -ay3: 22°44'40.3949"
            raman.getDegrees(0) shouldBe (22.744554139 plusOrMinus 1e-6)
            val unspecified =
                shouldThrow<StatusException> {
                    client.getAyanamsa(AyanamsaRequest.newBuilder().addInstants(timestamp("2000-01-01T12:00:00Z")).build())
                }
            unspecified.status.code shouldBe Status.Code.INVALID_ARGUMENT
        }
    }

    @Test
    fun `the ayanamsas added in 0_2_0 match swetest`() {
        // swetest -b1.1.2000 -ut12:00 -ay<SE_SIDM_*>
        val expected =
            mapOf(
                Ayanamsa.USHASHASHI to dms(20, 3, 13.2162),
                Ayanamsa.SURYASIDDHANTA to dms(20, 53, 28.2803),
                Ayanamsa.SURYASIDDHANTA_MSUN to dms(20, 40, 35.5981),
                Ayanamsa.ARYABHATA to dms(20, 53, 28.2835),
                Ayanamsa.ARYABHATA_MSUN to dms(20, 39, 12.8070),
                Ayanamsa.SS_REVATI to dms(20, 5, 58.2667),
                Ayanamsa.SS_CITRA to dms(23, 0, 6.8163),
                Ayanamsa.TRUE_MULA to dms(24, 34, 33.9785),
                Ayanamsa.ARYABHATA_522 to dms(20, 34, 19.1176),
                Ayanamsa.LAHIRI_1940 to dms(23, 50, 18.4322),
                Ayanamsa.LAHIRI_VP285 to dms(23, 51, 34.6009),
                Ayanamsa.KRISHNAMURTI_VP291 to dms(23, 46, 35.3824),
                Ayanamsa.LAHIRI_ICRC to dms(23, 51, 10.5089),
            )
        runBlocking {
            for ((ayanamsa, degrees) in expected) {
                val response =
                    client.getAyanamsa(
                        AyanamsaRequest
                            .newBuilder()
                            .addInstants(timestamp("2000-01-01T12:00:00Z"))
                            .setAyanamsa(ayanamsa)
                            .build(),
                    )
                withClue(ayanamsa) { response.getDegrees(0) shouldBe (degrees plusOrMinus 1e-6) }
            }
        }
    }

    @Test
    fun `equatorial coordinates, obliquity and nutation match swetest`() {
        runBlocking {
            val request =
                PositionsRequest
                    .newBuilder()
                    .addInstants(timestamp("2000-01-01T12:00:00Z"))
                    .addBodies(Body.SUN)
                    .addBodies(Body.MOON)
                    .setZodiac(lahiri) // equatorial coordinates ignore the zodiac
                    .setEquatorial(true)
                    .build()
            val result = client.getPositions(request).getResults(0)
            // swetest -b1.1.2000 -ut12:00 -p01 -fPad; -pe: true obliquity and nutation in longitude
            result.getPositions(0).rightAscension shouldBe (281.2783856 plusOrMinus 1e-6)
            result.getPositions(0).declination shouldBe (-23.0324304 plusOrMinus 1e-6)
            result.getPositions(1).rightAscension shouldBe (222.4521682 plusOrMinus 1e-6)
            result.getPositions(1).declination shouldBe (-10.9006385 plusOrMinus 1e-6)
            result.getPositions(1).longitude shouldBe (199.4705290 plusOrMinus 1e-6) // still sidereal
            result.trueObliquity shouldBe (23.4376767 plusOrMinus 1e-6)
            result.nutationLongitude shouldBe (-0.0038699 plusOrMinus 1e-6)

            // swetest ... -p0 -fPa -topo30.52,50.45,0
            val topocentric = client.getPositions(request.toBuilder().setObserver(kyiv).build()).getResults(0)
            topocentric.getPositions(0).rightAscension shouldBe (281.2775858 plusOrMinus 1e-6)

            val plain = client.getPositions(request.toBuilder().setEquatorial(false).build()).getResults(0)
            plain.hasTrueObliquity() shouldBe false
            plain.getPositions(0).hasRightAscension() shouldBe false
        }
    }

    @Test
    fun `queries without their instant are rejected`() {
        runBlocking {
            val houses =
                shouldThrow<StatusException> {
                    client.getHouses(
                        HousesRequest
                            .newBuilder()
                            .setSystem(HouseSystem.WHOLE_SIGN)
                            .addQueries(HouseQuery.newBuilder().setLocation(kyiv))
                            .build(),
                    )
                }
            houses.status.code shouldBe Status.Code.INVALID_ARGUMENT
            val riseSet =
                shouldThrow<StatusException> {
                    client.getRiseSet(
                        RiseSetRequest
                            .newBuilder()
                            .setBody(Body.SUN)
                            .addQueries(RiseSetQuery.newBuilder().setLocation(kyiv).addEvents(RiseSetEvent.RISE))
                            .build(),
                    )
                }
            riseSet.status.code shouldBe Status.Code.INVALID_ARGUMENT
        }
    }

    @Test
    fun `a grid step that does not divide 360 is rejected`() {
        runBlocking {
            val error =
                shouldThrow<StatusException> {
                    client.findCrossings(crossings(Body.SUN, "2024-01-01T00:00:00Z", "2025-01-01T00:00:00Z", 100.0, null))
                }
            error.status.code shouldBe Status.Code.INVALID_ARGUMENT
        }
    }

    @Test
    fun `dense crossings during a retrograde come back in time order`() {
        runBlocking {
            // Every arcminute while Mercury retrogrades about 15 degrees: many targets per daily sample.
            val crossings =
                client
                    .findCrossings(
                        crossings(Body.MERCURY, "2024-04-02T00:00:00Z", "2024-04-25T00:00:00Z", 1.0 / 60, null),
                    ).crossingsList
            (crossings.size > 500) shouldBe true
            crossings.all { it.retrograde } shouldBe true
            val instants = crossings.map { it.instant.toInstant() }
            instants shouldBe instants.sorted()
        }
    }

    @Test
    fun `info names the engine and the source`() {
        runBlocking {
            val info = client.getInfo(InfoRequest.getDefaultInstance())
            info.sourceUrl shouldBe "https://github.com/vibemade/swiss-ephemeris-service"
            info.engineVersion.startsWith("Swiss Ephemeris") shouldBe true
            info.maxStreamItems shouldBe 1_000_000L
        }
    }

    private fun secondsBetween(
        a: Instant,
        b: Instant,
    ): Long = abs(a.epochSecond - b.epochSecond)

    private fun dms(
        degrees: Int,
        minutes: Int,
        seconds: Double,
    ): Double = degrees + minutes / 60.0 + seconds / 3600.0
}
