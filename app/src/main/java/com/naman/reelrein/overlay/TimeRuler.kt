package com.naman.reelrein.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.text.TextPaint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

/**
 * Drag-to-pick minutes (1..50). A track with a tick per minute and a number every 5, a
 * gradient fill up to a round thumb, and a haptic tick on every minute you pass.
 * Tapping anywhere on the track jumps there.
 */
class TimeRuler(
    ctx: Context,
    private val accent: Int,
    private val accent2: Int,
    initial: Int,
    private val min: Int = 1,
    private val max: Int = 50,
) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity

    var value = initial.coerceIn(min, max)
        private set
    var onChange: ((Int) -> Unit)? = null

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(40, 255, 255, 255)
        strokeWidth = 6 * d
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 6 * d
        strokeCap = Paint.Cap.ROUND
    }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 255, 255, 255)
        strokeWidth = 1.2f * d
    }
    private val majorTick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 255, 255)
        strokeWidth = 1.6f * d
    }
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 255, 255)
        textSize = 11 * sp
        textAlign = Paint.Align.CENTER
    }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val thumbGlow = Paint(Paint.ANTI_ALIAS_FLAG)

    private val pad get() = 14 * d
    private val trackY get() = 18 * d

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (58 * d).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        fill.shader = LinearGradient(pad, 0f, w - pad, 0f, accent, accent2, Shader.TileMode.CLAMP)
        thumbGlow.color = Color.argb(90, Color.red(accent2), Color.green(accent2), Color.blue(accent2))
    }

    private fun xFor(v: Int) = pad + (width - 2 * pad) * (v - min).toFloat() / (max - min)

    override fun onDraw(c: Canvas) {
        val y = trackY
        c.drawLine(pad, y, width - pad, y, track)
        val tx = xFor(value)
        c.drawLine(pad, y, tx, y, fill)
        for (v in min..max) {
            val x = xFor(v)
            val major = v == min || v % 5 == 0
            val top = y + 9 * d
            c.drawLine(x, top, x, top + (if (major) 8 else 4) * d, if (major) majorTick else tick)
            if (major) c.drawText("$v", x, top + 22 * d, label)
        }
        c.drawCircle(tx, y, 15 * d, thumbGlow)
        c.drawCircle(tx, y, 10 * d, thumb)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val f = ((e.x - pad) / (width - 2 * pad)).coerceIn(0f, 1f)
                set((min + f * (max - min)).roundToInt())
            }
        }
        return true
    }

    fun set(v: Int) {
        val nv = v.coerceIn(min, max)
        if (nv == value) return
        value = nv
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        invalidate()
        onChange?.invoke(nv)
    }
}
