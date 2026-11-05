package com.laradars.app

/**
 * Decides what to say as the driver approaches a camera.
 *
 * For each camera the engine goes through stages: APPROACH when the driver comes within
 * [radiusM] while heading towards it, HALFWAY at half the radius (never under 100 m),
 * ENTER when on the segment, PASSED once clear of it. A camera that was not fired is
 * re-armed after the driver is radiusM + margin away. Speeding inside the zone is reported
 * on every update so the service can beep until the driver slows down.
 */
class AlertEngine(
    private val cameras: List<Camera>,
    @Volatile var radiusM: Double,
    @Volatile var onlyLive: Boolean = false,
    private val rearmMarginM: Double = 150.0,
) {
    enum class Event { APPROACH, HALFWAY, ENTER, PASSED }

    data class Nearest(val camera: Camera, val distanceM: Double)
    data class Alert(val camera: Camera, val distanceM: Double, val event: Event)
    data class Update(val nearest: Nearest?, val alerts: List<Alert>, val speedingIn: Camera?)

    private enum class Stage { ARMED, APPROACHED, HALFWAY, INSIDE, PASSED }
    private val stage = HashMap<String, Stage>()

    /** Metres from the segment within which the driver is "on" it (GPS and road width). */
    private val insideM = 45.0

    /**
     * @param headingDeg direction of travel, or null when unknown (stationary / first fix).
     * @param speedMph current speed, used only for the speeding flag.
     */
    fun update(lat: Double, lng: Double, headingDeg: Double?, speedMph: Int): Update {
        var nearest: Nearest? = null
        val alerts = ArrayList<Alert>()
        var speedingIn: Camera? = null
        val halfway = (radiusM / 2).coerceAtLeast(100.0)

        for (cam in cameras) {
            if (onlyLive && !cam.isLive) continue
            val d = cam.distanceTo(lat, lng)
            if (nearest == null || d < nearest.distanceM) nearest = Nearest(cam, d)

            val s = stage[cam.id] ?: Stage.ARMED
            val inside = d <= insideM
            val towards = inside || headingDeg == null && d <= halfway || isHeadingTowards(cam, lat, lng, d, headingDeg)

            when (s) {
                Stage.ARMED -> if (d <= radiusM && towards) {
                    stage[cam.id] = Stage.APPROACHED
                    alerts += Alert(cam, d, Event.APPROACH)
                } else if (d > radiusM + rearmMarginM) {
                    stage.remove(cam.id)
                }
                Stage.APPROACHED -> if (inside) {
                    stage[cam.id] = Stage.INSIDE
                    alerts += Alert(cam, d, Event.ENTER)
                } else if (d <= halfway && radiusM > halfway + 50) {
                    stage[cam.id] = Stage.HALFWAY
                    alerts += Alert(cam, d, Event.HALFWAY)
                } else if (d > radiusM + rearmMarginM) {
                    stage[cam.id] = Stage.ARMED
                }
                Stage.HALFWAY -> if (inside) {
                    stage[cam.id] = Stage.INSIDE
                    alerts += Alert(cam, d, Event.ENTER)
                } else if (d > radiusM + rearmMarginM) {
                    stage[cam.id] = Stage.ARMED
                }
                Stage.INSIDE -> if (!inside && d > insideM + 60) {
                    stage[cam.id] = Stage.PASSED
                    alerts += Alert(cam, d, Event.PASSED)
                }
                Stage.PASSED -> if (d > radiusM + rearmMarginM) stage[cam.id] = Stage.ARMED
            }
            if ((stage[cam.id] == Stage.INSIDE) && speedMph > cam.speedLimitMph + SPEEDING_OVER_MPH) {
                speedingIn = cam
            }
        }
        return Update(nearest, alerts, speedingIn)
    }

    /**
     * "Heading towards" means that, driving straight on, the driver would pass within
     * [LATERAL_M] of the camera. A parallel street 250 m away never qualifies; a street that
     * leads onto the segment does, however far away it still is.
     */
    private fun isHeadingTowards(cam: Camera, lat: Double, lng: Double, d: Double, headingDeg: Double?): Boolean {
        if (headingDeg == null) return false
        val (cLat, cLng) = cam.closestPoint(lat, lng)
        val toCam = Geo.bearing(lat, lng, cLat, cLng)
        val theta = Geo.angleDiff(headingDeg, toCam)
        if (theta > 90) return false
        return d * Math.sin(Math.toRadians(theta)) <= LATERAL_M
    }

    /** True from the first alert for this camera until it is passed. */
    fun isAlerting(cameraId: String): Boolean =
        stage[cameraId].let { it == Stage.APPROACHED || it == Stage.HALFWAY || it == Stage.INSIDE }

    fun reset() = stage.clear()

    companion object {
        /** Cameras ticket at 11 mph over the limit. */
        const val SPEEDING_OVER_MPH = 10
        /** The straight-on path must pass this close to the camera to count as approaching. */
        const val LATERAL_M = 150.0
    }
}
