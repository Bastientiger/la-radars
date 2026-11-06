package com.laradars.app

/**
 * Decides from cheap background fixes whether the phone is travelling like a car.
 * Pure logic, so it can be unit-tested without Android.
 */
object SpeedTrigger {

    /** 12 mph: faster than a bike ride in town, slower than any speed camera zone. */
    const val MIN_SPEED_MPS = 5.4
    /** A reported speed only counts from a tight fix (GPS), not from a cell-tower guess. */
    const val MAX_SPEED_FIX_ACCURACY_M = 50.0
    const val MIN_GAP_MS = 20_000L
    const val MAX_GAP_MS = 5 * 60_000L
    /** Minimum ground covered between two fixes before a "speed" is trusted. */
    const val MIN_DISTANCE_M = 500.0

    data class Fix(
        val lat: Double,
        val lng: Double,
        val timeMs: Long,
        val accuracyM: Double,
        /** null when the fix carries no speed (network location). */
        val speedMps: Double?,
    )

    fun isDriving(prev: Fix?, now: Fix): Boolean {
        if (now.speedMps != null && now.accuracyM <= MAX_SPEED_FIX_ACCURACY_M && now.speedMps >= MIN_SPEED_MPS) return true
        if (prev == null) return false
        val dt = now.timeMs - prev.timeMs
        if (dt < MIN_GAP_MS || dt > MAX_GAP_MS) return false
        val d = Geo.distance(prev.lat, prev.lng, now.lat, now.lng)
        // Both fixes may be off by their accuracy: the move must dwarf that error.
        if (d < MIN_DISTANCE_M || d < 2 * (prev.accuracyM + now.accuracyM)) return false
        return d / (dt / 1000.0) >= MIN_SPEED_MPS
    }
}
