// SPDX-License-Identifier: AGPL-3.0-or-later
package me.lagna.ephemeris.points

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import me.lagna.ephemeris.points.ClassicalMeanPlanets.Element
import me.lagna.ephemeris.swe.JulianDay
import me.lagna.ephemeris.timestamp
import org.junit.jupiter.api.Test

class ClassicalMeanPlanetsTest {
    @Test
    fun `the epoch is midnight of 1900-01-01 in Ujjain mean time`() {
        ClassicalMeanPlanets.EPOCH_JD shouldBe (JulianDay.of(timestamp("1899-12-31T18:56:00Z")) plusOrMinus 1e-9)
        ClassicalMeanPlanets.longitude(Element.SUN, ClassicalMeanPlanets.EPOCH_JD) shouldBe (257.4568 plusOrMinus 1e-9)
        ClassicalMeanPlanets.longitude(Element.JUPITER, ClassicalMeanPlanets.EPOCH_JD) shouldBe (220.04 - 3.33 plusOrMinus 1e-9)
    }

    @Test
    fun `each element advances by its daily motion, plus its slow correction`() {
        val start = JulianDay.of(timestamp("2000-01-01T00:00:00Z"))
        for ((element, perDay) in listOf(Element.SUN to 0.985602655, Element.MARS to 0.5240195, Element.MERCURY_SIGHROCCA to 4.092318)) {
            val moved = MeanElements.normalize(ClassicalMeanPlanets.longitude(element, start + 10) - ClassicalMeanPlanets.longitude(element, start))
            // Mercury's correction drifts 0.00133 degrees a year, about 4e-5 over these 10 days.
            moved shouldBe (MeanElements.normalize(10 * perDay) plusOrMinus 1e-4)
        }
    }
}
