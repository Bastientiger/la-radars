package com.laradars.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * The alert bar drawn over whatever app is on screen (Google Maps, Waze…) while a camera is
 * ahead: the distance in huge type, the street in small, amber on approach and red when close
 * or speeding. Needs the "display over other apps" permission; a tap hides it for this camera.
 */
class AlertBanner(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: View? = null
    private var dismissedFor: String? = null
    private var danger: Boolean? = null

    fun show(cameraId: String, distance: String, street: String, danger: Boolean) {
        if (!canDraw(context)) return
        if (dismissedFor == cameraId) return
        val v = view ?: inflate(cameraId)
        if (this.danger != danger) {
            this.danger = danger
            val bg = ContextCompat.getColor(context, if (danger) R.color.warn else R.color.amber)
            val fg = ContextCompat.getColor(context, if (danger) R.color.text_on_dark else R.color.text)
            v.findViewById<View>(R.id.bannerRoot).setBackgroundColor(bg)
            v.findViewById<TextView>(R.id.bannerDistance).setTextColor(fg)
            v.findViewById<TextView>(R.id.bannerStreet).setTextColor(fg)
            v.findViewById<RingsWatermarkView>(R.id.bannerRings).color = fg
        }
        v.findViewById<TextView>(R.id.bannerDistance).text = distance
        v.findViewById<TextView>(R.id.bannerStreet).text = street
    }

    fun hide() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        danger = null
    }

    /** Called when the camera is passed or the alert re-arms: the next camera gets a fresh banner. */
    fun reset() {
        hide()
        dismissedFor = null
    }

    private fun inflate(cameraId: String): View {
        val v = LayoutInflater.from(context).inflate(R.layout.banner_alert, null)
        v.setOnClickListener { dismissedFor = cameraId; hide() }
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        // The window, not the layout file, decides the size of an overlay: a fixed, thin bar.
        val heightPx = (BANNER_HEIGHT_DP * context.resources.displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, heightPx, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP }
        wm.addView(v, params)
        view = v
        return v
    }

    companion object {
        const val BANNER_HEIGHT_DP = 124
        fun canDraw(context: Context): Boolean = Settings.canDrawOverlays(context)
    }
}

/** Faded concentric rings on both ends of the banner, echoing the app's radar. */
class RingsWatermarkView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    var color: Int = 0
        set(value) { field = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val r = 75f * d
        val cy = height / 2f - 8f * d
        for (cx in floatArrayOf(-20f * d, width + 20f * d)) {
            for (i in 0 until 7) {
                paint.color = color
                paint.alpha = (255 * 0.16f * (0.25f + i * 0.1f)).toInt()
                canvas.drawCircle(cx, cy, r - i * 10f * d, paint)
            }
        }
    }
}
