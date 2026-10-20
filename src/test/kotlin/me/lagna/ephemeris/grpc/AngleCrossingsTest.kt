// SPDX-License-Identifier: AGPL-3.0-or-later
package me.lagna.ephemeris.grpc

import com.google.protobuf.Timestamp
import io.grpc.Status
import io.grpc.StatusException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import me.lagna.ephemeris.cache.NoopResultCache
import me.lagna.ephemeris.search.MotionSearch
import me.lagna.ephemeris.timestamp
import me.lagna.ephemeris.v1.AngleCrossingsRequest
import me.lagna.ephemeris.v1.Ayanamsa
import me.lagna.ephemeris.v1.ChartAngle
import me.lagna.ephemeris.v1.Crossing
import me.lagna.ephemeris.v1.GeoLocation
import me.lagna.ephemeris.v1.HouseQuery
import me.lagna.ephemeris.v1.HouseSystem
import me.lagna.ephemeris.v1.HousesRequest
import me.lagna.ephemeris.v1.LongitudeGrid
import me.lagna.ephemeris.v1.Zodiac
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class AngleCrossingsTest {
    private val service = InProcessService(NoopResultCache)
    private val client = service.client

    private val kyiv = place(50.45, 30.52)
    private val tromso = place(69.65, 18.96)

    /** One mean sidereal day (23h 56m 4s). */
    private val dayFrom = "2024-03-20T00:00:00Z"
    private val dayTo = "2024-03-20T23:56:04Z"

    @AfterEach
    fun close() = service.close()

    @Test
    fun `the lagna changes sign 12 times and navamsa 108 times in a sidereal day`() {
        runBlocking {
            val signs = client.findAngleCrossings(request(ChartAngle.ASCENDANT, kyiv, 30.0)).crossingsList
            signs shouldHaveSize 12
            signs.map { it.longitude }.sorted() shouldBe (0 until 12).map { it * 30.0 }
            signs.none { it.retrograde } shouldBe true
            assertBracketed(signs, ChartAngle.ASCENDANT, kyiv)

            val navamsas = client.findAngleCrossings(request(ChartAngle.ASCENDANT, kyiv, 360.0 / 108)).crossingsList
            navamsas shouldHaveSize 108
            assertBracketed(navamsas, ChartAngle.ASCENDANT, kyiv)
        }
    }

    @Test
    fun `sidereal lagna crossings`() {
        runBlocking {
            val lahiri = sidereal(Ayanamsa.LAHIRI)
            val signs = client.findAngleCrossings(request(ChartAngle.ASCENDANT, kyiv, 30.0).toBuilder().setZodiac(lahiri).build()).crossingsList
            signs shouldHaveSize 12
            assertBracketed(signs, ChartAngle.ASCENDANT, kyiv, lahiri)
        }
    }

    @Test
    fun `the midheaven works beyond the polar circles, the ascendant up to 66_5 degrees`() {
        runBlocking {
            val midheaven = client.findAngleCrossings(request(ChartAngle.MIDHEAVEN, tromso, 30.0)).crossingsList
            midheaven shouldHaveSize 12
            assertBracketed(midheaven, ChartAngle.MIDHEAVEN, tromso)

            // At the edge the ascendant sweeps most of the zodiac within minutes, but still only forward.
            val edge = place(66.5, 18.96)
            val degrees = client.findAngleCrossings(request(ChartAngle.ASCENDANT, edge, 1.0)).crossingsList
            degrees shouldHaveSize 360
            assertBracketed(degrees, ChartAngle.ASCENDANT, edge)

            val polar =
                shouldThrow<StatusException> { client.findAngleCrossings(request(ChartAngle.ASCENDANT, tromso, 30.0)) }
            polar.status.code shouldBe Status.Code.INVALID_ARGUMENT
        }
    }

    @Test
    fun `malformed angle requests are rejected`() {
        runBlocking {
            val valid = request(ChartAngle.ASCENDANT, kyiv, 30.0)
            for (request in listOf(valid.toBuilder().clearLocation().build(), valid.toBuilder().setAngle(ChartAngle.CHART_ANGLE_UNSPECIFIED).build())) {
                shouldThrow<StatusException> { client.findAngleCrossings(request) }.status.code shouldBe Status.Code.INVALID_ARGUMENT
            }
        }
    }

    private fun request(
        angle: ChartAngle,
        location: GeoLocation,
        step: Double,
    ): AngleCrossingsRequest =
        AngleCrossingsRequest
            .newBuilder()
            .setAngle(angle)
            .setLocation(location)
            .setFrom(timestamp(dayFrom))
            .setTo(timestamp(dayTo))
            .setGrid(LongitudeGrid.newBuilder().setStepDegrees(step))
            .build()

    /** One second before each crossing the angle is short of its target, one second after it is past it. */
    private suspend fun assertBracketed(
        crossings: List<Crossing>,
        angle: ChartAngle,
        location: GeoLocation,
        zodiac: Zodiac? = null,
    ) {
        val houses = HousesRequest.newBuilder().setSystem(HouseSystem.EQUAL)
        zodiac?.let { houses.setZodiac(it) }
        for (crossing in crossings) {
            for (offset in listOf(-1L, 1L)) {
                houses.addQueries(HouseQuery.newBuilder().setInstant(crossing.instant.plusSeconds(offset)).setLocation(location))
            }
        }
        val results = client.getHouses(houses.build()).resultsList
        crossings.forEachIndexed { i, crossing ->
            val (before, after) =
                results.subList(2 * i, 2 * i + 2).map { if (angle == ChartAngle.ASCENDANT) it.ascendant else it.midheaven }
            withClue("crossing of ${crossing.longitude} at ${crossing.instant}") {
                MotionSearch.signedDelta(before - crossing.longitude) shouldBeLessThan 0.0
                MotionSearch.signedDelta(after - crossing.longitude) shouldBeGreaterThan 0.0
            }
        }
    }

    private fun Timestamp.plusSeconds(seconds: Long): Timestamp = toBuilder().setSeconds(this.seconds + seconds).build()

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
