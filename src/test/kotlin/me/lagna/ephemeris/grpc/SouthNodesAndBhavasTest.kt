// SPDX-License-Identifier: AGPL-3.0-or-later
package me.lagna.ephemeris.grpc

import io.grpc.Status
import io.grpc.StatusException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import me.lagna.ephemeris.cache.NoopResultCache
import me.lagna.ephemeris.search.MotionSearch
import me.lagna.ephemeris.timestamp
import me.lagna.ephemeris.v1.Ayanamsa
import me.lagna.ephemeris.v1.Body
import me.lagna.ephemeris.v1.CrossingsRequest
import me.lagna.ephemeris.v1.DiscConvention
import me.lagna.ephemeris.v1.GeoLocation
import me.lagna.ephemeris.v1.HouseQuery
import me.lagna.ephemeris.v1.HouseSystem
import me.lagna.ephemeris.v1.HousesRequest
import me.lagna.ephemeris.v1.LongitudeGrid
import me.lagna.ephemeris.v1.PositionsRequest
import me.lagna.ephemeris.v1.RiseSetEvent
import me.lagna.ephemeris.v1.RiseSetQuery
import me.lagna.ephemeris.v1.RiseSetRequest
import me.lagna.ephemeris.v1.StationsRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.math.abs

class SouthNodesAndBhavasTest {
    private val service = InProcessService(NoopResultCache)
    private val client = service.client
    private val lahiri = sidereal(Ayanamsa.LAHIRI)
    private val kyiv =
        GeoLocation
            .newBuilder()
            .setLatitude(50.45)
            .setLongitude(30.52)
            .build()

    @AfterEach
    fun close() = service.close()

    @Test
    fun `a south node is its node plus 180 degrees with the latitude negated`() {
        runBlocking {
            val positions =
                client
                    .getPositions(
                        PositionsRequest
                            .newBuilder()
                            .addInstants(timestamp("2000-01-01T12:00:00Z"))
                            .addInstants(timestamp("2026-10-19T00:00:00Z"))
                            .addAllBodies(listOf(Body.TRUE_NODE, Body.TRUE_SOUTH_NODE, Body.MEAN_NODE, Body.MEAN_SOUTH_NODE))
                            .setZodiac(lahiri)
                            .setEquatorial(true)
                            .build(),
                    ).resultsList
            for (instant in positions) {
                for ((node, south) in listOf(instant.getPositions(0) to instant.getPositions(1), instant.getPositions(2) to instant.getPositions(3))) {
                    south.longitude shouldBe MotionSearch.normalize(node.longitude + 180)
                    south.latitude shouldBe -node.latitude
                    south.longitudeSpeed shouldBe node.longitudeSpeed
                    south.distanceAu shouldBe node.distanceAu
                    south.rightAscension shouldBe (MotionSearch.normalize(node.rightAscension + 180) plusOrMinus 1e-9)
                    south.declination shouldBe -node.declination
                }
            }
        }
    }

    @Test
    fun `south node stations are the node's and its crossings are the node's opposite ones`() {
        runBlocking {
            fun stations(body: Body) =
                StationsRequest
                    .newBuilder()
                    .setBody(body)
                    .setFrom(timestamp("2024-01-01T00:00:00Z"))
                    .setTo(timestamp("2024-03-01T00:00:00Z"))
                    .build()
            val node = client.findStations(stations(Body.TRUE_NODE)).stationsList
            val south = client.findStations(stations(Body.TRUE_SOUTH_NODE)).stationsList
            (node.size > 0) shouldBe true
            south.map { it.instant } shouldBe node.map { it.instant }
            south.map { it.turnsRetrograde } shouldBe node.map { it.turnsRetrograde }
            client.findStations(stations(Body.MEAN_SOUTH_NODE)).stationsList shouldHaveSize 0

            fun crossings(body: Body) =
                CrossingsRequest
                    .newBuilder()
                    .setBody(body)
                    .setZodiac(lahiri)
                    .setFrom(timestamp("2000-01-01T00:00:00Z"))
                    .setTo(timestamp("2020-01-01T00:00:00Z"))
                    .setGrid(LongitudeGrid.newBuilder().setStepDegrees(30.0))
                    .build()
            val ketu = client.findCrossings(crossings(Body.MEAN_SOUTH_NODE)).crossingsList
            val rahu = client.findCrossings(crossings(Body.MEAN_NODE)).crossingsList
            ketu shouldHaveSize rahu.size
            ketu.zip(rahu).forEach { (k, r) ->
                k.longitude shouldBe MotionSearch.normalize(r.longitude + 180)
                k.retrograde shouldBe true
                (abs(k.instant.seconds - r.instant.seconds) <= 1L) shouldBe true
            }
        }
    }

    @Test
    fun `a south node rises when its node sets, on the geometric horizon only`() {
        runBlocking {
            fun request(
                body: Body,
                convention: DiscConvention,
                vararg events: RiseSetEvent,
            ) = RiseSetRequest
                .newBuilder()
                .setBody(body)
                .setConvention(convention)
                .addQueries(
                    RiseSetQuery
                        .newBuilder()
                        .setAfter(timestamp("2026-10-19T00:00:00Z"))
                        .setLocation(kyiv)
                        .addAllEvents(events.toList()),
                ).build()
            val geometric = DiscConvention.CENTRE_NO_REFRACTION
            val south =
                client.getRiseSet(request(Body.TRUE_SOUTH_NODE, geometric, RiseSetEvent.RISE, RiseSetEvent.UPPER_TRANSIT)).getResults(0)
            val node =
                client.getRiseSet(request(Body.TRUE_NODE, geometric, RiseSetEvent.SET, RiseSetEvent.LOWER_TRANSIT)).getResults(0)
            south.timesList.map { it.event } shouldBe listOf(RiseSetEvent.RISE, RiseSetEvent.UPPER_TRANSIT)
            south.timesList.map { it.instant } shouldBe node.timesList.map { it.instant }

            val refracted =
                shouldThrow<StatusException> {
                    client.getRiseSet(request(Body.MEAN_SOUTH_NODE, DiscConvention.UPPER_LIMB_REFRACTED, RiseSetEvent.RISE))
                }
            refracted.status.code shouldBe Status.Code.INVALID_ARGUMENT
            client
                .getRiseSet(request(Body.MEAN_SOUTH_NODE, DiscConvention.UPPER_LIMB_REFRACTED, RiseSetEvent.UPPER_TRANSIT))
                .getResults(0)
                .getTimes(0)
                .hasInstant() shouldBe true
        }
    }

    @Test
    fun `Sripati bhavas are the Porphyry cusps as madhyas and the Sripati cusps as sandhis between them`() {
        runBlocking {
            suspend fun houses(system: HouseSystem) =
                client
                    .getHouses(
                        HousesRequest
                            .newBuilder()
                            .setSystem(system)
                            .setZodiac(lahiri)
                            .addQueries(HouseQuery.newBuilder().setInstant(timestamp("1990-05-17T08:30:00Z")).setLocation(kyiv))
                            .build(),
                    ).getResults(0)
            val sripati = houses(HouseSystem.SRIPATI)
            val madhya = sripati.bhavaMadhyaList
            val sandhi = sripati.bhavaSandhiList
            madhya shouldHaveSize 12
            madhya[0] shouldBe (sripati.ascendant plusOrMinus 1e-9)
            madhya[9] shouldBe (sripati.midheaven plusOrMinus 1e-9)
            madhya shouldBe houses(HouseSystem.PORPHYRY).cuspsList
            sandhi shouldBe sripati.cuspsList
            for (i in 0 until 12) {
                val previous = madhya[(i + 11) % 12]
                val halfway = MotionSearch.normalize(previous + MotionSearch.signedDelta(madhya[i] - previous) / 2)
                sandhi[i] shouldBe (halfway plusOrMinus 1e-9)
            }
            houses(HouseSystem.PLACIDUS).bhavaMadhyaCount shouldBe 0
        }
    }
}
