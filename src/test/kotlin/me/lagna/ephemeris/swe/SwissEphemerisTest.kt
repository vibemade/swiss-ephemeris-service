// SPDX-License-Identifier: AGPL-3.0-or-later
package me.lagna.ephemeris.swe

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import me.lagna.ephemeris.J2000
import me.lagna.ephemeris.TestEphemeris
import me.lagna.ephemeris.timestamp
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs

/** Expected values come from swetest built from the same pinned sources (`build/native/bin/swetest`). */
class SwissEphemerisTest {
    private val ephemeris = TestEphemeris.instance
    private val kyiv = Place(latitude = 50.45, longitude = 30.52)

    @Test
    fun `tropical positions match swetest`() {
        ephemeris.sessionBlocking { position(J2000, SweConstants.SE_SUN).longitude } shouldBe (280.3689187 plusOrMinus 1e-6)
        ephemeris.sessionBlocking { position(J2000, SweConstants.SE_MOON).longitude } shouldBe (223.3237514 plusOrMinus 1e-6)
        ephemeris.sessionBlocking { position(J2000, SweConstants.SE_CHIRON).longitude } shouldBe (251.6176264 plusOrMinus 1e-6)
    }

    @Test
    fun `sidereal and topocentric positions match swetest`() {
        val lahiri = Frame(SweConstants.SE_SIDM_LAHIRI)
        ephemeris.sessionBlocking { position(J2000, SweConstants.SE_MOON, lahiri).longitude } shouldBe
            (199.4705290 plusOrMinus 1e-6)
        ephemeris.sessionBlocking { position(J2000, SweConstants.SE_SUN, observer = kyiv).longitude } shouldBe
            (280.3680056 plusOrMinus 1e-6)
    }

    @Test
    fun `longitude without speed matches the full position`() {
        val lahiri = Frame(SweConstants.SE_SIDM_LAHIRI)
        val bodies = listOf(SweConstants.SE_SUN, SweConstants.SE_MOON, SweConstants.SE_MERCURY, SweConstants.SE_TRUE_NODE)
        ephemeris.sessionBlocking {
            for (body in bodies) {
                for (frame in listOf(Frame.TROPICAL, lahiri)) {
                    for (day in 0 until 3650 step 37) {
                        val jd = J2000 + day + 0.123
                        longitude(jd, body, frame) shouldBe (position(jd, body, frame).longitude plusOrMinus 1e-12)
                    }
                }
            }
        }
    }

    @Test
    fun `settings of one call never leak into the next`() {
        ephemeris.sessionBlocking {
            position(J2000, SweConstants.SE_MOON, Frame(SweConstants.SE_SIDM_LAHIRI), kyiv)
            position(J2000, SweConstants.SE_MOON).longitude
        } shouldBe (223.3237514 plusOrMinus 1e-6)
    }

    @Test
    fun `parallel sessions with different modes agree with sequential ones`() {
        val modes = listOf(SweConstants.SE_SIDM_LAHIRI, SweConstants.SE_SIDM_RAMAN, SweConstants.SE_SIDM_KRISHNAMURTI)
        val expected = modes.associateWith { mode -> ephemeris.sessionBlocking { ayanamsa(J2000, mode) } }
        runBlocking {
            // Fewer sessions than the worker queue holds (256), or a slow 2-CPU runner rejects some of them.
            val results =
                (1..240)
                    .map { i ->
                        val mode = modes[i % modes.size]
                        async { mode to ephemeris.session { ayanamsa(J2000, mode) } }
                    }.awaitAll()
            results.forEach { (mode, value) -> value shouldBe expected.getValue(mode) }
        }
    }

    @Test
    fun `Placidus is reported as undefined beyond the polar circle`() {
        val tromso = Place(latitude = 69.65, longitude = 18.96)
        val houses = ephemeris.sessionBlocking { houses(J2000, tromso, 'P') }
        houses.cusps shouldBe null
        ephemeris.sessionBlocking { houses(J2000, kyiv, 'W') }.cusps?.size shouldBe 12
    }

    @Test
    fun `sunrise in Kyiv and none during the polar day`() {
        val jd = JulianDay.of(timestamp("2024-06-21T00:00:00Z"))
        val rise = ephemeris.sessionBlocking { riseTrans(jd, SweConstants.SE_SUN, kyiv, SweConstants.SE_CALC_RISE) }
        val expected = timestamp("2024-06-21T01:46:00Z").seconds
        abs(JulianDay.toTimestamp(rise!!).seconds - expected) shouldBeLessThanOrEqual 2L
        val tromso = Place(latitude = 69.65, longitude = 18.96)
        ephemeris.sessionBlocking { riseTrans(jd, SweConstants.SE_SUN, tromso, SweConstants.SE_CALC_RISE) } shouldBe null
    }

    @Test
    fun `version names the engine`() {
        ephemeris.version shouldStartWith "Swiss Ephemeris 2.10"
    }

    @Test
    fun `startup fails when the asteroid file for Chiron is missing`(
        @TempDir partial: Path,
    ) {
        for (file in listOf("sepl_18.se1", "semo_18.se1")) {
            Files.copy(TestEphemeris.epheDirectory.resolve(file), partial.resolve(file))
        }
        val error = shouldThrow<EphemerisException> { SwissEphemeris.open(TestEphemeris.library, partial, threads = 1) }
        error.message shouldContain "self-check failed"
        error.cause?.message.orEmpty() shouldContain "seas_18"
    }

    @Test
    fun `startup fails instead of falling back to the Moshier model`(
        @TempDir empty: Path,
    ) {
        val error = shouldThrow<EphemerisException> { SwissEphemeris.open(TestEphemeris.library, empty, threads = 1) }
        error.message shouldContain "self-check failed"
        error.cause?.message.orEmpty() shouldContain "fell back"
    }
}
