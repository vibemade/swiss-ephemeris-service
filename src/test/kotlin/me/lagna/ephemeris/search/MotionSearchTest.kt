// SPDX-License-Identifier: AGPL-3.0-or-later
package me.lagna.ephemeris.search

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class MotionSearchTest {
    /** 1 degree per day starting at 355 degrees: crosses 0 at day 5, 30 at day 35. */
    private val linear = MotionSource { jd -> Motion(MotionSearch.normalize(355.0 + jd), 1.0) }

    /** Moves forward with a backward loop: speed 1 + 2 cos(2 pi t / 20) changes sign twice per 20 days. */
    private val looping =
        MotionSource { jd ->
            val omega = 2 * PI / 20
            Motion(MotionSearch.normalize(jd + 2 / omega * sin(omega * jd)), 1 + 2 * cos(omega * jd))
        }

    @org.junit.jupiter.api.Test
    fun `finds grid crossings across 360 degrees`() {
        val hits = MotionSearch(linear, stationStep = 1.0, canStation = false).crossings(0.0, 40.0, LongitudeTargets.Grid(12))
        hits.map { it.longitude } shouldBe listOf(0.0, 30.0)
        hits[0].jd shouldBe (5.0 plusOrMinus 1e-5)
        hits[1].jd shouldBe (35.0 plusOrMinus 1e-5)
        hits.none { it.retrograde } shouldBe true
    }

    @org.junit.jupiter.api.Test
    fun `finds explicit longitudes and ignores the start value`() {
        val hits =
            MotionSearch(linear, stationStep = 1.0, canStation = false)
                .crossings(0.0, 40.0, LongitudeTargets.Explicit(listOf(355.0, 10.0)))
        hits.map { it.longitude } shouldBe listOf(10.0)
    }

    @org.junit.jupiter.api.Test
    fun `stations split the motion so each target is crossed in both directions`() {
        val search = MotionSearch(looping, stationStep = 0.5, canStation = true)
        val stations = search.stations(0.0, 20.0)
        stations shouldHaveSize 2
        stations[0].turnsRetrograde shouldBe true
        stations[1].turnsRetrograde shouldBe false
        // speed = 0 where cos = -1/2: t = 20/3 and 40/3
        stations[0].jd shouldBe (20.0 / 3 plusOrMinus 1e-5)
        stations[1].jd shouldBe (40.0 / 3 plusOrMinus 1e-5)

        val target = looping.at(10.0).longitude // inside the retrograde loop
        val hits = search.crossings(0.0, 20.0, LongitudeTargets.Explicit(listOf(target)))
        hits.map { it.retrograde } shouldBe listOf(false, true, false)
        hits[1].jd shouldBe (10.0 plusOrMinus 1e-5)
    }

    @org.junit.jupiter.api.Test
    fun `crossing searches ask only for longitudes when the body cannot station`() {
        var fullCalls = 0
        var longitudeCalls = 0
        val counting =
            object : MotionSource {
                override fun at(jd: Double): Motion {
                    fullCalls++
                    return linear.at(jd)
                }

                override fun longitudeAt(jd: Double): Double {
                    longitudeCalls++
                    return linear.at(jd).longitude
                }
            }
        MotionSearch(counting, stationStep = 1.0, canStation = false).crossings(0.0, 40.0, LongitudeTargets.Grid(12))
        fullCalls shouldBe 0
        (longitudeCalls > 0) shouldBe true
    }

    @org.junit.jupiter.api.Test
    fun `stops at the result limit`() {
        shouldThrow<MotionSearch.TooManyResultsException> {
            MotionSearch(linear, stationStep = 1.0, canStation = false).crossings(0.0, 400.0, LongitudeTargets.Grid(360), limit = 10)
        }
    }

    @org.junit.jupiter.api.Test
    fun `grids must divide the circle`() {
        LongitudeTargets.Grid.ofStep(360.0 / 27).divisions shouldBe 27
        LongitudeTargets.Grid.ofStep(30.0, offset = 15.0).divisions shouldBe 12
        shouldThrow<IllegalArgumentException> { LongitudeTargets.Grid.ofStep(100.0) }
        shouldThrow<IllegalArgumentException> { LongitudeTargets.Grid.ofStep(1e-6) }
    }

    @org.junit.jupiter.api.Test
    fun `targets are generated lazily in the requested order`() {
        val grid = LongitudeTargets.Grid(12)
        grid.valuesIn(350.0, 400.0).map { it.longitude }.toList() shouldBe listOf(0.0, 30.0)
        grid.valuesIn(350.0, 400.0, descending = true).map { it.value }.toList() shouldBe listOf(390.0, 360.0)
        // A very fine grid over a wide interval: taking a few must not build them all.
        LongitudeTargets.Grid(LongitudeTargets.Grid.MAX_DIVISIONS).valuesIn(0.0, 1e9).take(3).count() shouldBe 3
        val list = LongitudeTargets.Explicit(listOf(-10.0, 370.0, 10.0))
        list.valuesIn(0.0, 720.0).map { it.value }.toList() shouldBe listOf(10.0, 350.0, 370.0, 710.0)
        list.valuesIn(0.0, 720.0, descending = true).map { it.value }.toList() shouldBe listOf(710.0, 370.0, 350.0, 10.0)
    }

    @org.junit.jupiter.api.Test
    fun `retrograde crossings within one sample come back in time order`() {
        val search = MotionSearch(looping, stationStep = 0.5, canStation = true, crossingStep = 5.0)
        val inLoop = (1..9).map { looping.at(9.0 + it * 0.2).longitude }
        val hits = search.crossings(0.0, 20.0, LongitudeTargets.Explicit(inLoop)).filter { it.retrograde }
        hits.map { it.jd } shouldBe hits.map { it.jd }.sorted()
        hits shouldHaveSize 9
    }

    @org.junit.jupiter.api.Test
    fun `a forward-only source may move more than half a turn between samples`() {
        // One turn a day at an uneven speed (46 to 674 degrees a day), like an ascendant near the polar circle.
        fun angle(jd: Double) = MotionSearch.normalize(360 * jd + 50 * sin(2 * PI * jd))
        val uneven = MotionSource { jd -> Motion(angle(jd), Double.NaN) }
        val coarse =
            MotionSearch(uneven, stationStep = 0.6, canStation = false, crossingStep = 0.6, forwardOnly = true)
                .crossings(0.0, 3.0, LongitudeTargets.Grid(12))
        coarse shouldHaveSize 36
        coarse.none { it.retrograde } shouldBe true
        coarse.forEach { MotionSearch.signedDelta(angle(it.jd) - it.longitude) shouldBe (0.0 plusOrMinus 1e-3) }
        val fine = MotionSearch(uneven, stationStep = 0.01, canStation = false, crossingStep = 0.01).crossings(0.0, 3.0, LongitudeTargets.Grid(12))
        coarse.map { it.longitude } shouldBe fine.map { it.longitude }
        coarse.zip(fine).forEach { (c, f) -> c.jd shouldBe (f.jd plusOrMinus 1e-5) }
        shouldThrow<IllegalArgumentException> { MotionSearch(uneven, stationStep = 1.0, canStation = true, forwardOnly = true) }
    }

    @org.junit.jupiter.api.Test
    fun `a search stopped at its limit never samples the whole range`() {
        // Sampling 1e9 days up front would take gigabytes; lazily, the search stops after a few samples.
        var calls = 0
        val counting = MotionSource { jd -> Motion(linear.at(jd).longitude.also { calls++ }, Double.NaN) }
        shouldThrow<MotionSearch.TooManyResultsException> {
            MotionSearch(counting, stationStep = 1.0, canStation = false, forwardOnly = true).crossings(0.0, 1e9, LongitudeTargets.Grid(12), limit = 10)
        }
        (calls < 10_000) shouldBe true
    }

    @org.junit.jupiter.api.Test
    fun `a sample on the target, up to round-off, does not push the crossing to the end of its interval`() {
        // 1 degree a day from 0; the sample at day 10 is 10.0, the target one ulp above it. Its difference used to fold
        // to 0, read as "past the target", and the bisection then ran to the next sample, day 11.
        val rising = MotionSource { jd -> Motion(MotionSearch.normalize(jd), 1.0) }
        val target = Math.nextUp(10.0)
        val hits = MotionSearch(rising, stationStep = 1.0, canStation = false).crossings(0.0, 20.0, LongitudeTargets.Explicit(listOf(target)))
        hits shouldHaveSize 1
        hits[0].jd shouldBe (10.0 plusOrMinus 1e-5)
        hits[0].longitude shouldBe target // list targets come back exactly as given
        // The same while moving backwards: one ulp below the sample.
        val falling = MotionSource { jd -> Motion(MotionSearch.normalize(-jd), -1.0) }
        val below = Math.nextDown(350.0)
        val back = MotionSearch(falling, stationStep = 1.0, canStation = false).crossings(0.0, 20.0, LongitudeTargets.Explicit(listOf(below)))
        back shouldHaveSize 1
        back[0].jd shouldBe (10.0 plusOrMinus 1e-5)
        back[0].retrograde shouldBe true
    }

    @org.junit.jupiter.api.Test
    fun `signed delta folds angles`() {
        MotionSearch.signedDelta(350.0) shouldBe -10.0
        MotionSearch.signedDelta(-350.0) shouldBe 10.0
        MotionSearch.signedDelta(-1e-15) shouldBe -1e-15
        MotionSearch.signedDelta(180.0) shouldBe -180.0
        MotionSearch.normalize(-30.0) shouldBe 330.0
        MotionSearch.normalize(42.354824666591) shouldBe 42.354824666591
        MotionSearch.normalize(-1e-20) shouldBe 0.0
        MotionSearch.normalize(-0.0).equals(0.0) shouldBe true
        MotionSearch.normalize(720.5) shouldBe 0.5
    }
}
