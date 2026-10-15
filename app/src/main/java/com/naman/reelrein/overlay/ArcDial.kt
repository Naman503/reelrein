package com.naman.reelrein.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.text.TextPaint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * One Sec-style time dial: a thick 270° arc with the gap at the bottom, a lavender fill up to
 * a white knob, and the value in the middle ("6 min"). Drag along the arc (or tap anywhere on
 * it) to set minutes; a haptic tick on every minute. The fill can't jump across the gap, so
 * dragging past the end stops at 1 or [max] instead of wrapping around.
 */
class ArcDial(
    ctx: Context,
    private val colorStart: Int,
    private val colorEnd: Int,
    initial: Int,
    private val min: Int = 1,
    private val max: Int = 50,
) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity

    var value = initial.coerceIn(min, max)
        private set
    var onChange: ((Int) -> Unit)? = null
    var haptics = true

    private val stroke = 30 * d
    private val oval = RectF()
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(255, 46, 47, 54)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
    }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(6 * d, 0f, 2 * d, Color.argb(120, 0, 0, 0))
    }
    private val number = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 30 * sp
        typeface = semibold()
    }
    private val hint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 255, 255)
        textAlign = Paint.Align.CENTER
        textSize = 13 * sp
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // knob shadow
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val r = min(w, h) / 2f - stroke / 2 - 4 * d
        val cx = w / 2f
        val cy = h / 2f
        oval.set(cx - r, cy - r, cx + r, cy + r)
        // Gradient along the arc, rotated so it starts where the arc starts (135°).
        fill.shader = SweepGradient(cx, cy, intArrayOf(colorStart, colorEnd, colorEnd, colorStart), floatArrayOf(0f, 0.75f, 0.9f, 1f)).apply {
            setLocalMatrix(android.graphics.Matrix().apply { setRotate(START, cx, cy) })
        }
    }

    private fun fraction() = (value - min).toFloat() / (max - min)

    override fun onDraw(c: Canvas) {
        c.drawArc(oval, START, SWEEP, false, track)
        val sweep = SWEEP * fraction()
        c.drawArc(oval, START, sweep.coerceAtLeast(0.01f), false, fill)

        val a = Math.toRadians((START + sweep).toDouble())
        val r = oval.width() / 2
        val kx = oval.centerX() + r * cos(a).toFloat()
        val ky = oval.centerY() + r * sin(a).toFloat()
        c.drawCircle(kx, ky, stroke * 0.42f, knob)

        val cy = oval.centerY()
        c.drawText("$value min", oval.centerX(), cy + number.textSize * 0.35f, number)
        c.drawText("drag to set", oval.centerX(), cy + number.textSize * 0.35f + 24 * d, hint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val deg = Math.toDegrees(atan2((e.y - oval.centerY()).toDouble(), (e.x - oval.centerX()).toDouble())).toFloat()
                var rel = ((deg - START) % 360 + 360) % 360
                if (rel > SWEEP) rel = if (rel - SWEEP < (360 - SWEEP) / 2) SWEEP else 0f // in the gap
                val v = (min + rel / SWEEP * (max - min)).roundToInt()
                // While dragging, don't let the value leap across the gap (max <-> min).
                if (e.actionMasked == MotionEvent.ACTION_MOVE && abs(v - value) > (max - min) / 2) return true
                set(v)
            }
        }
        return true
    }

    fun set(v: Int) {
        val nv = v.coerceIn(min, max)
        if (nv == value) return
        value = nv
        if (haptics) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        invalidate()
        onChange?.invoke(nv)
    }

    private companion object {
        const val START = 135f
        const val SWEEP = 270f
    }
}
