package com.laradars.app

import java.util.Locale
import kotlin.math.roundToInt

/** Formats distances and speeds in US units (feet, miles, mph) or metric (m, km, km/h). */
class Units(val metric: Boolean) {

    // Always a dot as decimal separator: the voice and the UI are in English.
    private fun fmt(pattern: String, value: Double) = String.format(Locale.US, pattern, value)

    fun speed(mps: Float): Int = if (metric) (mps * 3.6f).roundToInt() else (mps * 2.23694f).roundToInt()

    val speedUnit: String get() = if (metric) "km/h" else "mph"

    /** Posted limits are in mph; metric users also see the km/h equivalent. */
    fun limit(mph: Int): String = if (metric) "$mph mph · ${(mph * 1.609344).roundToInt()} km/h" else "$mph mph"

    /** Short display, e.g. "350 m", "1.2 km", "900 ft", "0.6 mi". */
    fun distance(m: Double): String = if (metric) {
        if (m >= 1000) fmt("%.1f km", m / 1000) else "${m.roundToInt()} m"
    } else {
        val ft = m * 3.28084
        if (ft >= 1000) fmt("%.1f mi", m / 1609.344) else "${ft.roundToInt()} ft"
    }

    /** Rounded display for alerts: 50 m / 100 ft steps. */
    fun distanceRounded(m: Double): String = if (metric) {
        val r = ((m / 50).roundToInt() * 50).coerceAtLeast(50)
        if (r >= 1000) fmt("%.1f km", r / 1000.0) else "$r m"
    } else {
        val ft = ((m * 3.28084 / 100).roundToInt() * 100).coerceAtLeast(100)
        if (ft >= 1000) fmt("%.1f mi", ft / 5280.0) else "$ft ft"
    }

    /** Live countdown for the banner: 10 m / 50 ft steps. */
    fun distanceLive(m: Double): String = if (metric) {
        val r = ((m / 10).roundToInt() * 10).coerceAtLeast(0)
        if (r >= 1000) fmt("%.1f km", r / 1000.0) else "$r m"
    } else {
        val ft = ((m * 3.28084 / 50).roundToInt() * 50).coerceAtLeast(0)
        if (ft >= 1000) fmt("%.1f mi", ft / 5280.0) else "$ft ft"
    }

    /** Spoken form, e.g. "350 metres", "a quarter mile". */
    fun spokenDistance(m: Double): String = if (metric) {
        val r = ((m / 50).roundToInt() * 50).coerceAtLeast(50)
        if (r >= 1000) fmt("%.1f kilometres", r / 1000.0) else "$r metres"
    } else {
        val ft = m * 3.28084
        when {
            ft < 1000 -> "${((ft / 100).roundToInt() * 100).coerceAtLeast(100)} feet"
            m < 500 -> "a quarter mile"
            m < 900 -> "half a mile"
            else -> fmt("%.1f miles", m / 1609.344)
        }
    }

    /** Radius slider label. */
    fun radius(m: Int): String = if (metric) "$m m" else "${((m * 3.28084) / 50).roundToInt() * 50} ft"
}
