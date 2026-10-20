// SPDX-License-Identifier: AGPL-3.0-or-later
package me.lagna.ephemeris.swe

import me.lagna.ephemeris.swe.SweConstants.SEFLG_EQUATORIAL
import me.lagna.ephemeris.swe.SweConstants.SEFLG_SIDEREAL
import me.lagna.ephemeris.swe.SweConstants.SEFLG_SPEED
import me.lagna.ephemeris.swe.SweConstants.SEFLG_SWIEPH
import me.lagna.ephemeris.swe.SweConstants.SEFLG_TOPOCTR
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_DOUBLE

/** Tropical when [siderealMode] is null, otherwise sidereal with that `SE_SIDM_*` mode. */
data class Frame(
    val siderealMode: Int? = null,
) {
    companion object {
        val TROPICAL = Frame()
    }
}

data class Place(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double = 0.0,
)

data class Position(
    val longitude: Double,
    val latitude: Double,
    val distance: Double,
    val longitudeSpeed: Double,
    val latitudeSpeed: Double,
    val distanceSpeed: Double,
)

/** Right ascension and declination in degrees, referred to the true equator and equinox of date. */
data class Equatorial(
    val rightAscension: Double,
    val declination: Double,
)

/** Obliquity of the ecliptic and nutation, in degrees. */
data class Nutation(
    val trueObliquity: Double,
    val meanObliquity: Double,
    val longitude: Double,
    val obliquity: Double,
)

class HouseCusps(
    val ascendant: Double,
    val midheaven: Double,
    val armc: Double,
    val vertex: Double,
    /** 12 cusps, house 1 first; null when the system is undefined at this latitude. */
    val cusps: List<Double>?,
)

/**
 * Direct libswe calls for one task on one [SweThreads] worker. Not thread-safe: obtain it through
 * [SwissEphemeris.session] and do not let it escape the block. Every call sets the modes it depends on
 * (sidereal mode, topocentric observer), so one call's settings never leak into another.
 */
class SweSession internal constructor(
    private val native: SweNative,
    arena: Arena,
) {
    private val xx = arena.allocate(JAVA_DOUBLE, XX_SIZE)
    private val serr = arena.allocate(SweConstants.ERROR_BUFFER.toLong())
    private val cusps = arena.allocate(JAVA_DOUBLE, SweConstants.CUSPS.toLong())
    private val ascmc = arena.allocate(JAVA_DOUBLE, SweConstants.ASCMC.toLong())
    private val geopos = arena.allocate(JAVA_DOUBLE, GEOPOS_SIZE)
    private val tret = arena.allocate(JAVA_DOUBLE, TRET_SIZE)
    private val attr = arena.allocate(JAVA_DOUBLE, ATTR_SIZE)
    private val wherePos = arena.allocate(JAVA_DOUBLE, ATTR_SIZE)
    private val scalarOut = arena.allocate(JAVA_DOUBLE)
    private val xaz = arena.allocate(JAVA_DOUBLE, XAZ_SIZE)

    /** [body] is a libswe body or one of the descending nodes ([SweConstants.SOUTH_MEAN_NODE], ...). */
    fun position(
        jdUt: Double,
        body: Int,
        frame: Frame = Frame.TROPICAL,
        observer: Place? = null,
    ): Position {
        if (SweConstants.isSouthNode(body)) {
            val node = position(jdUt, SweConstants.northOf(body), frame, observer)
            return node.copy(longitude = opposite(node.longitude), latitude = -node.latitude, latitudeSpeed = -node.latitudeSpeed)
        }
        var flags = SEFLG_SWIEPH or SEFLG_SPEED or frameFlags(frame)
        if (observer != null) {
            native.setTopo(observer.longitude, observer.latitude, observer.altitude)
            flags = flags or SEFLG_TOPOCTR
        }
        val returned = native.calcUt(jdUt, body, flags, xx, serr)
        requireFiles(returned, "swe_calc_ut(body $body)")
        return Position(xx.at(0), xx.at(1), xx.at(2), xx.at(3), xx.at(4), xx.at(5))
    }

    /**
     * Longitude alone, without `SEFLG_SPEED`: about 40% cheaper and identical to [position]'s longitude to
     * within 1e-13 degrees. Meant for searches that evaluate many instants and need no speed.
     */
    fun longitude(
        jdUt: Double,
        body: Int,
        frame: Frame = Frame.TROPICAL,
    ): Double {
        if (SweConstants.isSouthNode(body)) return opposite(longitude(jdUt, SweConstants.northOf(body), frame))
        val returned = native.calcUt(jdUt, body, SEFLG_SWIEPH or frameFlags(frame), xx, serr)
        requireFiles(returned, "swe_calc_ut(body $body)")
        return xx.at(0)
    }

    /** Right ascension and declination, independent of any sidereal mode; topocentric when [observer] is set. */
    fun equatorial(
        jdUt: Double,
        body: Int,
        observer: Place? = null,
    ): Equatorial {
        if (SweConstants.isSouthNode(body)) {
            val node = equatorial(jdUt, SweConstants.northOf(body), observer)
            return Equatorial(opposite(node.rightAscension), -node.declination)
        }
        var flags = SEFLG_SWIEPH or SEFLG_EQUATORIAL
        if (observer != null) {
            native.setTopo(observer.longitude, observer.latitude, observer.altitude)
            flags = flags or SEFLG_TOPOCTR
        }
        val returned = native.calcUt(jdUt, body, flags, xx, serr)
        requireFiles(returned, "swe_calc_ut(body $body, equatorial)")
        return Equatorial(xx.at(0), xx.at(1))
    }

    fun nutation(jdUt: Double): Nutation {
        val returned = native.calcUt(jdUt, SweConstants.SE_ECL_NUT, SEFLG_SWIEPH, xx, serr)
        if (returned < 0) throw EphemerisException("swe_calc_ut(SE_ECL_NUT) failed: ${serr.text()}")
        return Nutation(trueObliquity = xx.at(0), meanObliquity = xx.at(1), longitude = xx.at(2), obliquity = xx.at(3))
    }

    /**
     * Ascendant ([ascendant] true) or midheaven alone, without house cusps. Beyond the polar circles libswe keeps
     * the ascendant on the eastern side of the meridian, so it jumps there; callers decide whether that is usable.
     */
    fun angle(
        jdUt: Double,
        place: Place,
        ascendant: Boolean,
        frame: Frame = Frame.TROPICAL,
    ): Double {
        native.housesEx2(jdUt, frameFlags(frame), place.latitude, place.longitude, EQUAL_HOUSES.code, cusps, ascmc, serr)
        return ascmc.at(if (ascendant) SweConstants.ASCMC_ASC else SweConstants.ASCMC_MC)
    }

    /** Equation of time at [jdUt]: local apparent minus local mean time, in days. */
    fun equationOfTime(jdUt: Double): Double {
        val returned = native.timeEqu(jdUt, scalarOut, serr)
        if (returned < 0) throw EphemerisException("swe_time_equ failed: ${serr.text()}")
        return scalarOut.at(0)
    }

    /** Delta T (TT - UT) at [jdUt], in days, consistent with the Swiss Ephemeris files. */
    fun deltaT(jdUt: Double): Double = native.deltatEx(jdUt, SEFLG_SWIEPH, serr)

    /** True (unrefracted) altitude of the Sun's centre above the horizon of [place], in degrees. */
    fun sunAltitude(
        jdUt: Double,
        place: Place,
    ): Double {
        val sun = position(jdUt, SweConstants.SE_SUN)
        xx.setAtIndex(JAVA_DOUBLE, 0L, sun.longitude)
        xx.setAtIndex(JAVA_DOUBLE, 1L, sun.latitude)
        xx.setAtIndex(JAVA_DOUBLE, 2L, sun.distance)
        setGeopos(place)
        native.azalt(jdUt, SweConstants.SE_ECL2HOR, geopos, xx, xaz)
        return xaz.at(1)
    }

    /** Ayanamsa including nutation (as sidereal positions use it), or the mean one when [withNutation] is false. */
    fun ayanamsa(
        jdUt: Double,
        siderealMode: Int,
        withNutation: Boolean = true,
    ): Double {
        native.setSidMode(siderealMode)
        val flags = if (withNutation) SEFLG_SWIEPH else SEFLG_SWIEPH or SweConstants.SEFLG_NONUT
        val returned = native.ayanamsaExUt(jdUt, flags, scalarOut, serr)
        requireFiles(returned, "swe_get_ayanamsa_ex_ut(mode $siderealMode)")
        return scalarOut.at(0)
    }

    fun houses(
        jdUt: Double,
        place: Place,
        system: Char,
        frame: Frame = Frame.TROPICAL,
    ): HouseCusps {
        val returned =
            native.housesEx2(jdUt, frameFlags(frame), place.latitude, place.longitude, system.code, cusps, ascmc, serr)
        // A negative result means the system is undefined here (e.g. Placidus beyond the polar circles);
        // libswe then fills Porphyry cusps, which this service does not pass off as the requested system.
        return HouseCusps(
            ascendant = ascmc.at(SweConstants.ASCMC_ASC),
            midheaven = ascmc.at(SweConstants.ASCMC_MC),
            armc = ascmc.at(SweConstants.ASCMC_ARMC),
            vertex = ascmc.at(SweConstants.ASCMC_VERTEX),
            cusps = if (returned < 0) null else (1..HOUSES).map { cusps.at(it) },
        )
    }

    /** First rise, set or transit after [jdUt] as a Julian day, or null when it does not happen (polar day/night). */
    fun riseTrans(
        jdUt: Double,
        body: Int,
        place: Place,
        mode: Int,
    ): Double? {
        setGeopos(place)
        return when (val returned = native.riseTrans(jdUt, body, SEFLG_SWIEPH, mode, geopos, tret, serr)) {
            0 -> tret.at(0)
            SweConstants.CIRCUMPOLAR -> null
            else -> throw EphemerisException("swe_rise_trans(body $body) failed ($returned): ${serr.text()}")
        }
    }

    /**
     * First solar eclipse whose maximum is after [jdUt]: anywhere on Earth when [place] is null, otherwise the first
     * one visible there. Local contacts are limited to the time the Sun is above the horizon: libswe reports the
     * sunrise or sunset when it happens between the first and the last contact, and moves the maximum to it when
     * the maximum itself is below the horizon.
     */
    fun nextSolarEclipse(
        jdUt: Double,
        place: Place? = null,
    ): EclipseEvent = if (place == null) nextSolarEclipseGlobal(jdUt) else nextSolarEclipseAt(jdUt, place)

    /**
     * First lunar eclipse whose maximum is after [jdUt]: global when [place] is null, otherwise the first one visible
     * there, with its contacts limited to the time the Moon is above the horizon (libswe drops the contacts before
     * moonrise or after moonset and reports the moonrise or moonset instead).
     */
    fun nextLunarEclipse(
        jdUt: Double,
        place: Place? = null,
    ): EclipseEvent = if (place == null) nextLunarEclipseGlobal(jdUt) else nextLunarEclipseAt(jdUt, place)

    private fun nextSolarEclipseGlobal(jdUt: Double): EclipseEvent {
        tret.clear()
        val flags = native.solEclipseWhenGlob(jdUt, SEFLG_SWIEPH, tret, serr)
        requireEclipse(flags, "swe_sol_eclipse_when_glob")
        val maximum = tret.at(0)
        attr.clear()
        // 0 means libswe finds no place of greatest eclipse: a grazing partial eclipse (over 1800-2400 only the one
        // of 1935-01-05, magnitude 0.0013), whose magnitude is reported as 0.
        val where = native.solEclipseWhere(maximum, SEFLG_SWIEPH, wherePos, attr, serr)
        if (where < 0) throw EphemerisException("swe_sol_eclipse_where failed ($where): ${serr.text()}")
        val type = solarType(flags)
        val central = type != EclipseType.PARTIAL
        return EclipseEvent(
            type = type,
            maximum = maximum,
            magnitude = if (where == 0) 0.0 else attr.at(ATTR_NASA_MAGNITUDE),
            begin = tret.instant(2),
            end = tret.instant(3),
            totalityBegin = if (central) tret.instant(4) else null,
            totalityEnd = if (central) tret.instant(5) else null,
        )
    }

    private fun nextSolarEclipseAt(
        jdUt: Double,
        place: Place,
    ): EclipseEvent {
        setGeopos(place)
        tret.clear()
        attr.clear()
        val flags = native.solEclipseWhenLoc(jdUt, SEFLG_SWIEPH, geopos, tret, attr, serr)
        requireEclipse(flags, "swe_sol_eclipse_when_loc")
        val visible = Window(rise = tret.instant(5), set = tret.instant(6), maximum = tret.at(0))
        val type = solarType(flags)
        val totality =
            if (type == EclipseType.PARTIAL) null else visible.clip(tret.instant(2), tret.instant(3))
        return EclipseEvent(
            type = type,
            maximum = tret.at(0),
            magnitude = attr.at(ATTR_NASA_MAGNITUDE),
            begin = visible.clipBegin(tret.at(1)),
            end = visible.clipEnd(tret.at(4)),
            totalityBegin = totality?.first,
            totalityEnd = totality?.second,
        )
    }

    private fun nextLunarEclipseGlobal(jdUt: Double): EclipseEvent {
        tret.clear()
        val flags = native.lunEclipseWhen(jdUt, SEFLG_SWIEPH, tret, serr)
        requireEclipse(flags, "swe_lun_eclipse_when")
        val maximum = tret.at(0)
        attr.clear()
        requireEclipse(native.lunEclipseHow(maximum, SEFLG_SWIEPH, MemorySegment.NULL, attr, serr), "swe_lun_eclipse_how")
        return lunarEvent(lunarType(flags), maximum)
    }

    private fun nextLunarEclipseAt(
        jdUt: Double,
        place: Place,
    ): EclipseEvent {
        setGeopos(place)
        tret.clear()
        attr.clear()
        val flags = native.lunEclipseWhenLoc(jdUt, SEFLG_SWIEPH, geopos, tret, attr, serr)
        requireEclipse(flags, "swe_lun_eclipse_when_loc")
        val maximum = tret.at(0)
        val visible = Window(rise = tret.instant(8), set = tret.instant(9), maximum = maximum)
        // libswe zeroes the local contacts the Moon is below the horizon for; the same eclipse found globally (its
        // maximum is within hours of the local one) gives all of them, which are then limited like the solar ones.
        tret.clear()
        requireEclipse(native.lunEclipseWhen(maximum - ECLIPSE_MARGIN_DAYS, SEFLG_SWIEPH, tret, serr), "swe_lun_eclipse_when")
        return lunarEvent(lunarType(flags), maximum, visible)
    }

    /** Contacts from `tret` as `swe_lun_eclipse_when` fills it, limited to [visible]; magnitude from `attr`. */
    private fun lunarEvent(
        type: EclipseType,
        maximum: Double,
        visible: Window = Window(rise = null, set = null, maximum = maximum),
    ): EclipseEvent {
        val penumbral = type == EclipseType.PENUMBRAL
        val contacts = visible.clip(tret.instant(if (penumbral) 6 else 2), tret.instant(if (penumbral) 7 else 3))
        val totality = if (type == EclipseType.TOTAL) visible.clip(tret.instant(4), tret.instant(5)) else null
        return EclipseEvent(
            type = type,
            maximum = maximum,
            magnitude = attr.at(if (penumbral) ATTR_PENUMBRAL_MAGNITUDE else ATTR_UMBRAL_MAGNITUDE),
            begin = contacts?.first,
            end = contacts?.second,
            totalityBegin = totality?.first,
            totalityEnd = totality?.second,
        )
    }

    /**
     * When the eclipsed body is above the horizon; libswe reports a rise or set only when it falls within the eclipse.
     * Near the polar circles the body can set and rise again during one eclipse; then the stretch with the maximum
     * (always above the horizon) is kept.
     */
    private class Window(
        rise: Double?,
        set: Double?,
        maximum: Double,
    ) {
        private val upTwice = rise != null && set != null && set < rise
        val rise: Double? = if (upTwice && maximum <= checkNotNull(set)) null else rise
        val set: Double? = if (upTwice && maximum > checkNotNull(set)) null else set

        fun clipBegin(jd: Double): Double = rise?.let { maxOf(it, jd) } ?: jd

        fun clipEnd(jd: Double): Double = set?.let { minOf(it, jd) } ?: jd

        /** The part of [begin, end] above the horizon, or null when there is none. */
        fun clip(
            begin: Double?,
            end: Double?,
        ): Pair<Double, Double>? {
            if (begin == null || end == null) return null
            val from = clipBegin(begin)
            val to = clipEnd(end)
            return if (from < to) from to to else null
        }
    }

    private fun setGeopos(place: Place) {
        geopos.setAtIndex(JAVA_DOUBLE, 0L, place.longitude)
        geopos.setAtIndex(JAVA_DOUBLE, 1L, place.latitude)
        geopos.setAtIndex(JAVA_DOUBLE, 2L, place.altitude)
    }

    private fun requireEclipse(
        returned: Int,
        call: String,
    ) {
        if (returned <= 0) throw EphemerisException("$call failed ($returned): ${serr.text()}")
    }

    /** Sets the sidereal mode right before the calculation that needs it. */
    private fun frameFlags(frame: Frame): Int =
        when (val mode = frame.siderealMode) {
            null -> 0
            else -> SEFLG_SIDEREAL.also { native.setSidMode(mode) }
        }

    private fun requireFiles(
        returnedFlags: Int,
        call: String,
    ) {
        if (returnedFlags < 0) throw EphemerisException("$call failed: ${serr.text()}")
        if (returnedFlags and SEFLG_SWIEPH == 0) {
            throw EphemerisException("$call fell back from the Swiss Ephemeris files: ${serr.text()}")
        }
    }

    private companion object {
        const val XX_SIZE = 6L
        const val GEOPOS_SIZE = 3L
        const val TRET_SIZE = 10L
        const val HOUSES = 12

        /** libswe's eclipse functions want `attr[20]` (and `swe_sol_eclipse_where` up to 10 `geopos` values). */
        const val ATTR_SIZE = 20L
        const val XAZ_SIZE = 3L
        const val ATTR_UMBRAL_MAGNITUDE = 0
        const val ATTR_PENUMBRAL_MAGNITUDE = 1
        const val ATTR_NASA_MAGNITUDE = 8

        /** The local maximum of a lunar eclipse is within a few hours of the global one. */
        const val ECLIPSE_MARGIN_DAYS = 0.5

        /** Any system gives the same angles; equal houses are the cheapest and defined at every latitude. */
        const val EQUAL_HOUSES = 'E'

        fun solarType(flags: Int): EclipseType =
            when {
                flags and SweConstants.SE_ECL_ANNULAR_TOTAL != 0 -> EclipseType.HYBRID
                flags and SweConstants.SE_ECL_TOTAL != 0 -> EclipseType.TOTAL
                flags and SweConstants.SE_ECL_ANNULAR != 0 -> EclipseType.ANNULAR
                flags and SweConstants.SE_ECL_PARTIAL != 0 -> EclipseType.PARTIAL
                else -> throw EphemerisException("unknown solar eclipse type $flags")
            }

        fun lunarType(flags: Int): EclipseType =
            when {
                flags and SweConstants.SE_ECL_TOTAL != 0 -> EclipseType.TOTAL
                flags and SweConstants.SE_ECL_PARTIAL != 0 -> EclipseType.PARTIAL
                flags and SweConstants.SE_ECL_PENUMBRAL != 0 -> EclipseType.PENUMBRAL
                else -> throw EphemerisException("unknown lunar eclipse type $flags")
            }

        fun MemorySegment.at(index: Int): Double = getAtIndex(JAVA_DOUBLE, index.toLong())

        /** The opposite direction, in [0, 360). */
        fun opposite(degrees: Double): Double = ((degrees + HALF_CIRCLE) % FULL_CIRCLE + FULL_CIRCLE) % FULL_CIRCLE

        const val HALF_CIRCLE = 180.0
        const val FULL_CIRCLE = 360.0

        /** A time from libswe's `tret`, where 0 means "does not happen". */
        fun MemorySegment.instant(index: Int): Double? = at(index).takeIf { it != 0.0 }

        fun MemorySegment.clear() {
            fill(0)
        }

        fun MemorySegment.text(): String = getString(0).trim()
    }
}
