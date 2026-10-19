// SPDX-License-Identifier: AGPL-3.0-or-later
package me.lagna.ephemeris.grpc

import com.google.protobuf.Duration
import io.grpc.Status
import io.grpc.StatusException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import me.lagna.ephemeris.cache.NoopResultCache
import me.lagna.ephemeris.search.MotionSearch
import me.lagna.ephemeris.timestamp
import me.lagna.ephemeris.toInstant
import me.lagna.ephemeris.v1.Ayanamsa
import me.lagna.ephemeris.v1.Body
import me.lagna.ephemeris.v1.CrossingsRequest
import me.lagna.ephemeris.v1.GeoLocation
import me.lagna.ephemeris.v1.InstantAtPlace
import me.lagna.ephemeris.v1.LongitudeList
import me.lagna.ephemeris.v1.PairCombination
import me.lagna.ephemeris.v1.PositionsRequest
import me.lagna.ephemeris.v1.ReturnsRequest
import me.lagna.ephemeris.v1.SolarTimeRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs

class ReturnsAndSolarTimeTest {
    private val service = InProcessService(NoopResultCache)
    private val client = service.client
    private val lahiri = sidereal(Ayanamsa.LAHIRI)

    /** 12:44 on 1994-04-23 at +08:00 (Ipoh). */
    private val natal = timestamp("1994-04-23T04:44:00Z")

    @AfterEach
    fun close() = service.close()

    @Test
    fun `sidereal solar return of 2026 matches the crossing of the natal Sun`() {
        runBlocking {
            val request =
                ReturnsRequest
                    .newBuilder()
                    .setBody(Body.SUN)
                    .setNatal(natal)
                    .setZodiac(lahiri)
                    .setFrom(timestamp("2026-01-01T00:00:00Z"))
                    .setTo(timestamp("2027-01-01T00:00:00Z"))
                    .build()
            val response = client.findReturns(request)
            val natalSun =
                client
                    .getPositions(PositionsRequest.newBuilder().addInstants(natal).addBodies(Body.SUN).setZodiac(lahiri).build())
                    .getResults(0)
                    .getPositions(0)
                    .longitude
            response.natalValue shouldBe (natalSun plusOrMinus 1e-9)
            val solarReturn = response.returnsList.single()
            solarReturn.backwards shouldBe false
            // 17:42 at +08:00 on 2026-04-23
            abs(solarReturn.instant.toInstant().epochSecond - Instant.parse("2026-04-23T09:42:00Z").epochSecond) shouldBeLessThanOrEqual 120L

            val crossing =
                client
                    .findCrossings(
                        CrossingsRequest
                            .newBuilder()
                            .setBody(Body.SUN)
                            .setZodiac(lahiri)
                            .setFrom(request.from)
                            .setTo(request.to)
                            .setList(LongitudeList.newBuilder().addLongitudes(response.natalValue))
                            .build(),
                    ).crossingsList
                    .single()
            crossing.instant shouldBe solarReturn.instant
        }
    }

    @Test
    fun `returns of the natal tithi angle and no return at the natal instant itself`() {
        runBlocking {
            val tithi =
                client.findReturns(
                    ReturnsRequest
                        .newBuilder()
                        .setPair(pair(Body.MOON, Body.SUN, PairCombination.DIFFERENCE))
                        .setNatal(natal)
                        .setFrom(timestamp("2026-01-01T00:00:00Z"))
                        .setTo(timestamp("2027-01-01T00:00:00Z"))
                        .build(),
                )
            (tithi.returnsCount in 12..13) shouldBe true
            val positions =
                client
                    .getPositions(
                        PositionsRequest
                            .newBuilder()
                            .addAllInstants(tithi.returnsList.map { it.instant })
                            .addBodies(Body.MOON)
                            .addBodies(Body.SUN)
                            .build(),
                    ).resultsList
            positions.forEach {
                val angle = MotionSearch.normalize(it.getPositions(0).longitude - it.getPositions(1).longitude)
                MotionSearch.signedDelta(angle - tithi.natalValue) shouldBe (0.0 plusOrMinus 1e-4)
            }

            val aroundBirth =
                client.findReturns(
                    ReturnsRequest
                        .newBuilder()
                        .setBody(Body.SUN)
                        .setNatal(natal)
                        .setFrom(timestamp("1994-01-01T00:00:00Z"))
                        .setTo(timestamp("1995-01-01T00:00:00Z"))
                        .build(),
                )
            aroundBirth.returnsList.shouldBeEmpty()

            val noSubject = ReturnsRequest.newBuilder().setNatal(natal).setFrom(natal).setTo(timestamp("1995-01-01T00:00:00Z")).build()
            shouldThrow<StatusException> { client.findReturns(noSubject) }.status.code shouldBe Status.Code.INVALID_ARGUMENT
        }
    }

    @Test
    fun `equation of time over 2026 and local mean time offsets`() {
        runBlocking {
            val kyiv = place(50.45, 30.52)
            val days = (0L until 365L).map { LocalDate.parse("2026-01-01").plusDays(it) }
            val response =
                client.getSolarTime(
                    SolarTimeRequest
                        .newBuilder()
                        .addAllQueries(days.map { query(it.atStartOfDay().toInstant(ZoneOffset.UTC).toString(), kyiv) })
                        .build(),
                )
            response.resultsList shouldHaveSize 365
            val minutes = response.resultsList.map { seconds(it.equationOfTime) / 60 }
            val (minimum, maximum) = minutes.indexOf(minutes.min()) to minutes.indexOf(minutes.max())
            minutes[minimum] shouldBe (-14.2 plusOrMinus 0.2)
            days[minimum].month.value shouldBe 2
            minutes[maximum] shouldBe (16.4 plusOrMinus 0.2)
            days[maximum].month.value shouldBe 11
            for (result in response.resultsList) {
                result.localMeanOffset shouldBe Duration.newBuilder().setSeconds(7324).setNanos(800_000_000).build()
                seconds(result.localApparentOffset) shouldBe (seconds(result.localMeanOffset) + seconds(result.equationOfTime) plusOrMinus 1e-9)
            }

            val newYork =
                client.getSolarTime(SolarTimeRequest.newBuilder().addQueries(query("2026-01-01T00:00:00Z", place(40.7, -74.0))).build())
            newYork.getResults(0).localMeanOffset shouldBe Duration.newBuilder().setSeconds(-17_760).build()
        }
    }

    private fun query(
        iso: String,
        location: GeoLocation,
    ): InstantAtPlace =
        InstantAtPlace
            .newBuilder()
            .setInstant(timestamp(iso))
            .setLocation(location)
            .build()

    private fun seconds(duration: Duration): Double = duration.seconds + duration.nanos / 1e9

    private fun place(
        latitude: Double,
        longitude: Double,
    ): GeoLocation =
        GeoLocation
            .newBuilder()
            .setLatitude(latitude)
            .setLongitude(longitude)
            .build()
}
