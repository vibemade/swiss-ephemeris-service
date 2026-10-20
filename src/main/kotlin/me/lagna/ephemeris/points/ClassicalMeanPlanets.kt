// SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber") // published constants

package me.lagna.ephemeris.points

/**
 * Classical (ahargana-based) mean planets of B. V. Raman, "Graha and Bhava Balas", chapter VI (Chesta Bala) and
 * tables I-IX, in the book's own sidereal frame. Each is `L0 + n * D + c(t)` modulo 360, with D the days since the
 * book's epoch, 0h on 1 January 1900 in Ujjain mean time (76 E, 5h 04m ahead of UT), and t = D / 365.25 the years
 * since then (the book counts whole years, which changes the result by at most 0.007 degrees).
 *
 * The constants reproduce the book's daily motions consistently across all columns of its tables, rather than
 * copying individual cells, some of which are misprinted; they reproduce the book's worked example (its Standard
 * Horoscope, 1918-10-16) to within 0.07 degrees. The book has no classical mean Moon.
 */
object ClassicalMeanPlanets {
    enum class Element(
        internal val epoch: Double,
        internal val dailyMotion: Double,
        internal val correction: (Double) -> Double,
    ) {
        /** Table IV. */
        SUN(257.4568, 0.985602655, { 0.0 }),

        /** Table V. */
        MARS(270.22, 0.5240195, { 0.0 }),

        /** Table VI. */
        JUPITER(220.04, 0.08309633, { t -> -(3.33 + 0.0067 * t) }),

        /** Table VII. */
        SATURN(236.74, 0.03343945, { t -> 5.0 + 0.001 * t }),

        /** Table VIII: the sighrocca of Mercury. */
        MERCURY_SIGHROCCA(164.00, 4.092318, { t -> 6.67 - 0.00133 * t }),

        /** Table IX: the sighrocca of Venus. */
        VENUS_SIGHROCCA(328.51, 1.6021464, { t -> -(5.0 + 0.001 * t) }),
    }

    /** 1899-12-31T18:56:00Z, which is 0h on 1 January 1900 in Ujjain mean time, as a Julian day (UT). */
    const val EPOCH_JD = 2415020.5 - (5.0 + 4.0 / 60.0) / 24.0

    /** Longitude of [element] at [jdUt], degrees [0, 360) in the book's frame. */
    fun longitude(
        element: Element,
        jdUt: Double,
    ): Double {
        val days = jdUt - EPOCH_JD
        return MeanElements.normalize(element.epoch + element.dailyMotion * days + element.correction(days / DAYS_PER_YEAR))
    }

    private const val DAYS_PER_YEAR = 365.25
}
