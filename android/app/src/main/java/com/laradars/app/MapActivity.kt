package com.laradars.app

import android.graphics.Color
import android.os.Bundle
import android.preference.PreferenceManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.laradars.app.databinding.ActivityMapBinding
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/** All camera segments on an OpenStreetMap map, plus the driver's position. */
class MapActivity : AppCompatActivity() {

    private lateinit var b: ActivityMapBinding
    private var me: Marker? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        Configuration.getInstance().userAgentValue = BuildConfig.APPLICATION_ID
        b = ActivityMapBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.back.setOnClickListener { finish() }

        val map = b.map
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)

        val units = Units(Prefs(this).metric)
        val cameras = CameraRepository.load(this)
        val live = ContextCompat.getColor(this, R.color.accent)
        val soon = ContextCompat.getColor(this, R.color.ring8)
        val focusId = intent.getStringExtra(EXTRA_CAMERA_ID)
        var focus: Camera? = null

        for (cam in cameras) {
            val line = Polyline(map).apply {
                setPoints(listOf(GeoPoint(cam.fromLat, cam.fromLng), GeoPoint(cam.toLat, cam.toLng)))
                outlinePaint.color = if (cam.isLive) live else soon
                outlinePaint.strokeWidth = 14f
                outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                title = cam.street
                snippet = getString(R.string.between, cam.from, cam.to) + "\n" +
                    getString(R.string.limit_label, units.limit(cam.speedLimitMph)) + " · " +
                    getString(if (cam.isLive) R.string.status_live else R.string.status_construction)
            }
            map.overlays.add(line)
            if (cam.reportLat != null && cam.reportLng != null) {
                map.overlays.add(Marker(map).apply {
                    position = GeoPoint(cam.reportLat, cam.reportLng)
                    title = cam.street
                    snippet = getString(R.string.report_saved)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                })
            }
            if (cam.id == focusId) focus = cam
        }

        val f = focus
        val s = TrackerState.current
        when {
            f != null -> map.controller.apply { setZoom(16.0); setCenter(GeoPoint(f.midLat, f.midLng)) }
            s.hasFix -> map.controller.apply { setZoom(14.0); setCenter(GeoPoint(s.lat, s.lng)) }
            else -> map.post {
                map.zoomToBoundingBox(BoundingBox(34.33, -118.16, 33.72, -118.68), false, 40)
            }
        }

        TrackerState.live.observe(this) { snap ->
            if (!snap.hasFix) return@observe
            val m = me ?: Marker(map).apply {
                icon = ContextCompat.getDrawable(this@MapActivity, R.drawable.ic_me)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setInfoWindow(null)
                map.overlays.add(this)
                me = this
            }
            m.position = GeoPoint(snap.lat, snap.lng)
            map.invalidate()
        }
    }

    override fun onResume() { super.onResume(); b.map.onResume() }
    override fun onPause() { super.onPause(); b.map.onPause() }

    companion object { const val EXTRA_CAMERA_ID = "camera_id" }
}
