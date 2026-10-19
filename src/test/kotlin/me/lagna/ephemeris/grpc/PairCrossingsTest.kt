// SPDX-License-Identifier: AGPL-3.0-or-later
package me.lagna.ephemeris.grpc

import io.grpc.Status
import io.grpc.StatusException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.lagna.ephemeris.cache.CaffeineResultCache
import me.lagna.ephemeris.search.MotionSearch
import me.lagna.ephemeris.timestamp
import me.lagna.ephemeris.toInstant
import me.lagna.ephemeris.v1.Ayanamsa
import me.lagna.ephemeris.v1.AyanamsaRequest
import me.lagna.ephemeris.v1.Body
import me.lagna.ephemeris.v1.LongitudeGrid
import me.lagna.ephemeris.v1.LongitudeList
import me.lagna.ephemeris.v1.PairCombination
import me.lagna.ephemeris.v1.PairCrossing
import me.lagna.ephemeris.v1.PairCrossingsRequest
import me.lagna.ephemeris.v1.PositionRangeRequest
import me.lagna.ephemeris.v1.PositionsRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.ceil

class PairCrossingsTest {
    private val cache = CaffeineResultCache(maxBytes = 1_000_000)
    private val service = InProcessService(cache)
    private val client = service.client
    private val lahiri = sidereal(Ayanamsa.LAHIRI)

    /** Just before the new moon of 2000-01-06 18:14 UT, for one mean synodic month. */
    private val monthFrom = "2000-01-06T18:00:00Z"
    private val monthTo = "2000-02-05T06:43:12Z"

    @AfterEach
    fun close() = service.close()

    @Test
    fun `30 tithis in a synodic month, matching positions sampled every 0_005 day`() {
        runBlocking {
            val crossings = client.findPairCrossings(moonSun(PairCombination.DIFFERENCE, monthFrom, monthTo, 12.0)).crossingsList
            crossings shouldHaveSize 30
            crossings.map { it.value } shouldBe (0 until 30).map { it * 12.0 }
            crossings.none { it.decreasing } shouldBe true
            secondsBetween(crossings[0].instant.toInstant(), Instant.parse("2000-01-06T18:14:00Z")) shouldBeLessThan 60.0

            val sampled = sampledCrossings(monthFrom, monthTo, step = 0.005, every = 12.0)
            sampled shouldHaveSize 30
            crossings.zip(sampled).forEach { (crossing, expected) ->
                secondsBetween(crossing.instant.toInstant(), expected) shouldBeLessThanOrEqual 1.0
            }
        }
    }

    @Test
    fun `a difference does not depend on the zodiac, a sum moves by twice the ayanamsa`() {
        runBlocking {
            val tropical = client.findPairCrossings(moonSun(PairCombination.DIFFERENCE, monthFrom, monthTo, 6.0)).crossingsList
            val sidereal =
                client
                    .findPairCrossings(moonSun(PairCombination.DIFFERENCE, monthFrom, monthTo, 6.0).toBuilder().setZodiac(lahiri).build())
                    .crossingsList
            tropical shouldHaveSize 60 // karanas
            sidereal.map { it.value } shouldBe tropical.map { it.value }
            sidereal.zip(tropical).forEach { (s, t) -> secondsBetween(s.instant.toInstant(), t.instant.toInstant()) shouldBeLessThanOrEqual 0.2 }

            // Nithya yogas: the sidereal Moon + Sun crosses multiples of 360/27.
            val yogas =
                client
                    .findPairCrossings(moonSun(PairCombination.SUM, monthFrom, monthTo, 360.0 / 27).toBuilder().setZodiac(lahiri).build())
                    .crossingsList
            (yogas.size in 31..32) shouldBe true // Moon + Sun moves about 418 degrees in a month
            val positions = tropicalSum(yogas)
            val ayanamsa =
                client
                    .getAyanamsa(AyanamsaRequest.newBuilder().addAllInstants(yogas.map { it.instant }).setAyanamsa(Ayanamsa.LAHIRI).build())
                    .degreesList
            yogas.indices.forEach { i ->
                abs(MotionSearch.signedDelta(positions[i] - 2 * ayanamsa[i] - yogas[i].value)) shouldBeLessThan 1e-4
            }
        }
    }

    @Test
    fun `Venus meets the Sun moving backwards at inferior and forwards at superior conjunction`() {
        runBlocking {
            val conjunctions =
                client
                    .findPairCrossings(
                        PairCrossingsRequest
                            .newBuilder()
                            .setPair(pair(Body.VENUS, Body.SUN, PairCombination.DIFFERENCE))
                            .setFrom(timestamp("2020-01-01T00:00:00Z"))
                            .setTo(timestamp("2022-01-01T00:00:00Z"))
                            .setList(LongitudeList.newBuilder().addLongitudes(0.0))
                            .build(),
                    ).crossingsList
            conjunctions.map { it.instant.toInstant().atOffset(ZoneOffset.UTC).toLocalDate() } shouldBe
                listOf(LocalDate.parse("2020-06-03"), LocalDate.parse("2021-03-26"))
            conjunctions.map { it.decreasing } shouldBe listOf(true, false)
            for (conjunction in conjunctions) {
                val (venus, sun) =
                    client
                        .getPositions(
                            PositionsRequest
                                .newBuilder()
                                .addInstants(conjunction.instant)
                                .addBodies(Body.VENUS)
                                .addBodies(Body.SUN)
                                .build(),
                        ).getResults(0)
                        .positionsList
                venus.longitude shouldBe (sun.longitude plusOrMinus 1e-5)
            }
        }
    }

    @Test
    fun `the great conjunction of Jupiter and Saturn and the cache serves the repeat`() {
        runBlocking {
            val request =
                PairCrossingsRequest
                    .newBuilder()
                    .setPair(pair(Body.JUPITER, Body.SATURN, PairCombination.DIFFERENCE))
                    .setFrom(timestamp("2000-01-01T00:00:00Z"))
                    .setTo(timestamp("2030-01-01T00:00:00Z"))
                    .setList(LongitudeList.newBuilder().addLongitudes(0.0))
                    .build()
            val conjunctions = client.findPairCrossings(request).crossingsList
            conjunctions.map { it.instant.toInstant().atOffset(ZoneOffset.UTC).toLocalDate() } shouldBe
                listOf(LocalDate.parse("2000-05-28"), LocalDate.parse("2020-12-21"))
            client.findPairCrossings(request).crossingsList shouldBe conjunctions
            cache.hitCount() shouldBe 1L
        }
    }

    @Test
    fun `malformed pair requests are rejected`() {
        runBlocking {
            val valid = moonSun(PairCombination.DIFFERENCE, monthFrom, monthTo, 12.0)
            val invalid =
                listOf(
                    valid.toBuilder().setPair(pair(Body.MOON, Body.MOON, PairCombination.DIFFERENCE)).build(),
                    valid.toBuilder().setPair(pair(Body.MOON, Body.SUN, PairCombination.PAIR_COMBINATION_UNSPECIFIED)).build(),
                    valid.toBuilder().clearPair().build(),
                    valid.toBuilder().clearGrid().build(),
                    valid.toBuilder().setGrid(LongitudeGrid.newBuilder().setStepDegrees(7.0)).build(),
                )
            for (request in invalid) {
                shouldThrow<StatusException> { client.findPairCrossings(request) }.status.code shouldBe Status.Code.INVALID_ARGUMENT
            }
        }
    }

    private fun moonSun(
        combination: PairCombination,
        from: String,
        to: String,
        step: Double,
    ): PairCrossingsRequest =
        PairCrossingsRequest
            .newBuilder()
            .setPair(pair(Body.MOON, Body.SUN, combination))
            .setFrom(timestamp(from))
            .setTo(timestamp(to))
            .setGrid(LongitudeGrid.newBuilder().setStepDegrees(step))
            .build()

    /** Tropical Moon + Sun at each crossing's instant. */
    private suspend fun tropicalSum(crossings: List<PairCrossing>): List<Double> =
        client
            .getPositions(
                PositionsRequest
                    .newBuilder()
                    .addAllInstants(crossings.map { it.instant })
                    .addBodies(Body.MOON)
                    .addBodies(Body.SUN)
                    .build(),
            ).resultsList
            .map { it.getPositions(0).longitude + it.getPositions(1).longitude }

    /** Crossings of Moon - Sun over multiples of [every], interpolated between positions sampled every [step] days. */
    private suspend fun sampledCrossings(
        from: String,
        to: String,
        step: Double,
        every: Double,
    ): List<Instant> {
        val samples =
            client
                .streamPositions(
                    PositionRangeRequest
                        .newBuilder()
                        .setFrom(timestamp(from))
                        .setTo(timestamp(to))
                        .setStepDays(step)
                        .addBodies(Body.MOON)
                        .addBodies(Body.SUN)
                        .build(),
                ).toList()
                .flatMap { it.resultsList }
                .map { it.instant.toInstant() to MotionSearch.normalize(it.getPositions(0).longitude - it.getPositions(1).longitude) }
        val crossings = mutableListOf<Instant>()
        for ((a, b) in samples.zipWithNext()) {
            val moved = MotionSearch.normalize(b.second - a.second) // Moon - Sun only grows
            var target = ceil(a.second / every) * every
            while (target - a.second < moved) {
                val fraction = (target - a.second) / moved
                crossings += a.first.plusNanos((Duration.between(a.first, b.first).toNanos() * fraction).toLong())
                target += every
            }
        }
        return crossings
    }

    private fun secondsBetween(
        a: Instant,
        b: Instant,
    ): Double = abs(Duration.between(a, b).toNanos()) / 1e9
}
