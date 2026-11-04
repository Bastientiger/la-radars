package com.laradars.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Translucent teal rings that breathe slowly, like a radar at a few beats per minute, drawn
 * over the live map. Their size is a distance gauge: wide when the nearest camera is far,
 * tight around the driver when it is close. A faint ripple travels outwards once per beat.
 */
class PulseView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val colors = intArrayOf(
        R.color.ring0, R.color.ring1, R.color.ring2, R.color.ring3, R.color.ring4, R.color.ring5,
        R.color.ring6, R.color.ring7, R.color.ring8, R.color.ring9, R.color.ring10, R.color.ring11,
    ).map { ContextCompat.getColor(context, it) }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ripple = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
        color = ContextCompat.getColor(context, R.color.live)
    }
    private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.text) }
    private val arrowPath = Path()

    private var phase = 0f
    private var scale = 1f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        duration = 6000
        addUpdateListener {
            phase = it.animatedValue as Float
            // Ease the gauge towards its target so distance changes glide instead of jumping.
            scale += (targetScale - scale) * 0.08f
            invalidate()
        }
    }

    /** Beat length in ms (6000 = 10 beats per minute). */
    var periodMs: Long = 6000
        set(value) {
            if (field == value) return
            field = value
            val keep = phase
            animator.duration = value
            if (animator.isRunning) animator.setCurrentFraction(keep)
        }

    /** 0 = still, 1 = full breathing amplitude. */
    var intensity = 0.5f

    /** Ring size as a fraction of the view: [MIN_SCALE] when on the camera, [MAX_SCALE] when far. */
    var targetScale = MAX_SCALE

    /** Show the direction-of-travel arrow on the outer ring. */
    var showArrow = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val outer = min(width, height) / 2f * scale
        val n = colors.size
        val wave = 2.0 * PI * phase
        for (i in 0 until n) {
            val base = outer * (n - i) / n
            val breath = sin(wave - i * 0.45).toFloat()
            fill.color = colors[i]
            // See-through everywhere: 10 % at the edge, 50 % at the core, so the map shows through.
            fill.alpha = (255 * min(0.5f, 0.10f + i * 0.036f)).toInt()
            canvas.drawCircle(cx, cy, base * (1f + 0.035f * intensity * breath), fill)
        }
        val t = phase
        ripple.alpha = ((1f - t) * 110f * intensity).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, outer * (0.15f + 0.85f * t), ripple)

        if (showArrow) {
            val d = resources.displayMetrics.density
            val tip = cy - outer - 2 * d
            arrowPath.reset()
            arrowPath.moveTo(cx, tip)
            arrowPath.lineTo(cx + 9 * d, tip + 13 * d)
            arrowPath.lineTo(cx, tip + 9 * d)
            arrowPath.lineTo(cx - 9 * d, tip + 13 * d)
            arrowPath.close()
            arrow.alpha = 210
            canvas.drawPath(arrowPath, arrow)
        }
    }

    companion object {
        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 1.18f

        /** Gauge: tight at 0 m, fully open from about one mile away. */
        fun scaleForDistance(m: Double): Float =
            (MIN_SCALE + (MAX_SCALE - MIN_SCALE) * (m / 1600.0).coerceIn(0.0, 1.0)).toFloat()
    }
}
