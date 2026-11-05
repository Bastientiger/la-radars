package com.laradars.app

import android.content.Context
import kotlin.math.floor

/**
 * Knows where the freeways are, so alerts can be muted while driving on one: none of the
 * cameras is on a freeway, but several sit on streets that pass right under or next to one.
 *
 * The driver is "on a freeway" when within [NEAR_M] of a freeway centreline AND travelling
 * along it (heading within [ALIGN_DEG] of the line, either way). Crossing a freeway on a
 * surface street is perpendicular, so it never counts.
 */
class FreewayIndex(private val segments: List<Seg>) {

    class Seg(val aLat: Double, val aLng: Double, val bLat: Double, val bLng: Double) {
        val bearing = Geo.bearing(aLat, aLng, bLat, bLng)
    }

    // Grid of ~1 km cells for fast lookup.
    private val cell = 0.01
    private val grid = HashMap<Long, MutableList<Int>>()

    init {
        segments.forEachIndexed { i, s ->
            val minLat = minOf(s.aLat, s.bLat); val maxLat = maxOf(s.aLat, s.bLat)
            val minLng = minOf(s.aLng, s.bLng); val maxLng = maxOf(s.aLng, s.bLng)
            var la = floor(minLat / cell).toLong()
            while (la <= floor(maxLat / cell).toLong()) {
                var lo = floor(minLng / cell).toLong()
                while (lo <= floor(maxLng / cell).toLong()) {
                    grid.getOrPut(key(la, lo)) { ArrayList() }.add(i)
                    lo++
                }
                la++
            }
        }
    }

    private fun key(la: Long, lo: Long) = la * 1_000_000L + lo

    /**
     * Some camera streets run right along a freeway (Riverside Dr is 20 m from the 2), so
     * distance alone is not enough: very close to the line counts always; a little further
     * out it only counts at freeway speed.
     */
    fun isOnFreeway(lat: Double, lng: Double, headingDeg: Double?, speedMph: Int): Boolean {
        if (headingDeg == null) return false
        var closest = Double.MAX_VALUE
        val la = floor(lat / cell).toLong(); val lo = floor(lng / cell).toLong()
        for (dla in -1L..1L) for (dlo in -1L..1L) {
            val list = grid[key(la + dla, lo + dlo)] ?: continue
            for (i in list) {
                val s = segments[i]
                val diff = Geo.angleDiff(headingDeg, s.bearing)
                if (diff > ALIGN_DEG && diff < 180 - ALIGN_DEG) continue
                val d = Geo.distanceToSegment(lat, lng, s.aLat, s.aLng, s.bLat, s.bLng)
                if (d < closest) closest = d
            }
        }
        lastClosestM = closest
        return closest <= ON_LINE_M || (closest <= NEAR_M && speedMph >= FREEWAY_SPEED_MPH)
    }

    /** Distance to the nearest aligned freeway line at the last check (diagnostics). */
    @Volatile var lastClosestM = Double.MAX_VALUE
        private set

    companion object {
        const val ON_LINE_M = 15.0
        const val NEAR_M = 35.0
        const val FREEWAY_SPEED_MPH = 50
        const val ALIGN_DEG = 30.0

        @Volatile private var cached: FreewayIndex? = null

        /** Parsed once per process; later trips reuse it instantly. */
        fun load(context: Context): FreewayIndex {
            cached?.let { return it }
            val t0 = System.currentTimeMillis()
            val idx = parse(context.assets.open("freeways.json").bufferedReader().use { it.readText() })
            android.util.Log.i("LARadars", "freeways loaded: ${idx.segments.size} segments in ${System.currentTimeMillis() - t0} ms")
            cached = idx
            return idx
        }

        /** Hand-rolled number scan: ten times faster than org.json on 20 000 values. */
        fun parse(json: String): FreewayIndex {
            val start = json.indexOf("\"lines\"")
            val segs = ArrayList<Seg>(25_000)
            var i = json.indexOf('[', start) + 1
            val n = json.length
            val line = ArrayList<Double>(64)
            while (i < n) {
                val c = json[i]
                when {
                    c == '[' -> line.clear()
                    c == ']' -> {
                        var k = 0
                        while (k + 3 < line.size) {
                            segs += Seg(line[k], line[k + 1], line[k + 2], line[k + 3]); k += 2
                        }
                        line.clear()
                        if (i + 1 < n && json[i + 1] == '}') break
                    }
                    c == '-' || c.isDigit() -> {
                        var j = i + 1
                        while (j < n && (json[j].isDigit() || json[j] == '.')) j++
                        line += json.substring(i, j).toDouble()
                        i = j; continue
                    }
                }
                i++
            }
            return FreewayIndex(segs)
        }
    }
}
