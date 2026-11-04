package com.laradars.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Concentric teal rings that breathe slowly, like a radar at a few beats per minute.
 * A faint ripple travels from the centre to the edge once per beat.
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
        color = ContextCompat.getColor(context, R.color.text_on_dark)
    }

    private var phase = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        duration = 6000
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
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
        val outer = min(width, height) / 2f
        val n = colors.size
        val wave = 2.0 * PI * phase
        for (i in 0 until n) {
            val base = outer * (n - i) / n
            val breath = sin(wave - i * 0.45).toFloat()
            fill.color = colors[i]
            canvas.drawCircle(cx, cy, base * (1f + 0.035f * intensity * breath), fill)
        }
        // Ripple: born at the centre, fades as it reaches the edge.
        val t = phase
        ripple.alpha = ((1f - t) * 140f * intensity).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, outer * (0.15f + 0.85f * t), ripple)
    }
}
