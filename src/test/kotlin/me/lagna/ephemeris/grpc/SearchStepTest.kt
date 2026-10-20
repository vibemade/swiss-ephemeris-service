// SPDX-License-Identifier: AGPL-3.0-or-later
package me.lagna.ephemeris.grpc

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import me.lagna.ephemeris.TestEphemeris
import me.lagna.ephemeris.search.LongitudeTargets
import me.lagna.ephemeris.search.Motion
import me.lagna.ephemeris.search.MotionSearch
import me.lagna.ephemeris.search.MotionSource
import me.lagna.ephemeris.swe.Frame
import me.lagna.ephemeris.swe.JulianDay
import me.lagna.ephemeris.swe.SweConstants
import me.lagna.ephemeris.swe.SweSession
import me.lagna.ephemeris.timestamp
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/** The coarse production sampling steps find exactly what sampling every 0.1 day finds. */
class SearchStepTest {
    private val from = JulianDay.of(timestamp("2000-01-01T00:00:00Z"))
    private val to = JulianDay.of(timestamp("2040-01-01T00:00:00Z"))
    private val padas = LongitudeTargets.Grid(108)

    @ParameterizedTest
    @ValueSource(ints = [SweConstants.SE_MERCURY, SweConstants.SE_VENUS, SweConstants.SE_MARS, SweConstants.SE_JUPITER, SweConstants.SE_CHIRON])
    fun `station and crossing searches match a fine step`(body: Int) {
        TestEphemeris.instance.sessionBlocking {
            val coarse = search(body, ProtoMapping.stationStep(body))
            val fine = search(body, FINE_STEP)

            val expectedStations = fine.stations(from, to)
            val stations = coarse.stations(from, to)
            stations shouldHaveSize expectedStations.size
            stations.zip(expectedStations).forEach { (actual, expected) ->
                actual.turnsRetrograde shouldBe expected.turnsRetrograde
                actual.jd shouldBe (expected.jd plusOrMinus TIME_TOLERANCE)
            }

            assertSameCrossings(coarse, fine)
        }
    }

    @org.junit.jupiter.api.Test
    fun `true node crossings with the coarser crossing step match a fine step`() {
        val body = SweConstants.SE_TRUE_NODE
        TestEphemeris.instance.sessionBlocking {
            assertSameCrossings(search(body, ProtoMapping.crossingStationStep(body)), search(body, FINE_STEP))
        }
    }

    /** Differences (sign -1) and sums (+1) of pairs whose stations come closest together, and two slow pairs. */
    @ParameterizedTest
    @CsvSource("2, 3, -1", "2, 3, 1", "0, 2, 1", "3, 4, 1", "4, 5, -1", "5, 6, 1")
    fun `pair station and crossing searches match a fine step`(
        bodyA: Int,
        bodyB: Int,
        sign: Int,
    ) {
        val pairTo = JulianDay.of(timestamp("2030-01-01T00:00:00Z"))
        TestEphemeris.instance.sessionBlocking {
            val production =
                pairSearch(bodyA, bodyB, sign, ProtoMapping.pairStationStep(bodyA, bodyB), ProtoMapping.pairCrossingStep(bodyA, bodyB))
            val fine = pairSearch(bodyA, bodyB, sign, FINE_STEP, MotionSearch.DEFAULT_CROSSING_STEP_DAYS)
            val expectedStations = fine.stations(from, pairTo)
            val stations = production.stations(from, pairTo)
            stations.map { it.turnsRetrograde } shouldBe expectedStations.map { it.turnsRetrograde }
            stations.zip(expectedStations).forEach { (actual, expected) -> actual.jd shouldBe (expected.jd plusOrMinus TIME_TOLERANCE) }
            assertSameCrossings(production, fine, pairTo, LongitudeTargets.Grid(120))
        }
    }

    private fun SweSession.pairSearch(
        bodyA: Int,
        bodyB: Int,
        sign: Int,
        stationStep: Double,
        crossingStep: Double,
    ) = MotionSearch(
        source =
            object : MotionSource {
                override fun at(jd: Double): Motion {
                    val a = position(jd, bodyA)
                    val b = position(jd, bodyB)
                    return Motion(MotionSearch.normalize(a.longitude + sign * b.longitude), a.longitudeSpeed + sign * b.longitudeSpeed)
                }

                override fun longitudeAt(jd: Double): Double = MotionSearch.normalize(longitude(jd, bodyA) + sign * longitude(jd, bodyB))
            },
        stationStep = stationStep,
        canStation = ProtoMapping.pairCanStation(bodyA, bodyB),
        crossingStep = crossingStep,
    )

    private fun assertSameCrossings(
        coarse: MotionSearch,
        fine: MotionSearch,
        until: Double = to,
        targets: LongitudeTargets = padas,
    ) {
        val expected = fine.crossings(from, until, targets)
        val actual = coarse.crossings(from, until, targets)
        actual shouldHaveSize expected.size
        actual.zip(expected).forEach { (a, e) ->
            a.longitude shouldBe e.longitude
            a.retrograde shouldBe e.retrograde
            a.jd shouldBe (e.jd plusOrMinus TIME_TOLERANCE)
        }
    }

    private fun SweSession.search(
        body: Int,
        stationStep: Double,
    ) = MotionSearch(
        source =
            object : MotionSource {
                override fun at(jd: Double): Motion {
                    val p = position(jd, body, Frame.TROPICAL)
                    return Motion(p.longitude, p.longitudeSpeed)
                }

                override fun longitudeAt(jd: Double): Double = longitude(jd, body, Frame.TROPICAL)
            },
        stationStep = stationStep,
        canStation = ProtoMapping.canStation(body),
    )

    private companion object {
        const val FINE_STEP = 0.1

        /**
         * About 9 s. Both searches refine to 1e-6 day, but near a station a slow body barely moves (Chiron: about
         * 1e-10 degrees a second), so bisections over different brackets can settle a second or so apart.
         */
        const val TIME_TOLERANCE = 1e-4
    }
}
