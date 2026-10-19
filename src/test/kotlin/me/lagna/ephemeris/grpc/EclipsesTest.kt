// SPDX-License-Identifier: AGPL-3.0-or-later
package me.lagna.ephemeris.grpc

import com.google.protobuf.Timestamp
import io.grpc.Status
import io.grpc.StatusException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import me.lagna.ephemeris.cache.NoopResultCache
import me.lagna.ephemeris.timestamp
import me.lagna.ephemeris.toInstant
import me.lagna.ephemeris.v1.Eclipse
import me.lagna.ephemeris.v1.EclipseKind
import me.lagna.ephemeris.v1.EclipseType
import me.lagna.ephemeris.v1.EclipsesRequest
import me.lagna.ephemeris.v1.GeoLocation
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.math.abs

/**
 * Expected values from NASA's catalogue (eclipse.gsfc.nasa.gov, "Eclipses During 2026"), which gives lunar contacts
 * to the minute. NASA's Delta T differs from the Swiss Ephemeris' by a few seconds, hence the tolerances.
 */
class EclipsesTest {
    private val service = InProcessService(NoopResultCache)
    private val client = service.client

    @AfterEach
    fun close() = service.close()

    @Test
    fun `solar eclipses of 2026`() {
        runBlocking {
            val (annular, total) = eclipses(EclipseKind.SOLAR, "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z").also { it shouldHaveSize 2 }
            annular.type shouldBe EclipseType.ANNULAR
            annular.maximum shouldBeNear "2026-02-17T12:12:00Z"
            annular.magnitude shouldBe (0.96299 plusOrMinus 0.002)
            total.type shouldBe EclipseType.TOTAL
            total.maximum shouldBeNear "2026-08-12T17:45:54Z" // 17:47:05.2 TD
            total.magnitude shouldBe (1.0386 plusOrMinus 0.002)
            for (eclipse in listOf(annular, total)) {
                eclipse.kind shouldBe EclipseKind.SOLAR
                ordered(eclipse.begin, eclipse.totalityBegin, eclipse.maximum, eclipse.totalityEnd, eclipse.end) shouldBe true
            }
        }
    }

    @Test
    fun `lunar eclipses of 2026 with their contacts`() {
        runBlocking {
            val (total, partial) = eclipses(EclipseKind.LUNAR, "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z").also { it shouldHaveSize 2 }
            total.type shouldBe EclipseType.TOTAL
            total.maximum shouldBeNear "2026-03-03T11:34:00Z"
            total.magnitude shouldBe (1.1507 plusOrMinus 0.001)
            total.begin shouldBeNear "2026-03-03T09:50:00Z"
            total.totalityBegin shouldBeNear "2026-03-03T11:04:00Z"
            total.totalityEnd shouldBeNear "2026-03-03T12:03:00Z"
            total.end shouldBeNear "2026-03-03T13:17:00Z"

            partial.type shouldBe EclipseType.PARTIAL
            partial.maximum shouldBeNear "2026-08-28T04:13:00Z"
            partial.magnitude shouldBe (0.9299 plusOrMinus 0.001)
            partial.begin shouldBeNear "2026-08-28T02:34:00Z"
            partial.end shouldBeNear "2026-08-28T05:52:00Z"
            partial.hasTotalityBegin() shouldBe false

            val penumbral = eclipses(EclipseKind.LUNAR, "2027-02-01T00:00:00Z", "2027-03-01T00:00:00Z").single()
            penumbral.type shouldBe EclipseType.PENUMBRAL
            ordered(penumbral.begin, penumbral.maximum, penumbral.end) shouldBe true
        }
    }

    @Test
    fun `local eclipses are the visible ones, limited by sunset and moonset`() {
        runBlocking {
            val reykjavik = place(64.1466, -21.9426)
            val totalThere = eclipses(EclipseKind.SOLAR, "2026-08-01T00:00:00Z", "2026-09-01T00:00:00Z", reykjavik).single()
            totalThere.type shouldBe EclipseType.TOTAL
            ordered(totalThere.begin, totalThere.totalityBegin, totalThere.totalityEnd, totalThere.end) shouldBe true

            // Seen from Kyiv the Sun sets eclipsed: the eclipse ends at sunset, which is also its local maximum.
            val kyiv = place(50.45, 30.52)
            val atSunset = eclipses(EclipseKind.SOLAR, "2026-08-01T00:00:00Z", "2026-09-01T00:00:00Z", kyiv).single()
            atSunset.type shouldBe EclipseType.PARTIAL
            atSunset.end shouldBe atSunset.maximum
            (atSunset.magnitude < 0.5) shouldBe true

            // The total lunar eclipse of 2026-03-03 happens in Kyiv's daytime; the partial one of 2026-08-28 ends at moonset.
            eclipses(EclipseKind.LUNAR, "2026-02-01T00:00:00Z", "2026-04-01T00:00:00Z", kyiv).shouldBeEmpty()
            val moonset = eclipses(EclipseKind.LUNAR, "2026-08-01T00:00:00Z", "2026-09-01T00:00:00Z", kyiv).single()
            moonset.end shouldBe moonset.maximum
            moonset.end.toInstant().isBefore(Instant.parse("2026-08-28T05:52:00Z")) shouldBe true

            // Neither solar eclipse of 2026 reaches Sydney.
            eclipses(EclipseKind.SOLAR, "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", place(-33.87, 151.21)).shouldBeEmpty()
        }
    }

    @Test
    fun `an eclipse is in the range when its maximum is`() {
        runBlocking {
            eclipses(EclipseKind.SOLAR, "2026-08-12T17:46:30Z", "2026-09-01T00:00:00Z").shouldBeEmpty()
            eclipses(EclipseKind.SOLAR, "2026-08-12T15:00:00Z", "2026-08-12T17:45:00Z").shouldBeEmpty()
            eclipses(EclipseKind.SOLAR, "2026-08-12T17:45:00Z", "2026-08-12T17:47:00Z") shouldHaveSize 1
        }
    }

    @Test
    fun `malformed eclipse requests are rejected`() {
        runBlocking {
            val valid = request(EclipseKind.SOLAR, "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", null)
            val invalid =
                listOf(
                    valid.toBuilder().setKind(EclipseKind.ECLIPSE_KIND_UNSPECIFIED).build(),
                    valid.toBuilder().setLocation(place(50.0, 30.0).toBuilder().setAltitudeM(30_000.0)).build(),
                    valid.toBuilder().setTo(valid.from).build(),
                )
            for (request in invalid) {
                shouldThrow<StatusException> { client.findEclipses(request) }.status.code shouldBe Status.Code.INVALID_ARGUMENT
            }
        }
    }

    private suspend fun eclipses(
        kind: EclipseKind,
        from: String,
        to: String,
        location: GeoLocation? = null,
    ): List<Eclipse> = client.findEclipses(request(kind, from, to, location)).eclipsesList

    private fun request(
        kind: EclipseKind,
        from: String,
        to: String,
        location: GeoLocation?,
    ): EclipsesRequest {
        val request =
            EclipsesRequest
                .newBuilder()
                .setKind(kind)
                .setFrom(timestamp(from))
                .setTo(timestamp(to))
        location?.let { request.setLocation(it) }
        return request.build()
    }

    /** Within 90 s: NASA rounds to the minute and its Delta T differs by a few seconds. */
    private infix fun Timestamp.shouldBeNear(iso: String) {
        abs(toInstant().epochSecond - Instant.parse(iso).epochSecond) shouldBeLessThanOrEqual 90L
    }

    private fun ordered(vararg instants: Timestamp): Boolean = instants.map { it.toInstant() }.zipWithNext().all { (a, b) -> a <= b }

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
