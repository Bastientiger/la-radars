package com.laradars.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import androidx.core.content.ContextCompat
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline

/**
 * The watermark map behind the rings on the main screen: greyed-out tiles, heading-up,
 * centred on the driver, with the nearest camera's segment and a label drawn on it.
 */
class HeroMap(private val context: Context, private val map: MapView) {

    private var segment: Polyline? = null
    private var label: LabelOverlay? = null
    private var shownCameraId: String? = null
    private var orientation = 0f

    init {
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(false)
        map.zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
        map.setOnTouchListener { _, _ -> true }   // a watermark, not a map to pan
        map.overlayManager.tilesOverlay.setColorFilter(ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }))
        map.overlayManager.tilesOverlay.loadingBackgroundColor = Color.TRANSPARENT
        map.overlayManager.tilesOverlay.loadingLineColor = Color.TRANSPARENT
        map.controller.setZoom(16.0)
        map.isHorizontalMapRepetitionEnabled = false
        map.isVerticalMapRepetitionEnabled = false
    }

    fun update(lat: Double, lng: Double, headingDeg: Double?, nearest: Camera?, distanceM: Double) {
        // Close by, the card already says everything: drop the label so it never covers the speed.
        label?.visible = distanceM > 150.0
        map.controller.setCenter(GeoPoint(lat, lng))
        if (headingDeg != null) {
            // Heading-up: the road ahead points to the top, like the arrow on the rings.
            // Smooth the rotation so GPS jitter does not make the map twitch.
            val target = (-headingDeg).toFloat()
            var delta = target - orientation
            while (delta > 180) delta -= 360
            while (delta < -180) delta += 360
            // Big change (first fix, U-turn): snap; small changes: glide.
            orientation += if (Math.abs(delta) > 60) delta else delta * 0.3f
            map.mapOrientation = orientation
        }
        if (nearest?.id != shownCameraId) {
            segment?.let { map.overlays.remove(it) }
            label?.let { map.overlays.remove(it) }
            segment = null; label = null
            shownCameraId = nearest?.id
            if (nearest != null) {
                segment = Polyline(map).apply {
                    setPoints(listOf(GeoPoint(nearest.fromLat, nearest.fromLng), GeoPoint(nearest.toLat, nearest.toLng)))
                    outlinePaint.color = ContextCompat.getColor(context, R.color.accent)
                    outlinePaint.strokeWidth = 11f * context.resources.displayMetrics.density
                    outlinePaint.strokeCap = Paint.Cap.ROUND
                    outlinePaint.alpha = 235
                    setOnClickListener { _, _, _ -> true }
                }
                map.overlays.add(segment)
                label = LabelOverlay(context, GeoPoint(nearest.midLat, nearest.midLng), "${nearest.street} · ${nearest.speedLimitMph} mph")
                label?.visible = distanceM > 150.0
                map.overlays.add(label)
            }
        }
        map.invalidate()
    }

    /** A small pin with a rounded dark label next to it, kept upright whatever the map rotation. */
    private class LabelOverlay(context: Context, private val point: GeoPoint, private val text: String) : Overlay() {
        private val d = context.resources.displayMetrics.density
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.text_on_dark)
            textSize = 11f * d
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.text) }
        private val pinFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.accent) }
        private val pinRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.bg); style = Paint.Style.STROKE; strokeWidth = 3f * d
        }
        private val px = Point()
        private val box = RectF()

        var visible = true

        override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
            if (shadow || !visible) return
            mapView.projection.toPixels(point, px)
            canvas.save()
            // Undo the map rotation around the pin so the label stays readable.
            canvas.rotate(-mapView.mapOrientation, px.x.toFloat(), px.y.toFloat())
            canvas.drawCircle(px.x.toFloat(), px.y.toFloat(), 7f * d, pinFill)
            canvas.drawCircle(px.x.toFloat(), px.y.toFloat(), 7f * d, pinRing)
            val w = textPaint.measureText(text)
            val left = px.x + 14f * d
            val top = px.y - 11f * d
            box.set(left, top, left + w + 16f * d, top + 22f * d)
            canvas.drawRoundRect(box, 11f * d, 11f * d, boxPaint)
            canvas.drawText(text, left + 8f * d, top + 15f * d, textPaint)
            canvas.restore()
        }
    }
}
