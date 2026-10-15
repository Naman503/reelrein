package com.naman.reelrein.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View

/**
 * A calm hourglass: lavender caps, a glass body, and peach sand that drains from the top
 * bulb to the bottom over [start]'s duration, with a thin falling stream. Driven by the real
 * clock, so the phone's animation-speed setting can't change the timing. [fixedProgress]
 * draws a still glass (1 = all sand at the bottom, for "time's up").
 */
class HourglassView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density

    var fixedProgress: Float? = null
    var onFinished: (() -> Unit)? = null

    private var startAt = 0L
    private var durationMs = 0L
    private var running = false
    private var finished = false

    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val capShade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 20, 18, 60) }
    private val glassFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(34, 255, 255, 255) }
    private val glassStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * d
        color = Color.argb(90, 255, 255, 255)
    }
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * d
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(70, 255, 255, 255)
    }
    private val sand = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stream = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2.2f * d
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#E9B48A")
    }
    private val glass = Path()
    private val tmp = Path()
    private val rect = RectF()

    fun start(durationMs: Long) {
        this.durationMs = durationMs
        startAt = SystemClock.uptimeMillis()
        running = true
        finished = false
        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        capPaint.shader = LinearGradient(0f, 0f, w.toFloat(), 0f,
            intArrayOf(Color.parseColor("#7C7AD6"), Color.parseColor("#A9A7F2"), Color.parseColor("#7472CF")), null, Shader.TileMode.CLAMP)
        sand.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
            Color.parseColor("#F2C9A0"), Color.parseColor("#DFA57A"), Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val bodyW = w * 0.62f
        val cx = w / 2
        val capH = h * 0.1f
        val top = capH * 0.85f
        val bottom = h - capH * 0.85f
        val cy = (top + bottom) / 2
        val half = bodyW / 2
        val neck = w * 0.035f

        // glass outline: two bulbs meeting at a narrow neck
        glass.reset()
        glass.moveTo(cx - half, top)
        glass.lineTo(cx + half, top)
        glass.cubicTo(cx + half, cy - (cy - top) * 0.25f, cx + neck * 1.2f, cy - (cy - top) * 0.2f, cx + neck, cy)
        glass.cubicTo(cx + neck * 1.2f, cy + (bottom - cy) * 0.2f, cx + half, cy + (bottom - cy) * 0.25f, cx + half, bottom)
        glass.lineTo(cx - half, bottom)
        glass.cubicTo(cx - half, cy + (bottom - cy) * 0.25f, cx - neck * 1.2f, cy + (bottom - cy) * 0.2f, cx - neck, cy)
        glass.cubicTo(cx - neck * 1.2f, cy - (cy - top) * 0.2f, cx - half, cy - (cy - top) * 0.25f, cx - half, top)
        glass.close()

        val p = fixedProgress ?: if (running && durationMs > 0) {
            ((SystemClock.uptimeMillis() - startAt).toFloat() / durationMs).coerceIn(0f, 1f)
        } else 0f

        c.drawPath(glass, glassFill)
        c.save()
        c.clipPath(glass)
        // top sand: its surface sinks towards the neck
        val topSurface = top + (cy - top) * (0.18f + 0.82f * p)
        if (p < 1f) c.drawRect(cx - half, topSurface, cx + half, cy, sand)
        // bottom sand: a mound that grows from the base
        val moundH = (bottom - cy) * 0.78f * p
        if (moundH > 0f) {
            tmp.reset()
            tmp.moveTo(cx - half, bottom)
            tmp.lineTo(cx - half, bottom - moundH * 0.55f)
            tmp.quadTo(cx, bottom - moundH * 1.25f, cx + half, bottom - moundH * 0.55f)
            tmp.lineTo(cx + half, bottom)
            tmp.close()
            c.drawPath(tmp, sand)
        }
        c.restore()
        // falling stream
        if (p in 0.001f..0.995f && fixedProgress == null) {
            c.drawLine(cx, cy, cx, bottom - moundH * 0.95f, stream)
        }
        c.drawPath(glass, glassStroke)
        c.drawLine(cx - half * 0.62f, top + (cy - top) * 0.15f, cx - half * 0.45f, top + (cy - top) * 0.55f, shine)

        // caps
        val capW = bodyW * 1.18f
        rect.set(cx - capW / 2, 0f, cx + capW / 2, capH)
        c.drawRoundRect(rect, capH / 2.4f, capH / 2.4f, capPaint)
        rect.set(cx - capW / 2, capH * 0.62f, cx + capW / 2, capH)
        c.drawRoundRect(rect, capH / 3f, capH / 3f, capShade)
        rect.set(cx - capW / 2, h - capH, cx + capW / 2, h)
        c.drawRoundRect(rect, capH / 2.4f, capH / 2.4f, capPaint)
        rect.set(cx - capW / 2, h - capH * 0.38f, cx + capW / 2, h)
        c.drawRoundRect(rect, capH / 3f, capH / 3f, capShade)

        if (running && fixedProgress == null && p >= 1f && !finished) {
            finished = true
            post { onFinished?.invoke() }
        }
        if (running && !finished && isAttachedToWindow) postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }
}
