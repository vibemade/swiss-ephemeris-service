// SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber") // values copied verbatim from swephexp.h

package me.lagna.ephemeris.swe

/** Values from `swephexp.h` (Swiss Ephemeris 2.10.03). */
object SweConstants {
    const val SE_SUN = 0
    const val SE_MOON = 1
    const val SE_MERCURY = 2
    const val SE_VENUS = 3
    const val SE_MARS = 4
    const val SE_JUPITER = 5
    const val SE_SATURN = 6
    const val SE_URANUS = 7
    const val SE_NEPTUNE = 8
    const val SE_PLUTO = 9
    const val SE_MEAN_NODE = 10
    const val SE_TRUE_NODE = 11
    const val SE_MEAN_APOG = 12
    const val SE_CHIRON = 15

    /** Pseudo-body of `swe_calc_ut`: true and mean obliquity, nutation in longitude and in obliquity. */
    const val SE_ECL_NUT = -1

    /**
     * Not libswe bodies: the descending nodes (Ketu), which [SweSession] derives from [SE_MEAN_NODE] and
     * [SE_TRUE_NODE]. Far from every libswe id, so they can never reach libswe by mistake.
     */
    const val SOUTH_MEAN_NODE = -1_000 - SE_MEAN_NODE
    const val SOUTH_TRUE_NODE = -1_000 - SE_TRUE_NODE

    fun isSouthNode(body: Int): Boolean = body == SOUTH_MEAN_NODE || body == SOUTH_TRUE_NODE

    /** The ascending node for a descending one, any other body unchanged. */
    fun northOf(body: Int): Int =
        when (body) {
            SOUTH_MEAN_NODE -> SE_MEAN_NODE
            SOUTH_TRUE_NODE -> SE_TRUE_NODE
            else -> body
        }

    const val SE_SIDM_FAGAN_BRADLEY = 0
    const val SE_SIDM_LAHIRI = 1
    const val SE_SIDM_RAMAN = 3
    const val SE_SIDM_USHASHASHI = 4
    const val SE_SIDM_KRISHNAMURTI = 5
    const val SE_SIDM_YUKTESHWAR = 7
    const val SE_SIDM_SURYASIDDHANTA = 21
    const val SE_SIDM_SURYASIDDHANTA_MSUN = 22
    const val SE_SIDM_ARYABHATA = 23
    const val SE_SIDM_ARYABHATA_MSUN = 24
    const val SE_SIDM_SS_REVATI = 25
    const val SE_SIDM_SS_CITRA = 26
    const val SE_SIDM_TRUE_CITRA = 27
    const val SE_SIDM_TRUE_REVATI = 28
    const val SE_SIDM_TRUE_PUSHYA = 29
    const val SE_SIDM_TRUE_MULA = 35
    const val SE_SIDM_ARYABHATA_522 = 37
    const val SE_SIDM_LAHIRI_1940 = 43
    const val SE_SIDM_LAHIRI_VP285 = 44
    const val SE_SIDM_KRISHNAMURTI_VP291 = 45
    const val SE_SIDM_LAHIRI_ICRC = 46

    const val SEFLG_SWIEPH = 2
    const val SEFLG_NONUT = 64
    const val SEFLG_SPEED = 256
    const val SEFLG_EQUATORIAL = 2 * 1024
    const val SEFLG_TOPOCTR = 32 * 1024
    const val SEFLG_SIDEREAL = 64 * 1024

    const val SE_ECL_TOTAL = 4
    const val SE_ECL_ANNULAR = 8
    const val SE_ECL_PARTIAL = 16
    const val SE_ECL_ANNULAR_TOTAL = 32
    const val SE_ECL_PENUMBRAL = 64

    /** Altitudes `swe_rise_trans` and `swe_*_eclipse_*` accept, in metres (`SEI_ECL_GEOALT_MIN` / `MAX` in `sweph.h`). */
    const val HORIZON_ALTITUDE_MIN = -500.0
    const val HORIZON_ALTITUDE_MAX = 25_000.0

    const val SE_CALC_RISE = 1
    const val SE_CALC_SET = 2
    const val SE_CALC_MTRANSIT = 4
    const val SE_CALC_ITRANSIT = 8
    const val SE_BIT_DISC_CENTER = 256
    const val SE_BIT_NO_REFRACTION = 512

    /** `swe_azalt` input in ecliptic coordinates. */
    const val SE_ECL2HOR = 0

    internal const val ERROR_BUFFER = 256
    internal const val CUSPS = 13
    internal const val ASCMC = 10
    internal const val ASCMC_ASC = 0
    internal const val ASCMC_MC = 1
    internal const val ASCMC_ARMC = 2
    internal const val ASCMC_VERTEX = 3

    /** `swe_rise_trans` result when the body does not cross the horizon (polar day or night). */
    internal const val CIRCUMPOLAR = -2
}
