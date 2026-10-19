// SPDX-License-Identifier: AGPL-3.0-or-later
package me.lagna.ephemeris.grpc

import com.google.protobuf.Timestamp
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import me.lagna.ephemeris.cache.NoopResultCache
import me.lagna.ephemeris.search.MotionSearch
import me.lagna.ephemeris.timestamp
import me.lagna.ephemeris.toInstant
import me.lagna.ephemeris.v1.Ayanamsa
import me.lagna.ephemeris.v1.Body
import me.lagna.ephemeris.v1.DiscConvention
import me.lagna.ephemeris.v1.GeoLocation
import me.lagna.ephemeris.v1.HouseQuery
import me.lagna.ephemeris.v1.HouseSystem
import me.lagna.ephemeris.v1.HousesRequest
import me.lagna.ephemeris.v1.InstantAtPlace
import me.lagna.ephemeris.v1.PortionPoint
import me.lagna.ephemeris.v1.PositionsRequest
import me.lagna.ephemeris.v1.RiseSetEvent
import me.lagna.ephemeris.v1.RiseSetQuery
import me.lagna.ephemeris.v1.RiseSetRequest
import me.lagna.ephemeris.v1.Upagraha
import me.lagna.ephemeris.v1.UpagrahaResult
import me.lagna.ephemeris.v1.UpagrahasRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate

class UpagrahasTest {
    private val service = InProcessService(NoopResultCache)
    private val client = service.client
    private val lahiri = sidereal(Ayanamsa.LAHIRI)
    private val kyiv = place(50.45, 30.52)
    private val hindu = DiscConvention.CENTRE_NO_REFRACTION

    private val weekdayOrder = listOf(Body.SUN, Body.MOON, Body.MARS, Body.MERCURY, Body.JUPITER, Body.VENUS, Body.SATURN)

    @AfterEach
    fun close() = service.close()

    @Test
    fun `the Dhuma group follows from the Sun`() {
        runBlocking {
            val instant = timestamp("2026-10-19T09:00:00Z")
            val result = upagrahas(listOf(instant)).single()
            val sun =
                client
                    .getPositions(PositionsRequest.newBuilder().addInstants(instant).addBodies(Body.SUN).setZodiac(lahiri).build())
                    .getResults(0)
                    .getPositions(0)
                    .longitude
            val at = result.positionsList.associate { it.upagraha to it.longitude }
            result.positionsList.map { it.upagraha } shouldBe Upagraha.values().filter { it != Upagraha.UPAGRAHA_UNSPECIFIED && it != Upagraha.UNRECOGNIZED }
            angle(at.getValue(Upagraha.DHUMA) - sun) shouldBe (133.0 + 20.0 / 60 plusOrMinus 1e-9)
            angle(at.getValue(Upagraha.VYATIPATA) + at.getValue(Upagraha.DHUMA)) shouldBe (0.0 plusOrMinus 1e-9)
            angle(at.getValue(Upagraha.PARIVESHA) - at.getValue(Upagraha.VYATIPATA)) shouldBe (180.0 plusOrMinus 1e-9)
            angle(at.getValue(Upagraha.INDRACHAPA) + at.getValue(Upagraha.PARIVESHA)) shouldBe (0.0 plusOrMinus 1e-9)
            angle(at.getValue(Upagraha.UPAKETU) - at.getValue(Upagraha.INDRACHAPA)) shouldBe (16.0 + 40.0 / 60 plusOrMinus 1e-9)
            angle(at.getValue(Upagraha.UPAKETU) - sun + 30) shouldBe (0.0 plusOrMinus 1e-9) // the known identity Upaketu = Sun - 30
        }
    }

    @Test
    fun `eight portions cover the day from sunrise to sunset without gaps`() {
        runBlocking {
            val result = upagrahas(listOf(timestamp("2026-10-19T09:00:00Z"))).single()
            result.night shouldBe false
            val portions = result.portionsList
            portions shouldHaveSize 8
            portions.map { it.index } shouldBe (1..8).toList()
            portions.zipWithNext().forEach { (a, b) -> b.start shouldBe a.end }
            val (sunrise, sunset) = sunriseAndSunset("2026-10-19T00:00:00Z")
            secondsBetween(portions.first().start, sunrise) shouldBe (0.0 plusOrMinus 1.0)
            secondsBetween(portions.last().end, sunset) shouldBe (0.0 plusOrMinus 1.0)
            val lengths = portions.map { Duration.between(it.start.toInstant(), it.end.toInstant()).toMillis() }
            (lengths.max() - lengths.min() <= 1) shouldBe true
        }
    }

    @Test
    fun `portion lords for all seven days and nights, and an instant before sunrise belongs to the previous day`() {
        runBlocking {
            // 2026-10-18 is a Sunday. Kyiv local time is UTC+3: 09:00 UTC is midday, 19:00 UTC is evening.
            for (day in 0 until 7) {
                val date = LocalDate.parse("2026-10-18").plusDays(day.toLong())
                val (noon, evening) = upagrahas(listOf(timestamp("${date}T09:00:00Z"), timestamp("${date}T19:00:00Z")))
                noon.weekdayLord shouldBe weekdayOrder[day]
                evening.weekdayLord shouldBe weekdayOrder[day]
                evening.night shouldBe true
                noon.portionsList.take(7).map { it.lord } shouldBe (0 until 7).map { weekdayOrder[(day + it) % 7] }
                evening.portionsList.take(7).map { it.lord } shouldBe (0 until 7).map { weekdayOrder[(day + 4 + it) % 7] }
                noon.portionsList.last().hasLord() shouldBe false
            }
            // 05:00 local on Monday 2026-10-19, before sunrise: still Sunday's night, the second half of it.
            val beforeSunrise = upagrahas(listOf(timestamp("2026-10-19T02:00:00Z"))).single()
            beforeSunrise.weekdayLord shouldBe Body.SUN
            beforeSunrise.night shouldBe true
            beforeSunrise.portionsList.first().lord shouldBe Body.JUPITER
        }
    }

    @Test
    fun `the Kala group is the ascendant at its portion's point, Mandi Gulika unless placed apart`() {
        runBlocking {
            val instant = timestamp("2026-10-19T09:00:00Z")
            val default = upagrahas(listOf(instant)).single()
            val middle =
                upagrahas(listOf(instant)) { setKalaPoint(PortionPoint.PORTION_MIDDLE).setMandiPoint(PortionPoint.PORTION_END) }.single()
            val start = default.positionsList.associate { it.upagraha to it.longitude }
            val atMiddle = middle.positionsList.associate { it.upagraha to it.longitude }
            start.getValue(Upagraha.MANDI) shouldBe start.getValue(Upagraha.GULIKA)

            val portionOf = default.portionsList.associate { it.lord to it }
            val kala = mapOf(Upagraha.KALA to Body.SUN, Upagraha.MRITYU to Body.MARS, Upagraha.ARDHAPRAHARA to Body.MERCURY, Upagraha.YAMAGHANTAKA to Body.JUPITER, Upagraha.GULIKA to Body.SATURN)
            for ((upagraha, lord) in kala) {
                val portion = portionOf.getValue(lord)
                start.getValue(upagraha) shouldBe (ascendant(portion.start) plusOrMinus 1e-4)
                atMiddle.getValue(upagraha) shouldBe (ascendant(midpoint(portion.start, portion.end)) plusOrMinus 1e-4)
            }
            atMiddle.getValue(Upagraha.MANDI) shouldBe (ascendant(portionOf.getValue(Body.SATURN).end) plusOrMinus 1e-4)
        }
    }

    @Test
    fun `no portions and no Kala group during polar night or day`() {
        runBlocking {
            val tromso = place(69.65, 18.96)
            val (polarNight, midnightSun) =
                upagrahas(listOf(timestamp("2026-12-21T11:00:00Z"), timestamp("2026-06-21T23:00:00Z")), tromso)
            for (result in listOf(polarNight, midnightSun)) {
                result.portionsList shouldHaveSize 0
                result.positionsList.map { it.upagraha } shouldBe
                    listOf(Upagraha.DHUMA, Upagraha.VYATIPATA, Upagraha.PARIVESHA, Upagraha.INDRACHAPA, Upagraha.UPAKETU)
            }
            polarNight.night shouldBe true
            midnightSun.night shouldBe false
            polarNight.weekdayLord shouldBe Body.MOON // 2026-12-21 is a Monday
        }
    }

    private suspend fun upagrahas(
        instants: List<Timestamp>,
        location: GeoLocation = kyiv,
        configure: UpagrahasRequest.Builder.() -> UpagrahasRequest.Builder = { this },
    ): List<UpagrahaResult> =
        client
            .getUpagrahas(
                UpagrahasRequest
                    .newBuilder()
                    .addAllQueries(instants.map { InstantAtPlace.newBuilder().setInstant(it).setLocation(location).build() })
                    .setZodiac(lahiri)
                    .setSunrise(hindu)
                    .configure()
                    .build(),
            ).resultsList

    private suspend fun sunriseAndSunset(after: String): Pair<Timestamp, Timestamp> {
        val times =
            client
                .getRiseSet(
                    RiseSetRequest
                        .newBuilder()
                        .setBody(Body.SUN)
                        .setConvention(hindu)
                        .addQueries(
                            RiseSetQuery
                                .newBuilder()
                                .setAfter(timestamp(after))
                                .setLocation(kyiv)
                                .addEvents(RiseSetEvent.RISE)
                                .addEvents(RiseSetEvent.SET),
                        ).build(),
                ).getResults(0)
                .timesList
        return times[0].instant to times[1].instant
    }

    private suspend fun ascendant(instant: Timestamp): Double =
        client
            .getHouses(
                HousesRequest
                    .newBuilder()
                    .setSystem(HouseSystem.EQUAL)
                    .setZodiac(lahiri)
                    .addQueries(HouseQuery.newBuilder().setInstant(instant).setLocation(kyiv))
                    .build(),
            ).getResults(0)
            .ascendant

    private fun midpoint(
        a: Timestamp,
        b: Timestamp,
    ): Timestamp {
        val from = a.toInstant()
        val middle = from.plus(Duration.between(from, b.toInstant()).dividedBy(2))
        return Timestamp
            .newBuilder()
            .setSeconds(middle.epochSecond)
            .setNanos(middle.nano)
            .build()
    }

    /** An angle in [0, 360), except that values a hair below 0 stay negative so that checks against 0 work. */
    private fun angle(degrees: Double): Double = MotionSearch.signedDelta(degrees).let { if (it < -1e-6) it + 360 else it }

    private fun secondsBetween(
        a: Timestamp,
        b: Timestamp,
    ): Double = Duration.between(a.toInstant(), b.toInstant()).toNanos() / 1e9

    private fun place(
        latitude: Double,
        longitude: Double,
    ): GeoLocation =
        GeoLocation
            .newBuilder()
            .setLatitude(latitude)
            .setLongitude(longitude)
            .build()
}
