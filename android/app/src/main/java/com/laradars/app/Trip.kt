package com.laradars.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** What happened in one camera zone during a trip. */
data class ZonePass(val cameraId: String, val street: String, val limitMph: Int, val maxSpeedMph: Int)

data class Trip(
    val startMs: Long,
    val endMs: Long,
    val distanceM: Double,
    val maxSpeedMph: Int,
    val zones: List<ZonePass>,
) {
    val durationMin: Int get() = ((endMs - startMs) / 60000).toInt()
    val speedingZones: Int get() = zones.count { it.maxSpeedMph > it.limitMph + AlertEngine.SPEEDING_OVER_MPH }

    fun toJson(): JSONObject = JSONObject().apply {
        put("start", startMs); put("end", endMs); put("distance", distanceM); put("maxSpeed", maxSpeedMph)
        put("zones", JSONArray(zones.map {
            JSONObject().put("id", it.cameraId).put("street", it.street).put("limit", it.limitMph).put("max", it.maxSpeedMph)
        }))
    }

    companion object {
        fun fromJson(o: JSONObject): Trip {
            val z = o.getJSONArray("zones")
            return Trip(
                o.getLong("start"), o.getLong("end"), o.getDouble("distance"), o.getInt("maxSpeed"),
                (0 until z.length()).map { i ->
                    val p = z.getJSONObject(i)
                    ZonePass(p.getString("id"), p.getString("street"), p.getInt("limit"), p.getInt("max"))
                },
            )
        }
    }
}

/** Accumulates one trip while the service runs. */
class TripRecorder(private val startMs: Long = System.currentTimeMillis()) {
    private var distanceM = 0.0
    private var maxSpeedMph = 0
    private var lastLat: Double? = null
    private var lastLng: Double? = null
    private val zoneMax = LinkedHashMap<String, ZonePass>()

    fun onFix(lat: Double, lng: Double, speedMph: Int, insideOf: Camera?) {
        val pl = lastLat; val pg = lastLng
        if (pl != null && pg != null) {
            val step = Geo.distance(pl, pg, lat, lng)
            if (step < 500) distanceM += step  // ignore GPS jumps
        }
        lastLat = lat; lastLng = lng
        if (speedMph > maxSpeedMph) maxSpeedMph = speedMph
        if (insideOf != null) {
            val prev = zoneMax[insideOf.id]
            if (prev == null || speedMph > prev.maxSpeedMph) {
                zoneMax[insideOf.id] = ZonePass(insideOf.id, insideOf.street, insideOf.speedLimitMph, speedMph)
            }
        }
    }

    fun finish(): Trip = Trip(startMs, System.currentTimeMillis(), distanceM, maxSpeedMph, zoneMax.values.toList())
}

object TripStore {
    private const val FILE = "trips"
    private const val KEY = "list"
    private const val MAX = 50

    fun all(context: Context): List<Trip> {
        val arr = JSONArray(context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]")
        return (0 until arr.length()).map { Trip.fromJson(arr.getJSONObject(it)) }.reversed()
    }

    fun add(context: Context, trip: Trip) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString(KEY, "[]") ?: "[]")
        arr.put(trip.toJson())
        while (arr.length() > MAX) arr.remove(0)
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun clear(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().clear().apply()
}
