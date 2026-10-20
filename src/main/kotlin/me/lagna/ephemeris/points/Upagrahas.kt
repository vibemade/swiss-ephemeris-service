// SPDX-License-Identifier: AGPL-3.0-or-later
package me.lagna.ephemeris.points

import me.lagna.ephemeris.swe.SweConstants
import kotlin.math.floor

/** The arithmetic of the upagrahas (BPHS 3.61-70); the instants and angles come from libswe. */
object Upagrahas {
    /** Lords of the weekdays from Sunday, in weekday order. */
    val WEEKDAY_LORDS =
        listOf(
            SweConstants.SE_SUN,
            SweConstants.SE_MOON,
            SweConstants.SE_MARS,
            SweConstants.SE_MERCURY,
            SweConstants.SE_JUPITER,
            SweConstants.SE_VENUS,
            SweConstants.SE_SATURN,
        )

    /** Lords whose portions give the Kala group, in its order: Kala, Mrityu, Ardhaprahara, Yamaghantaka, Gulika. */
    val KALA_LORDS =
        listOf(SweConstants.SE_SUN, SweConstants.SE_MARS, SweConstants.SE_MERCURY, SweConstants.SE_JUPITER, SweConstants.SE_SATURN)

    const val PORTIONS = 8

    /** Day of the week, 0 = Sunday, of the local mean date at [longitude] (degrees east) of an instant in UT. */
    fun weekday(
        jdUt: Double,
        longitude: Double,
    ): Int {
        // A Julian day number's weekday is (JDN + 1) mod 7 counted from Sunday; local mean time is UT + longitude / 15 h.
        val dayNumber = floor(jdUt + longitude / FULL_CIRCLE + HALF_DAY).toLong()
        return Math.floorMod(dayNumber + 1, DAYS_IN_WEEK)
    }

    /**
     * Lords of the eight portions of a day or night: day portions start from the weekday lord, night portions from
     * the fifth lord counted from it (Sunday night from Jupiter); both go on in weekday order, and the eighth portion
     * (null) has no lord.
     */
    fun portionLords(
        weekday: Int,
        night: Boolean,
    ): List<Int?> {
        val first = weekday + if (night) FIFTH_FROM else 0
        return (0 until DAYS_IN_WEEK).map { WEEKDAY_LORDS[(first + it) % DAYS_IN_WEEK] } + null
    }

    /**
     * Dhuma, Vyatipata, Parivesha, Indrachapa and Upaketu from the Sun's longitude: Dhuma = Sun + 133 deg 20',
     * Vyatipata = 360 - Dhuma, Parivesha = Vyatipata + 180, Indrachapa = 360 - Parivesha, Upaketu = Indrachapa + 16 deg 40'.
     */
    fun dhumaGroup(sun: Double): List<Double> {
        val dhuma = MeanElements.normalize(sun + DHUMA_OFFSET)
        val vyatipata = MeanElements.normalize(FULL_CIRCLE - dhuma)
        val parivesha = MeanElements.normalize(vyatipata + FULL_CIRCLE / 2)
        val indrachapa = MeanElements.normalize(FULL_CIRCLE - parivesha)
        val upaketu = MeanElements.normalize(indrachapa + UPAKETU_OFFSET)
        return listOf(dhuma, vyatipata, parivesha, indrachapa, upaketu)
    }

    private const val DAYS_IN_WEEK = 7
    private const val FIFTH_FROM = 4
    private const val FULL_CIRCLE = 360.0
    private const val HALF_DAY = 0.5
    private const val DHUMA_OFFSET = 133.0 + 20.0 / 60.0
    private const val UPAKETU_OFFSET = 16.0 + 40.0 / 60.0
}
