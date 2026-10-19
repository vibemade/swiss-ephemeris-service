// SPDX-License-Identifier: AGPL-3.0-or-later
package me.lagna.ephemeris.swe

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_DOUBLE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.invoke.MethodHandle
import java.nio.file.Path

/**
 * Hand-written FFM bindings for the libswe functions this service uses (signatures from `swephexp.h`).
 * Raw and unsafe on purpose: only [SweSession] calls these, always on a [SweThreads] worker.
 */
internal class SweNative(
    library: Path,
) {
    private val linker = Linker.nativeLinker()
    private val lookup = SymbolLookup.libraryLookup(library, Arena.global())

    private val calcUt = handle("swe_calc_ut", JAVA_INT, JAVA_DOUBLE, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS)
    private val setSidMode = voidHandle("swe_set_sid_mode", JAVA_INT, JAVA_DOUBLE, JAVA_DOUBLE)
    private val setTopo = voidHandle("swe_set_topo", JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE)
    private val ayanamsaExUt = handle("swe_get_ayanamsa_ex_ut", JAVA_INT, JAVA_DOUBLE, JAVA_INT, ADDRESS, ADDRESS)
    private val housesEx2 =
        handle(
            "swe_houses_ex2",
            JAVA_INT,
            JAVA_DOUBLE,
            JAVA_INT,
            JAVA_DOUBLE,
            JAVA_DOUBLE,
            JAVA_INT,
            ADDRESS,
            ADDRESS,
            ADDRESS,
            ADDRESS,
            ADDRESS,
        )
    private val riseTrans =
        handle(
            "swe_rise_trans",
            JAVA_INT,
            JAVA_DOUBLE,
            JAVA_INT,
            ADDRESS,
            JAVA_INT,
            JAVA_INT,
            ADDRESS,
            JAVA_DOUBLE,
            JAVA_DOUBLE,
            ADDRESS,
            ADDRESS,
        )
    private val solEclipseWhenGlob =
        handle("swe_sol_eclipse_when_glob", JAVA_INT, JAVA_DOUBLE, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS)
    private val solEclipseWhenLoc =
        handle("swe_sol_eclipse_when_loc", JAVA_INT, JAVA_DOUBLE, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS)
    private val solEclipseWhere = handle("swe_sol_eclipse_where", JAVA_INT, JAVA_DOUBLE, JAVA_INT, ADDRESS, ADDRESS, ADDRESS)
    private val lunEclipseWhen =
        handle("swe_lun_eclipse_when", JAVA_INT, JAVA_DOUBLE, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS)
    private val lunEclipseWhenLoc =
        handle("swe_lun_eclipse_when_loc", JAVA_INT, JAVA_DOUBLE, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS)
    private val lunEclipseHow = handle("swe_lun_eclipse_how", JAVA_INT, JAVA_DOUBLE, JAVA_INT, ADDRESS, ADDRESS, ADDRESS)
    private val timeEqu = handle("swe_time_equ", JAVA_INT, JAVA_DOUBLE, ADDRESS, ADDRESS)
    private val deltatEx = handle("swe_deltat_ex", JAVA_DOUBLE, JAVA_DOUBLE, JAVA_INT, ADDRESS)
    private val azalt =
        voidHandle("swe_azalt", JAVA_DOUBLE, JAVA_INT, ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE, ADDRESS, ADDRESS)
    private val setEphePath = voidHandle("swe_set_ephe_path", ADDRESS)
    private val version = handle("swe_version", ADDRESS, ADDRESS)

    fun calcUt(
        jdUt: Double,
        body: Int,
        flags: Int,
        xx: MemorySegment,
        serr: MemorySegment,
    ): Int = calcUt.invoke(jdUt, body, flags, xx, serr) as Int

    fun setSidMode(mode: Int) {
        setSidMode.invoke(mode, 0.0, 0.0)
    }

    fun setTopo(
        longitude: Double,
        latitude: Double,
        altitude: Double,
    ) {
        setTopo.invoke(longitude, latitude, altitude)
    }

    fun ayanamsaExUt(
        jdUt: Double,
        flags: Int,
        out: MemorySegment,
        serr: MemorySegment,
    ): Int = ayanamsaExUt.invoke(jdUt, flags, out, serr) as Int

    @Suppress("LongParameterList") // mirrors the C signature
    fun housesEx2(
        jdUt: Double,
        flags: Int,
        latitude: Double,
        longitude: Double,
        system: Int,
        cusps: MemorySegment,
        ascmc: MemorySegment,
        serr: MemorySegment,
    ): Int = housesEx2.invoke(jdUt, flags, latitude, longitude, system, cusps, ascmc, NULL, NULL, serr) as Int

    @Suppress("LongParameterList") // mirrors the C signature
    fun riseTrans(
        jdUt: Double,
        body: Int,
        flags: Int,
        mode: Int,
        geopos: MemorySegment,
        result: MemorySegment,
        serr: MemorySegment,
    ): Int = riseTrans.invoke(jdUt, body, NULL, flags, mode, geopos, 0.0, 0.0, result, serr) as Int

    /** Next solar eclipse anywhere on Earth after [jdUt], of any type. */
    fun solEclipseWhenGlob(
        jdUt: Double,
        flags: Int,
        tret: MemorySegment,
        serr: MemorySegment,
    ): Int = solEclipseWhenGlob.invoke(jdUt, flags, 0, tret, 0, serr) as Int

    /** Next solar eclipse visible at [geopos] after [jdUt]. */
    fun solEclipseWhenLoc(
        jdUt: Double,
        flags: Int,
        geopos: MemorySegment,
        tret: MemorySegment,
        attr: MemorySegment,
        serr: MemorySegment,
    ): Int = solEclipseWhenLoc.invoke(jdUt, flags, geopos, tret, attr, 0, serr) as Int

    /** Place and attributes of the greatest solar eclipse at [jdUt]; [geopos] receives up to 10 values. */
    fun solEclipseWhere(
        jdUt: Double,
        flags: Int,
        geopos: MemorySegment,
        attr: MemorySegment,
        serr: MemorySegment,
    ): Int = solEclipseWhere.invoke(jdUt, flags, geopos, attr, serr) as Int

    /** Next lunar eclipse after [jdUt], of any type. */
    fun lunEclipseWhen(
        jdUt: Double,
        flags: Int,
        tret: MemorySegment,
        serr: MemorySegment,
    ): Int = lunEclipseWhen.invoke(jdUt, flags, 0, tret, 0, serr) as Int

    /** Next lunar eclipse visible at [geopos] after [jdUt]. */
    fun lunEclipseWhenLoc(
        jdUt: Double,
        flags: Int,
        geopos: MemorySegment,
        tret: MemorySegment,
        attr: MemorySegment,
        serr: MemorySegment,
    ): Int = lunEclipseWhenLoc.invoke(jdUt, flags, geopos, tret, attr, 0, serr) as Int

    /** Attributes of a lunar eclipse at [jdUt]; [geopos] is only used for the Moon's altitude and may be NULL. */
    fun lunEclipseHow(
        jdUt: Double,
        flags: Int,
        geopos: MemorySegment,
        attr: MemorySegment,
        serr: MemorySegment,
    ): Int = lunEclipseHow.invoke(jdUt, flags, geopos, attr, serr) as Int

    /** Equation of time (local apparent minus local mean time) at [jdUt], in days, written to [out]. */
    fun timeEqu(
        jdUt: Double,
        out: MemorySegment,
        serr: MemorySegment,
    ): Int = timeEqu.invoke(jdUt, out, serr) as Int

    /** Delta T (TT - UT) in days. */
    fun deltatEx(
        jdUt: Double,
        flags: Int,
        serr: MemorySegment,
    ): Double = deltatEx.invoke(jdUt, flags, serr) as Double

    /** Azimuth, true and apparent altitude ([xaz]) of the point [xin] (ecliptic of date with [SweConstants.SE_ECL2HOR]). */
    @Suppress("LongParameterList") // mirrors the C signature
    fun azalt(
        jdUt: Double,
        mode: Int,
        geopos: MemorySegment,
        xin: MemorySegment,
        xaz: MemorySegment,
    ) {
        azalt.invoke(jdUt, mode, geopos, 0.0, 0.0, xin, xaz)
    }

    fun setEphePath(path: Path) {
        Arena.ofConfined().use { arena -> setEphePath.invoke(arena.allocateFrom(path.toString())) }
    }

    fun version(): String =
        Arena.ofConfined().use { arena ->
            val buffer = arena.allocate(SweConstants.ERROR_BUFFER.toLong())
            (version.invoke(buffer) as MemorySegment).reinterpret(SweConstants.ERROR_BUFFER.toLong()).getString(0)
        }

    private fun handle(
        name: String,
        result: MemoryLayout,
        vararg args: MemoryLayout,
    ): MethodHandle = linker.downcallHandle(symbol(name), FunctionDescriptor.of(result, *args))

    private fun voidHandle(
        name: String,
        vararg args: MemoryLayout,
    ): MethodHandle = linker.downcallHandle(symbol(name), FunctionDescriptor.ofVoid(*args))

    private fun symbol(name: String): MemorySegment =
        lookup.find(name).orElseThrow { IllegalStateException("libswe symbol not found: $name") }

    private companion object {
        val NULL: MemorySegment = MemorySegment.NULL
    }
}
