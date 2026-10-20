// SPDX-License-Identifier: AGPL-3.0-or-later
package me.lagna.ephemeris.grpc

import com.google.protobuf.Timestamp
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import me.lagna.ephemeris.cache.NoopResultCache
import me.lagna.ephemeris.search.MotionSearch
import me.lagna.ephemeris.timestamp
import me.lagna.ephemeris.toInstant
import me.lagna.ephemeris.v1.Ayanamsa
import me.lagna.ephemeris.v1.Body
import me.lagna.ephemeris.v1.PositionsRequest
import me.lagna.ephemeris.v1.ReturnsRequest
import me.lagna.ephemeris.v1.ReturnsResponse
import me.lagna.ephemeris.v1.Zodiac
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

/**
 * When the search range starts a whole number of days before the natal instant, one daily sample falls on it, where
 * the body is at its natal value up to round-off. 0.2.1 could then report a "return" one day later (or at `to`) at
 * which the body was a day's motion away from its natal value.
 */
class NearNatalReturnsTest {
    private val service = InProcessService(NoopResultCache)
    private val client = service.client
    private val lahiri = sidereal(Ayanamsa.LAHIRI)
    private val tropical = Zodiac.getDefaultInstance()

    @AfterEach
    fun close() = service.close()

    @Test
    fun `the solar returns around 1951 are the three real ones`() {
        runBlocking {
            val natal = "1951-05-27T15:32:00Z"
            val response = returns(Body.SUN, natal, lahiri, -400.0, 800.0)
            response.returnsList shouldHaveSize 3
            listOf("1950-05-27T09:18:38Z", "1952-05-26T21:38:35Z", "1953-05-27T03:54:55Z").zip(response.returnsList).forEach { (expected, actual) ->
                (abs(Duration.between(Instant.parse(expected), actual.instant.toInstant()).toSeconds()) <= 1) shouldBe true
            }
            assertAtNatalValue(Body.SUN, lahiri, response)
        }
    }

    @Test
    fun `no return a day after the natal instant, whatever the body, frame or whole-day range`() {
        runBlocking {
            val natals =
                listOf("1918-10-16T08:55:56Z", "1951-05-27T15:32:00Z", "1969-07-20T20:17:00Z", "1985-07-14T06:20:00Z", "1990-01-01T00:00:00Z", "2001-03-03T12:00:00Z")
            val bodies = listOf(Body.SUN, Body.MOON, Body.MERCURY, Body.VENUS, Body.MARS, Body.JUPITER, Body.SATURN, Body.TRUE_NODE)
            val ranges = listOf(-3.0 to 3.0, -10.0 to 10.0, 0.0 to 10.0, -2.0 to 0.99, -9.0 to 10.0)
            var checked = 0
            for (natal in natals) for (body in bodies) for (zodiac in listOf(tropical, lahiri)) for ((from, to) in ranges) {
                assertAtNatalValue(body, zodiac, returns(body, natal, zodiac, from, to))
                checked++
            }
            checked shouldBe natals.size * bodies.size * 2 * ranges.size
        }
    }

    @Test
    fun `real crossings near the natal instant stay`() {
        runBlocking {
            // Saturn stationing near its natal longitude, the true node oscillating around it, Mercury retrograde.
            val cases =
                listOf(
                    Triple(Body.SATURN, "1951-05-27T15:32:00Z", listOf(3.03)),
                    Triple(Body.TRUE_NODE, "1951-05-27T15:32:00Z", listOf(0.458, 1.245)),
                    Triple(Body.TRUE_NODE, "1990-01-01T00:00:00Z", listOf(-1.395)),
                    Triple(Body.MERCURY, "1990-01-01T00:00:00Z", listOf(-2.063)),
                )
            for ((body, natal, offsets) in cases) {
                val response = returns(body, natal, lahiri, -4.0, 4.0)
                val found = response.returnsList.map { daysBetween(timestamp(natal), it.instant) }
                withClue("$body $natal: $found") {
                    for (offset in offsets) found.any { abs(it - offset) < 0.01 } shouldBe true
                }
                assertAtNatalValue(body, lahiri, response)
            }
        }
    }

    private suspend fun returns(
        body: Body,
        natal: String,
        zodiac: Zodiac,
        fromDays: Double,
        toDays: Double,
    ): ReturnsResponse {
        val instant = timestamp(natal)
        return client.findReturns(
            ReturnsRequest
                .newBuilder()
                .setBody(body)
                .setNatal(instant)
                .setZodiac(zodiac)
                .setFrom(instant.plusDays(fromDays))
                .setTo(instant.plusDays(toDays))
                .build(),
        )
    }

    /** At every reported return the body is at its natal value, to within a second of its motion. */
    private suspend fun assertAtNatalValue(
        body: Body,
        zodiac: Zodiac,
        response: ReturnsResponse,
    ) {
        if (response.returnsCount == 0) return
        val positions =
            client
                .getPositions(
                    PositionsRequest
                        .newBuilder()
                        .addAllInstants(response.returnsList.map { it.instant })
                        .addBodies(body)
                        .setZodiac(zodiac)
                        .build(),
                ).resultsList
                .map { it.getPositions(0) }
        response.returnsList.zip(positions).forEach { (crossing, position) ->
            withClue("$body ${crossing.instant.toInstant()}: ${position.longitude} vs ${response.natalValue}") {
                abs(MotionSearch.signedDelta(position.longitude - response.natalValue)) shouldBeLessThan
                    abs(position.longitudeSpeed) / 86_400 + 1e-9
            }
        }
    }

    private fun Timestamp.plusDays(days: Double): Timestamp {
        val nanos = Math.round(days * 86_400e9)
        val total = seconds * 1_000_000_000L + this.nanos + nanos
        return toBuilder()
            .setSeconds(Math.floorDiv(total, 1_000_000_000L))
            .setNanos(Math.floorMod(total, 1_000_000_000L).toInt())
            .build()
    }

    private fun daysBetween(
        a: Timestamp,
        b: Timestamp,
    ): Double = Duration.between(a.toInstant(), b.toInstant()).toNanos() / 86_400e9
}
