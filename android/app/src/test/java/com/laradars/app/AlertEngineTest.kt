package com.laradars.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertEngineTest {
    // An exactly east-west segment: Beverly Blvd between Belmont Ave and Witmer St.
    private val cam = Camera(
        id = "1-1", district = 1, street = "Beverly Blvd", from = "Belmont Ave", to = "Witmer St",
        speedLimitMph = 35, status = "Warning Live",
        fromLat = 34.063415, fromLng = -118.263063, toLat = 34.063415, toLng = -118.259400,
    )
    private val engine = AlertEngine(listOf(cam), radiusM = 400.0, rearmMarginM = 150.0)
    private val midLng = -118.26123

    // Latitude offsets: 0.001 degrees is about 111 m. Heading 180 = driving south, towards the segment.
    private fun at(metersNorth: Double, heading: Double? = 180.0, speed: Int = 30) =
        engine.update(34.063415 + metersNorth / 111_000, midLng, heading, speed)

    private fun events(u: AlertEngine.Update) = u.alerts.map { it.event }

    @Test
    fun approachesThenEntersThenPasses() {
        assertTrue(at(1000.0).alerts.isEmpty())
        assertEquals(listOf(AlertEngine.Event.APPROACH), events(at(350.0)))
        assertEquals(listOf(AlertEngine.Event.HALFWAY), events(at(190.0)))
        assertTrue(at(120.0).alerts.isEmpty())
        assertEquals(listOf(AlertEngine.Event.ENTER), events(at(10.0)))
        assertTrue(at(-30.0).alerts.isEmpty())             // still inside, heading away now
        assertEquals(listOf(AlertEngine.Event.PASSED), events(at(-150.0, heading = 180.0)))
        assertTrue(at(-300.0).alerts.isEmpty())
    }

    @Test
    fun drivingAwayNeverAlerts() {
        at(1000.0, heading = 0.0)
        assertTrue("heading north, away from the camera", at(350.0, heading = 0.0).alerts.isEmpty())
        assertTrue(at(150.0, heading = 0.0).alerts.isEmpty())
    }

    @Test
    fun parallelStreetNeverAlerts() {
        // Driving east on a street 250 m north of the segment: never heading towards it.
        for (i in 0..10) {
            val u = engine.update(34.063415 + 250.0 / 111_000, -118.2650 + i * 0.0007, 90.0, 30)
            assertTrue("step $i", u.alerts.isEmpty())
        }
    }

    @Test
    fun unknownHeadingAlertsOnlyWhenClose() {
        assertTrue(at(350.0, heading = null).alerts.isEmpty())
        assertEquals(listOf(AlertEngine.Event.APPROACH), events(at(150.0, heading = null)))
    }

    @Test
    fun firesOnceAndRearmsAfterLeaving() {
        at(350.0)
        assertTrue(at(300.0).alerts.isEmpty())
        at(450.0)                                   // just outside: not re-armed yet
        assertTrue(at(350.0).alerts.isEmpty())
        at(700.0)                                   // well outside: re-armed
        assertEquals(listOf(AlertEngine.Event.APPROACH), events(at(350.0)))
    }

    @Test
    fun speedingIsReportedOnlyInsideTheZone() {
        assertNull(at(350.0, speed = 50).speedingIn)
        at(10.0, speed = 50)
        assertEquals("1-1", at(5.0, speed = 50).speedingIn?.id)
        assertNull(at(5.0, speed = 40).speedingIn)   // 40 is within the 10 mph tolerance
    }

    @Test
    fun onlyLiveSkipsConstructionCameras() {
        val soon = cam.copy(id = "9-9", status = "Construction")
        val e = AlertEngine(listOf(soon), 400.0, onlyLive = true)
        val u = e.update(34.063415 + 300.0 / 111_000, midLng, 180.0, 30)
        assertTrue(u.alerts.isEmpty())
        assertNull(u.nearest)
    }

    @Test
    fun reportedSpotIsUsedForDistance() {
        // The user pinned the camera 300 m east of the segment's end.
        val pinned = cam.copy(reportLat = 34.063415, reportLng = -118.259400 + 300.0 / (111_000 * 0.829))
        val d = pinned.distanceTo(34.063415, -118.259400 + 320.0 / (111_000 * 0.829))
        assertTrue("got $d", d < 40)
    }

    @Test
    fun parsesTheCameraJson() {
        val json = """[{"id":"1-1","district":1,"street":"Beverly Blvd","from":"Belmont Ave","to":"Witmer St",
            "speed_limit":35,"status":"Warning Live","from_coord":[34.063415,-118.263063],"to_coord":[34.0643,-118.2594]},
            {"id":"9-9","district":9,"street":"X","from":"A","to":"B","speed_limit":35,"status":"Construction","from_coord":null,"to_coord":null}]"""
        val cams = CameraRepository.parse(json)
        assertEquals("a camera without coordinates is skipped", 1, cams.size)
        assertTrue(cams[0].isLive)
    }
}
