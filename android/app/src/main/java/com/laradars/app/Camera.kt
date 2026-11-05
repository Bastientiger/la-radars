package com.laradars.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.cos
import kotlin.math.sqrt

/** One LADOT speed safety camera: a street segment between two cross streets. */
data class Camera(
    val id: String,
    val district: Int,
    val street: String,
    val from: String,
    val to: String,
    val speedLimitMph: Int,
    val status: String,
    val fromLat: Double,
    val fromLng: Double,
    val toLat: Double,
    val toLng: Double,
    /** Exact spot reported by the user, if any. */
    val reportLat: Double? = null,
    val reportLng: Double? = null,
) {
    val isLive: Boolean get() = status.contains("Live", ignoreCase = true)
    val midLat: Double get() = (fromLat + toLat) / 2
    val midLng: Double get() = (fromLng + toLng) / 2

    /** Metres between a position and the camera: the reported spot if known, else the segment. */
    fun distanceTo(lat: Double, lng: Double): Double {
        val seg = Geo.distanceToSegment(lat, lng, fromLat, fromLng, toLat, toLng)
        if (reportLat == null || reportLng == null) return seg
        return minOf(seg, Geo.distance(lat, lng, reportLat, reportLng))
    }

    /** Point of the camera closest to the position (for bearing). */
    fun closestPoint(lat: Double, lng: Double): Pair<Double, Double> {
        if (reportLat != null && reportLng != null) return reportLat to reportLng
        return Geo.closestPointOnSegment(lat, lng, fromLat, fromLng, toLat, toLng)
    }
}

object Geo {
    private const val EARTH_RADIUS_M = 6371000.0

    fun distance(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val cosLat = cos(Math.toRadians((aLat + bLat) / 2))
        val x = Math.toRadians(bLng - aLng) * cosLat * EARTH_RADIUS_M
        val y = Math.toRadians(bLat - aLat) * EARTH_RADIUS_M
        return sqrt(x * x + y * y)
    }

    /** Distance in metres from point P to segment AB (local flat projection). */
    fun distanceToSegment(
        pLat: Double, pLng: Double,
        aLat: Double, aLng: Double,
        bLat: Double, bLng: Double,
    ): Double {
        val (cLat, cLng) = closestPointOnSegment(pLat, pLng, aLat, aLng, bLat, bLng)
        return distance(pLat, pLng, cLat, cLng)
    }

    fun closestPointOnSegment(
        pLat: Double, pLng: Double,
        aLat: Double, aLng: Double,
        bLat: Double, bLng: Double,
    ): Pair<Double, Double> {
        val cosLat = cos(Math.toRadians(pLat))
        fun x(lng: Double) = Math.toRadians(lng - pLng) * cosLat * EARTH_RADIUS_M
        fun y(lat: Double) = Math.toRadians(lat - pLat) * EARTH_RADIUS_M
        val ax = x(aLng); val ay = y(aLat)
        val bx = x(bLng); val by = y(bLat)
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((-ax) * dx + (-ay) * dy) / len2).coerceIn(0.0, 1.0)
        return (aLat + t * (bLat - aLat)) to (aLng + t * (bLng - aLng))
    }

    /** Compass bearing in degrees from A to B. */
    fun bearing(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val dLon = Math.toRadians(bLng - aLng)
        val y = Math.sin(dLon) * Math.cos(Math.toRadians(bLat))
        val x = Math.cos(Math.toRadians(aLat)) * Math.sin(Math.toRadians(bLat)) -
            Math.sin(Math.toRadians(aLat)) * Math.cos(Math.toRadians(bLat)) * Math.cos(dLon)
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360
    }

    /** Mean of two bearings on the circle (so 350 and 10 give 0, not 180). */
    fun meanBearing(a: Double, b: Double): Double {
        val x = Math.cos(Math.toRadians(a)) + Math.cos(Math.toRadians(b))
        val y = Math.sin(Math.toRadians(a)) + Math.sin(Math.toRadians(b))
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360
    }

    /** Smallest angle between two bearings, 0..180. */
    fun angleDiff(a: Double, b: Double): Double {
        val d = Math.abs(a - b) % 360
        return if (d > 180) 360 - d else d
    }
}

object CameraRepository {
    @Volatile private var cache: List<Camera>? = null

    fun invalidate() { cache = null }

    /** Downloaded data wins over the copy bundled in the app. */
    fun dataFile(context: Context) = File(context.filesDir, "cameras.json")

    fun load(context: Context): List<Camera> {
        cache?.let { return it }
        val file = dataFile(context)
        var parsed: List<Camera>? = null
        if (file.exists()) {
            // A truncated or corrupt download must never keep the app from starting.
            parsed = runCatching { parse(file.readText()) }.getOrNull()?.takeIf { it.size >= 50 }
            if (parsed == null) {
                file.delete()
                Prefs(context).dataVersion = ""
            }
        }
        if (parsed == null) {
            parsed = parse(context.assets.open("cameras.json").bufferedReader().use { it.readText() })
        }
        val reports = Prefs(context).reports()
        val list = parsed.map { c ->
            reports[c.id]?.let { r -> c.copy(reportLat = r[0], reportLng = r[1]) } ?: c
        }
        cache = list
        return list
    }

    fun parse(json: String): List<Camera> {
        val arr = JSONArray(json)
        val out = ArrayList<Camera>(arr.length())
        for (i in 0 until arr.length()) {
            val o: JSONObject = arr.getJSONObject(i)
            val f = o.optJSONArray("from_coord") ?: continue
            val t = o.optJSONArray("to_coord") ?: continue
            out += Camera(
                id = o.getString("id"),
                district = o.getInt("district"),
                street = o.getString("street"),
                from = o.getString("from"),
                to = o.getString("to"),
                speedLimitMph = o.getInt("speed_limit"),
                status = o.getString("status"),
                fromLat = f.getDouble(0), fromLng = f.getDouble(1),
                toLat = t.getDouble(0), toLng = t.getDouble(1),
            )
        }
        return out
    }
}
