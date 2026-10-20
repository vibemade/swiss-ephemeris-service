// SPDX-License-Identifier: AGPL-3.0-or-later
package me.lagna.ephemeris.grpc

import io.grpc.Status
import io.grpc.StatusException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import me.lagna.ephemeris.ServiceConfig
import me.lagna.ephemeris.cache.NoopResultCache
import me.lagna.ephemeris.timestamp
import me.lagna.ephemeris.v1.Ayanamsa
import me.lagna.ephemeris.v1.Body
import me.lagna.ephemeris.v1.DiscConvention
import me.lagna.ephemeris.v1.GeoLocation
import me.lagna.ephemeris.v1.HouseQuery
import me.lagna.ephemeris.v1.HouseSystem
import me.lagna.ephemeris.v1.HousesRequest
import me.lagna.ephemeris.v1.InstantAtPlace
import me.lagna.ephemeris.v1.PositionsRequest
import me.lagna.ephemeris.v1.UpagrahasRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * The largest responses the default limits allow stay under gRPC's default 4 MiB message size. Instants before 1970
 * take the most room (negative seconds are 10-byte varints), and fractional instants add nanos.
 */
class ResponseSizeTest {
    private val service = InProcessService(NoopResultCache)
    private val client = service.client
    private val maxItems = ServiceConfig.DEFAULT_MAX_ITEMS
    private val kyiv =
        GeoLocation
            .newBuilder()
            .setLatitude(50.45)
            .setLongitude(30.52)
            .setAltitudeM(179.0)
            .build()

    @AfterEach
    fun close() = service.close()

    @Test
    fun `a full batch of upagrahas fits, one more query is refused`() {
        runBlocking {
            val limit = maxItems / 19
            val request = upagrahas(limit)
            val response = client.getUpagrahas(request)
            response.resultsCount shouldBe limit
            response.resultsList.all { it.portionsCount == 8 } shouldBe true
            response.serializedSize shouldBeLessThan GRPC_DEFAULT_MAX_MESSAGE

            val tooMany = shouldThrow<StatusException> { client.getUpagrahas(upagrahas(limit + 1)) }
            tooMany.status.code shouldBe Status.Code.INVALID_ARGUMENT
        }
    }

    @Test
    fun `full batches of Sripati houses and equatorial positions fit`() {
        runBlocking {
            val houses =
                HousesRequest
                    .newBuilder()
                    .setSystem(HouseSystem.SRIPATI)
                    .setZodiac(sidereal(Ayanamsa.LAHIRI))
                    .addAllQueries((0 until maxItems).map { HouseQuery.newBuilder().setInstant(instant(it)).setLocation(kyiv).build() })
                    .build()
            client.getHouses(houses).serializedSize shouldBeLessThan GRPC_DEFAULT_MAX_MESSAGE

            val positions =
                PositionsRequest
                    .newBuilder()
                    .addAllInstants((0 until maxItems / 16).map { instant(it) })
                    .addAllBodies(Body.values().filter { it != Body.BODY_UNSPECIFIED && it != Body.UNRECOGNIZED })
                    .setEquatorial(true)
                    .build()
            client.getPositions(positions).serializedSize shouldBeLessThan GRPC_DEFAULT_MAX_MESSAGE
        }
    }

    private fun upagrahas(queries: Int): UpagrahasRequest =
        UpagrahasRequest
            .newBuilder()
            .addAllQueries((0 until queries).map { InstantAtPlace.newBuilder().setInstant(instant(it)).setLocation(kyiv).build() })
            .setZodiac(sidereal(Ayanamsa.LAHIRI))
            .setSunrise(DiscConvention.CENTRE_NO_REFRACTION)
            .build()

    /** Daytime instants before 1970, spread over the year, with milliseconds. */
    private fun instant(i: Int) =
        timestamp("1900-01-01T09:00:00Z").let {
            it
                .toBuilder()
                .setSeconds(it.seconds + i * 86_400L * 3 + 1234)
                .setNanos(567_000_000)
                .build()
        }

    private companion object {
        const val GRPC_DEFAULT_MAX_MESSAGE = 4 * 1024 * 1024
    }
}
