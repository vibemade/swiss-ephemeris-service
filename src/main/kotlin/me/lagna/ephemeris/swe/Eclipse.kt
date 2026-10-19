// SPDX-License-Identifier: AGPL-3.0-or-later
package me.lagna.ephemeris.swe

enum class EclipseType { TOTAL, ANNULAR, HYBRID, PARTIAL, PENUMBRAL }

/**
 * One solar or lunar eclipse, global or as seen from a place; instants are Julian days (UT). Local contacts are
 * limited to the time the eclipsed body is above the horizon (see [SweSession.nextSolarEclipse]).
 */
data class EclipseEvent(
    val type: EclipseType,
    val maximum: Double,
    /** Solar: NASA magnitude. Lunar: umbral magnitude, or penumbral magnitude for a penumbral eclipse. */
    val magnitude: Double,
    val begin: Double?,
    val end: Double?,
    val totalityBegin: Double?,
    val totalityEnd: Double?,
)
