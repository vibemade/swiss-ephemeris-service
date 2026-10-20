// SPDX-License-Identifier: AGPL-3.0-or-later
package me.lagna.ephemeris.grpc

import io.grpc.Status
import io.grpc.StatusException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import me.lagna.ephemeris.TestEphemeris
import me.lagna.ephemeris.cache.NoopResultCache
import me.lagna.ephemeris.points.MeanElements
import me.lagna.ephemeris.search.MotionSearch
import me.lagna.ephemeris.swe.JulianDay
import me.lagna.ephemeris.swe.SweConstants
import me.lagna.ephemeris.timestamp
import me.lagna.ephemeris.v1.Ayanamsa
import me.lagna.ephemeris.v1.Body
import me.lagna.ephemeris.v1.MeanFrame
import me.lagna.ephemeris.v1.MeanModel
import me.lagna.ephemeris.v1.MeanPositionsRequest
import me.lagna.ephemeris.v1.PositionsRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.math.abs

class MeanPositionsTest {
    private val service = InProcessService(NoopResultCache)
    private val client = service.client
    private val classical = listOf(Body.SUN, Body.MOON, Body.MERCURY, Body.VENUS, Body.MARS, Body.JUPITER, Body.SATURN)
    private val instants = listOf("1900-01-01T00:00:00Z", "2000-01-01T12:00:00Z", "2026-10-19T00:00:00Z", "2100-01-01T00:00:00Z")

    @AfterEach
    fun close() = service.close()

    @Test
    fun `the mean Sun follows Meeus's L0 and each planet has its madhya and sighrocca`() {
        runBlocking {
            val response = client.getMeanPositions(request(MeanModel.MODERN))
            response.frame shouldBe MeanFrame.REQUEST_ZODIAC
            for ((iso, result) in instants.zip(response.resultsList)) {
                val jd = JulianDay.of(timestamp(iso))
                val t = (jd + TestEphemeris.instance.sessionBlocking { deltaT(jd) } - 2451545.0) / 36525
                val at = result.positionsList.associateBy { it.body }
                val meanSun = MotionSearch.normalize(280.46646 + 36000.76983 * t + 0.0003032 * t * t) // Meeus 25.2
                at.getValue(Body.SUN).meanLongitude shouldBe (meanSun plusOrMinus 1e-4)
                at.getValue(Body.SUN).hasSighrocca() shouldBe false
                at.getValue(Body.MOON).hasSighrocca() shouldBe false
                for (inner in listOf(Body.MERCURY, Body.VENUS)) at.getValue(inner).meanLongitude shouldBe at.getValue(Body.SUN).meanLongitude
                for (outer in listOf(Body.MARS, Body.JUPITER, Body.SATURN)) at.getValue(outer).sighrocca shouldBe at.getValue(Body.SUN).meanLongitude
                at.getValue(Body.MARS).meanLongitude shouldBe MeanElements.heliocentric(MeanElements.Planet.MARS, t)
                at.getValue(Body.MERCURY).sighrocca shouldBe MeanElements.heliocentric(MeanElements.Planet.MERCURY, t)
            }
            // At J2000.0 TT the mean longitudes are the constant terms.
            val epoch = response.getResults(1).positionsList.associateBy { it.body }
            epoch.getValue(Body.MARS).meanLongitude shouldBe (355.43299958 plusOrMinus 1e-3)
            epoch.getValue(Body.MOON).meanLongitude shouldBe (218.3164477 plusOrMinus 1e-2)
        }
    }

    @Test
    fun `mean longitudes stay within the equation of centre of the true ones`() {
        runBlocking {
            val mean = client.getMeanPositions(request(MeanModel.MODERN)).resultsList
            val trueSun =
                client
                    .getPositions(
                        PositionsRequest
                            .newBuilder()
                            .addAllInstants(instants.map { timestamp(it) })
                            .addBodies(Body.SUN)
                            .addBodies(Body.MOON)
                            .build(),
                    ).resultsList
            for ((m, p) in mean.zip(trueSun)) {
                val at = m.positionsList.associateBy { it.body }
                abs(MotionSearch.signedDelta(at.getValue(Body.SUN).meanLongitude - p.getPositions(0).longitude)) shouldBeLessThan 2.1
                abs(MotionSearch.signedDelta(at.getValue(Body.MOON).meanLongitude - p.getPositions(1).longitude)) shouldBeLessThan 9.0
            }
        }
    }

    @Test
    fun `sidereal mean longitudes subtract the mean ayanamsa`() {
        runBlocking {
            val tropical = client.getMeanPositions(request(MeanModel.MODERN)).resultsList
            val sidereal = client.getMeanPositions(request(MeanModel.MODERN).toBuilder().setZodiac(sidereal(Ayanamsa.LAHIRI)).build()).resultsList
            for ((iso, both) in instants.zip(tropical.zip(sidereal))) {
                val jd = JulianDay.of(timestamp(iso))
                val ayanamsa = TestEphemeris.instance.sessionBlocking { ayanamsa(jd, SweConstants.SE_SIDM_LAHIRI, withNutation = false) }
                both.first.positionsList.zip(both.second.positionsList).forEach { (t, s) ->
                    MotionSearch.signedDelta(t.meanLongitude - s.meanLongitude - ayanamsa) shouldBe (0.0 plusOrMinus 1e-9)
                }
            }
        }
    }

    @Test
    fun `classical mean planets reproduce Raman's Standard Horoscope`() {
        runBlocking {
            // Graha and Bhava Balas, examples 41-48: 1918-10-16, 14:06:16 LMT at 77 35' E.
            val request =
                MeanPositionsRequest
                    .newBuilder()
                    .addInstants(timestamp("1918-10-16T08:55:56Z"))
                    .addAllBodies(classical - Body.MOON)
                    .setModel(MeanModel.CLASSICAL)
                    .build()
            val response = client.getMeanPositions(request)
            response.frame shouldBe MeanFrame.CLASSICAL_SIDEREAL
            val at = response.getResults(0).positionsList.associateBy { it.body }
            at.getValue(Body.SUN).meanLongitude shouldBe (181.2276 plusOrMinus 0.02)
            at.getValue(Body.MARS).meanLongitude shouldBe (266.34 plusOrMinus 0.02)
            // The book's example uses a misprinted cell of table VI (800 days: 66.58 instead of 66.48).
            at.getValue(Body.JUPITER).meanLongitude shouldBe (66.91 plusOrMinus 0.1)
            at.getValue(Body.SATURN).meanLongitude shouldBe (111.23 plusOrMinus 0.02)
            at.getValue(Body.MERCURY).sighrocca shouldBe (174.49 plusOrMinus 0.05)
            at.getValue(Body.VENUS).sighrocca shouldBe (158.35 plusOrMinus 0.02)

            // Same split between mean longitude and sighrocca as MODERN.
            at.getValue(Body.SUN).hasSighrocca() shouldBe false
            for (inner in listOf(Body.MERCURY, Body.VENUS)) at.getValue(inner).meanLongitude shouldBe at.getValue(Body.SUN).meanLongitude
            for (outer in listOf(Body.MARS, Body.JUPITER, Body.SATURN)) at.getValue(outer).sighrocca shouldBe at.getValue(Body.SUN).meanLongitude

            // The tables have their own frame: the zodiac is ignored, and no ayanamsa matches it.
            client.getMeanPositions(request.toBuilder().setZodiac(sidereal(Ayanamsa.LAHIRI)).build()) shouldBe response
            val raman =
                client
                    .getMeanPositions(request.toBuilder().setModel(MeanModel.MODERN).setZodiac(sidereal(Ayanamsa.RAMAN)).build())
                    .getResults(0)
                    .positionsList
                    .first { it.body == Body.SUN }
                    .meanLongitude
            MotionSearch.signedDelta(raman - at.getValue(Body.SUN).meanLongitude) shouldBe (1.56 plusOrMinus 0.05)
        }
    }

    @Test
    fun `the classical model has no mean Moon, and other bodies and models are rejected`() {
        runBlocking {
            shouldThrow<StatusException> { client.getMeanPositions(request(MeanModel.CLASSICAL)) }.status.code shouldBe
                Status.Code.INVALID_ARGUMENT
            shouldThrow<StatusException> { client.getMeanPositions(request(MeanModel.MEAN_MODEL_UNSPECIFIED)) }.status.code shouldBe
                Status.Code.INVALID_ARGUMENT
            shouldThrow<StatusException> {
                client.getMeanPositions(request(MeanModel.MODERN).toBuilder().addBodies(Body.URANUS).build())
            }.status.code shouldBe Status.Code.INVALID_ARGUMENT
        }
    }

    private fun request(model: MeanModel): MeanPositionsRequest =
        MeanPositionsRequest
            .newBuilder()
            .addAllInstants(instants.map { timestamp(it) })
            .addAllBodies(classical)
            .setModel(model)
            .build()
}
