package com.laradars.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreewayIndexTest {
    // A north-south freeway through the Beverly Blvd camera area, 1 km long.
    private val index = FreewayIndex.parse("""{"lines":[[34.0600,-118.2600,34.0690,-118.2600]]}""")

    @Test
    fun drivingAlongTheFreewayCounts() {
        assertTrue(index.isOnFreeway(34.0650, -118.2600, 0.0, 65))     // northbound, on the line
        assertTrue(index.isOnFreeway(34.0650, -118.26012, 180.0, 30)) // southbound, 11 m off, any speed
        assertTrue(index.isOnFreeway(34.0650, -118.26015, 0.0, 0))    // stuck in traffic, 14 m off, 0 mph
    }

    @Test
    fun aStreetRunningAlongTheFreewayOnlyCountsAtFreewaySpeed() {
        val lng = -118.2600 - 25.0 / (111_000 * 0.829)   // 25 m from the line
        assertFalse(index.isOnFreeway(34.0650, lng, 0.0, 35))
        assertTrue(index.isOnFreeway(34.0650, lng, 0.0, 60))
    }

    @Test
    fun crossingUnderTheFreewayDoesNot() {
        assertFalse(index.isOnFreeway(34.0650, -118.2600, 90.0, 70))  // perpendicular, even fast
    }

    @Test
    fun aParallelStreetFurtherAwayDoesNot() {
        assertFalse(index.isOnFreeway(34.0650, -118.2610, 0.0, 70))   // 92 m away
    }

    @Test
    fun unknownHeadingNeverMutes() {
        assertFalse(index.isOnFreeway(34.0650, -118.2600, null, 70))
    }

    @Test
    fun mutedEngineReportsNearestButNeverFires() {
        val cam = Camera("1-1", 1, "Beverly Blvd", "Belmont Ave", "Witmer St", 35, "Warning Live",
            34.063415, -118.263063, 34.063415, -118.259400)
        val engine = AlertEngine(listOf(cam), 400.0)
        val u = engine.update(34.063415 + 100.0 / 111_000, -118.26123, 180.0, 60, muted = true)
        assertTrue(u.alerts.isEmpty())
        assertTrue(u.nearest?.camera?.id == "1-1")
    }
}
