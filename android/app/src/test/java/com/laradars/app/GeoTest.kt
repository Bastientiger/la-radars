package com.laradars.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {
    // An exactly east-west segment of about 340 m near Beverly Blvd & Belmont Ave.
    private val aLat = 34.063415; private val aLng = -118.263063
    private val bLat = 34.063415; private val bLng = -118.259400

    @Test
    fun pointOnSegmentIsAtZeroDistance() {
        val d = Geo.distanceToSegment(aLat, aLng, aLat, aLng, bLat, bLng)
        assertEquals(0.0, d, 0.5)
    }

    @Test
    fun pointOneBlockNorthIsAboutOneBlockAway() {
        // 0.0009 degrees of latitude is about 100 m.
        val midLat = (aLat + bLat) / 2 + 0.0009
        val midLng = (aLng + bLng) / 2
        val d = Geo.distanceToSegment(midLat, midLng, aLat, aLng, bLat, bLng)
        assertEquals(100.0, d, 3.0)
    }

    @Test
    fun pointBeyondTheEndMeasuresToTheEndpoint() {
        // 0.01 degrees of longitude east of B at this latitude is about 920 m.
        val d = Geo.distanceToSegment(bLat, bLng + 0.01, aLat, aLng, bLat, bLng)
        assertTrue("got $d", d > 900 && d < 940)
    }
}
