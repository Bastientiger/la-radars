package com.laradars.app

import com.laradars.app.SpeedTrigger.Fix
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedTriggerTest {

    // ~1 km north of (34.05, -118.25) is about 0.009 degrees of latitude.
    private val home = Fix(34.05, -118.25, 0L, 40.0, null)

    @Test
    fun gpsFixWithCarSpeedStarts() {
        assertTrue(SpeedTrigger.isDriving(null, Fix(34.05, -118.25, 0L, 10.0, speedMps = 15.0)))
    }

    @Test
    fun gpsFixAtWalkingSpeedDoesNot() {
        assertFalse(SpeedTrigger.isDriving(null, Fix(34.05, -118.25, 0L, 10.0, speedMps = 1.5)))
    }

    @Test
    fun speedFromLooseFixIsIgnored() {
        assertFalse(SpeedTrigger.isDriving(null, Fix(34.05, -118.25, 0L, 300.0, speedMps = 15.0)))
    }

    @Test
    fun oneKilometreInTwoMinutesStarts() {
        val later = Fix(34.059, -118.25, 120_000L, 40.0, null)
        assertTrue(SpeedTrigger.isDriving(home, later))
    }

    @Test
    fun oneKilometreInTenMinutesIsTooSlow() {
        val later = Fix(34.059, -118.25, 600_000L, 40.0, null)
        assertFalse(SpeedTrigger.isDriving(home, later))
    }

    @Test
    fun jumpWithinAccuracyNoiseIsIgnored() {
        val loose = Fix(34.05, -118.25, 0L, 400.0, null)
        val later = Fix(34.059, -118.25, 120_000L, 400.0, null)
        assertFalse(SpeedTrigger.isDriving(loose, later))
    }

    @Test
    fun gapTooShortOrTooLongIsIgnored() {
        assertFalse(SpeedTrigger.isDriving(home, Fix(34.059, -118.25, 5_000L, 40.0, null)))
        assertFalse(SpeedTrigger.isDriving(home, Fix(34.2, -118.25, 20 * 60_000L, 40.0, null)))
    }
}
