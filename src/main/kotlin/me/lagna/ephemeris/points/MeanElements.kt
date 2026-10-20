// SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber") // published coefficients

package me.lagna.ephemeris.points

/**
 * Mean longitudes referred to the mean equinox and ecliptic of date, in degrees [0, 360), as functions of T, Julian
 * centuries of TT from J2000.0.
 *
 * Planets (heliocentric) and the Earth-Moon barycentre: Simon et al. 1994, "Numerical expressions for precession
 * formulae and mean elements for the Moon and the planets", A&A 282, 663, to the T^2 term. The Moon: the mean
 * longitude of ELP-2000/82 (Chapront-Touze and Chapront), as in Meeus, Astronomical Algorithms, eq. 47.1.
 */
object MeanElements {
    enum class Planet(
        internal val l0: Double,
        internal val l1: Double,
        internal val l2: Double,
    ) {
        MERCURY(252.25090552, 538_106_659.820037, 1.0925943),
        VENUS(181.97980085, 210_669_166.631989, 1.1165021),
        EARTH_MOON(100.46645683, 129_602_771.103429, 1.0915809),
        MARS(355.43299958, 68_910_106.933069, 1.1178674),
        JUPITER(34.35151874, 10_930_689.989453, 0.8038700),
        SATURN(50.0774443, 4_404_639.847038, 1.8686817),
    }

    /** Heliocentric mean longitude of [planet]; the rates are in arcseconds per century. */
    fun heliocentric(
        planet: Planet,
        t: Double,
    ): Double = normalize(planet.l0 + (planet.l1 * t + planet.l2 * t * t) / ARCSECONDS)

    /** Geocentric mean longitude of the Sun: the Earth-Moon barycentre's, plus 180 degrees. */
    fun sun(t: Double): Double = normalize(heliocentric(Planet.EARTH_MOON, t) + HALF_CIRCLE)

    fun moon(t: Double): Double =
        normalize(218.3164477 + 481_267.88123421 * t - 0.0015786 * t * t + t * t * t / 538_841.0 - t * t * t * t / 65_194_000.0)

    /** Julian centuries of TT since J2000.0 for a Julian day in TT. */
    fun centuries(jdTt: Double): Double = (jdTt - J2000) / DAYS_PER_CENTURY

    fun normalize(degrees: Double): Double = (degrees % FULL_CIRCLE + FULL_CIRCLE) % FULL_CIRCLE

    private const val J2000 = 2451545.0
    private const val DAYS_PER_CENTURY = 36525.0
    private const val ARCSECONDS = 3600.0
    private const val HALF_CIRCLE = 180.0
    private const val FULL_CIRCLE = 360.0
}
