// SPDX-License-Identifier: AGPL-3.0-or-later
package me.lagna.ephemeris.grpc

import com.google.protobuf.Timestamp
import me.lagna.ephemeris.points.MeanElements
import me.lagna.ephemeris.points.MeanElements.Planet
import me.lagna.ephemeris.points.Upagrahas
import me.lagna.ephemeris.swe.Frame
import me.lagna.ephemeris.swe.JulianDay
import me.lagna.ephemeris.swe.Place
import me.lagna.ephemeris.swe.SweConstants
import me.lagna.ephemeris.swe.SweSession
import me.lagna.ephemeris.v1.DayPortion
import me.lagna.ephemeris.v1.InstantMeanPositions
import me.lagna.ephemeris.v1.MeanPosition
import me.lagna.ephemeris.v1.Upagraha
import me.lagna.ephemeris.v1.UpagrahaPosition
import me.lagna.ephemeris.v1.UpagrahaResult
import me.lagna.ephemeris.v1.Body as ProtoBody

/** How a GetUpagrahas request defines the day and places the Kala group within the portions. */
internal class UpagrahaSettings(
    val frame: Frame,
    val riseMode: Int,
    val setMode: Int,
    /** Where in its portion each of the Kala group is taken: 0 start, 0.5 middle, 1 end. */
    val kalaPoint: Double,
    /** The same for Mandi, or null when Mandi is Gulika. */
    val mandiPoint: Double?,
)

/** The day (sunrise to sunset) or night (sunset to the next sunrise) containing an instant. */
private class DayHalf(
    val start: Double,
    val end: Double,
    val night: Boolean,
    /** The sunrise that began the Vedic day. */
    val sunrise: Double,
)

/** Upagrahas of one instant at one place, as GetUpagrahas returns them. */
internal fun SweSession.upagrahas(
    jd: Double,
    place: Place,
    settings: UpagrahaSettings,
): UpagrahaResult {
    val half = dayHalf(jd, place, settings)
    val weekday = Upagrahas.weekday(half?.sunrise ?: jd, place.longitude)
    val result =
        UpagrahaResult
            .newBuilder()
            .setNight(half?.night ?: (sunAltitude(jd, place) < 0.0))
            .setWeekdayLord(ProtoMapping.protoBody(Upagrahas.WEEKDAY_LORDS[weekday]))

    val dhuma = Upagrahas.dhumaGroup(position(jd, SweConstants.SE_SUN, settings.frame).longitude)
    DHUMA_GROUP.zip(dhuma).forEach { (upagraha, longitude) -> result.addPositions(position(upagraha, longitude)) }

    if (half != null) {
        val lords = Upagrahas.portionLords(weekday, half.night)
        val length = (half.end - half.start) / Upagrahas.PORTIONS
        lords.forEachIndexed { i, lord ->
            val portion =
                DayPortion
                    .newBuilder()
                    .setIndex(i + 1)
                    .setStart(timestamp(half.start + i * length))
                    .setEnd(timestamp(if (i == lords.lastIndex) half.end else half.start + (i + 1) * length))
            lord?.let { portion.setLord(ProtoMapping.protoBody(it)) }
            result.addPortions(portion)
        }

        fun ascendantIn(
            lord: Int,
            point: Double,
        ): Double = angle(half.start + (lords.indexOf(lord) + point) * length, place, ascendant = true, frame = settings.frame)

        val kala = Upagrahas.KALA_LORDS.map { ascendantIn(it, settings.kalaPoint) }
        KALA_GROUP.zip(kala).forEach { (upagraha, longitude) -> result.addPositions(position(upagraha, longitude)) }
        val mandi = settings.mandiPoint?.let { ascendantIn(SweConstants.SE_SATURN, it) } ?: kala.last()
        result.addPositions(position(Upagraha.MANDI, mandi))
    }
    return result.build()
}

/**
 * Finds the last sunrise at or before [jd], then the sunset after it and, at night, the next sunrise. Null during
 * polar day or night, when one of them does not happen.
 */
private fun SweSession.dayHalf(
    jd: Double,
    place: Place,
    settings: UpagrahaSettings,
): DayHalf? {
    fun next(
        after: Double,
        mode: Int,
    ): Double? = riseTrans(after, SweConstants.SE_SUN, place, mode)

    // At high latitudes libswe only looks about a day ahead for a rise, so the first sunrise after a polar night can be
    // out of reach from the furthest start; nearer starts find it.
    var sunrise =
        SUNRISE_LOOKBACKS_DAYS.firstNotNullOfOrNull { lookback -> next(jd - lookback, settings.riseMode)?.takeIf { it <= jd } }
            ?: return null
    while (true) {
        val later = next(sunrise + MIN_RISE_GAP_DAYS, settings.riseMode)
        if (later == null || later > jd) break
        sunrise = later
    }
    val sunset = next(sunrise, settings.setMode) ?: return null
    if (jd < sunset) return DayHalf(sunrise, sunset, night = false, sunrise = sunrise)
    val nextSunrise = next(sunset, settings.riseMode)?.takeIf { it > jd } ?: return null
    return DayHalf(sunset, nextSunrise, night = true, sunrise = sunrise)
}

/** Mean positions (MODERN model) of the requested bodies at one instant, in [frame]. */
internal fun SweSession.meanPositions(
    timestamp: Timestamp,
    jd: Double,
    bodies: List<ProtoBody>,
    frame: Frame,
): InstantMeanPositions {
    val t = MeanElements.centuries(jd + deltaT(jd))
    // Mean longitudes refer to the mean equinox of date, so the sidereal shift is the ayanamsa without nutation.
    val ayanamsa = frame.siderealMode?.let { ayanamsa(jd, it, withNutation = false) } ?: 0.0

    fun inFrame(longitude: Double) = MeanElements.normalize(longitude - ayanamsa)

    val sun = MeanElements.sun(t)
    val result = InstantMeanPositions.newBuilder().setInstant(timestamp)
    for (body in bodies) {
        val (mean, sighrocca) =
            when (body) {
                ProtoBody.SUN -> sun to null
                ProtoBody.MOON -> MeanElements.moon(t) to null
                ProtoBody.MERCURY -> sun to MeanElements.heliocentric(Planet.MERCURY, t)
                ProtoBody.VENUS -> sun to MeanElements.heliocentric(Planet.VENUS, t)
                ProtoBody.MARS -> MeanElements.heliocentric(Planet.MARS, t) to sun
                ProtoBody.JUPITER -> MeanElements.heliocentric(Planet.JUPITER, t) to sun
                ProtoBody.SATURN -> MeanElements.heliocentric(Planet.SATURN, t) to sun
                else -> error("no mean position for $body") // the service validates the bodies first
            }
        val position = MeanPosition.newBuilder().setBody(body).setMeanLongitude(inFrame(mean))
        sighrocca?.let { position.setSighrocca(inFrame(it)) }
        result.addPositions(position)
    }
    return result.build()
}

private fun position(
    upagraha: Upagraha,
    longitude: Double,
): UpagrahaPosition =
    UpagrahaPosition
        .newBuilder()
        .setUpagraha(upagraha)
        .setLongitude(longitude)
        .build()

private fun timestamp(jd: Double): Timestamp = JulianDay.toTimestamp(jd)

private val DHUMA_GROUP = listOf(Upagraha.DHUMA, Upagraha.VYATIPATA, Upagraha.PARIVESHA, Upagraha.INDRACHAPA, Upagraha.UPAKETU)
private val KALA_GROUP = listOf(Upagraha.KALA, Upagraha.MRITYU, Upagraha.ARDHAPRAHARA, Upagraha.YAMAGHANTAKA, Upagraha.GULIKA)

/** Sunrises are about a day apart outside the polar regions; a longer gap means polar day or night. */
private val SUNRISE_LOOKBACKS_DAYS = listOf(1.5, 1.0, 0.5)

/** Skips past a sunrise so that the next search finds the following one. */
private const val MIN_RISE_GAP_DAYS = 0.25
