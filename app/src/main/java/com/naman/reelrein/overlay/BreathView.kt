package com.naman.reelrein.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.text.TextPaint
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The centrepiece of the pause and time's-up screens: a glowing orb that grows on the in-breath
 * and shrinks on the out-breath (4 s each), inside a ring that fills as the pause runs.
 * Driven by the real clock, so the phone's animation-speed setting can't change the timing;
 * with animations turned off it stays still.
 */
class BreathView(ctx: Context, private val accent: Int, private val accent2: Int) : View(ctx) {
    private val density = resources.displayMetrics.density
    private val motion = Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

    private var startAt = 0L
    private var durationMs = 0L
    private var running = false
    private var finished = false

    /** Set for a still ring (time's up). */
    var fixedProgress: Float? = null
    var centerIcon: Drawable? = null
    var onFinished: (() -> Unit)? = null

    private val oval = RectF()
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
        color = Color.argb(30, 255, 255, 255)
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
        strokeCap = Paint.Cap.ROUND
    }
    private val orb = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 17 * resources.displayMetrics.scaledDensity
        typeface = semibold()
    }
    private val count = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(170, 255, 255, 255)
        textAlign = Paint.Align.CENTER
        textSize = 13 * resources.displayMetrics.scaledDensity
        isFakeBoldText = false
    }
    private var baseOrbR = 0f

    fun start(durationMs: Long) {
        this.durationMs = durationMs
        startAt = SystemClock.uptimeMillis()
        running = true
        finished = false
        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val cx = w / 2f
        val cy = h / 2f
        val r = min(w, h) / 2f
        baseOrbR = r * 0.62f
        orb.shader = RadialGradient(
            cx - baseOrbR * 0.35f, cy - baseOrbR * 0.45f, baseOrbR * 1.5f,
            intArrayOf(accent2, accent, darken(accent)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        glow.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(withAlpha(accent, 110), withAlpha(accent, 40), Color.TRANSPARENT), floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP,
        )
        arc.shader = SweepGradient(cx, cy, intArrayOf(accent, accent2, accent), null).apply {
            setLocalMatrix(Matrix().apply { setRotate(-90f, cx, cy) })
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val r = min(w, h) / 2f
        val elapsed = if (running) SystemClock.uptimeMillis() - startAt else 0L
        val progress = fixedProgress ?: if (durationMs > 0) (elapsed.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

        // 0 = fully out, 1 = fully in; an 8 s cosine starting from "out".
        val t = (elapsed % BREATH_CYCLE_MS).toFloat() / BREATH_CYCLE_MS
        val phase = if (motion && running) ((1 - cos(2 * PI * t)) / 2).toFloat() else 0.6f
        val inhaling = sin(2 * PI * t) >= 0

        // glow + orb (scaled so the cached gradients can be reused every frame)
        val s = 0.74f + 0.26f * phase
        c.save()
        c.scale(0.85f + 0.15f * phase, 0.85f + 0.15f * phase, cx, cy)
        c.drawCircle(cx, cy, r, glow)
        c.restore()
        c.save()
        c.scale(s, s, cx, cy)
        c.drawCircle(cx, cy, baseOrbR, orb)
        c.restore()

        // progress ring
        val rr = r - arc.strokeWidth
        oval.set(cx - rr, cy - rr, cx + rr, cy + rr)
        c.drawArc(oval, 0f, 360f, false, track)
        if (progress > 0f) c.drawArc(oval, -90f, 360f * progress, false, arc)

        // centre
        val icon = centerIcon
        if (icon != null) {
            val half = (baseOrbR * 0.42f).toInt()
            icon.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
            icon.draw(c)
        } else if (running && !finished) {
            val text = if (!motion) "Breathe" else if (inhaling) "Breathe in" else "Breathe out"
            c.drawText(text, cx, cy + label.textSize * 0.1f, label)
            val left = ((durationMs - elapsed + 999) / 1000).coerceAtLeast(0)
            c.drawText("${left}s", cx, cy + label.textSize * 1.25f, count)
        }

        if (running && fixedProgress == null && progress >= 1f && !finished) {
            finished = true
            post { onFinished?.invoke() }
        }
        if (running && isAttachedToWindow) postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    private fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))
    private fun darken(c: Int) = Color.rgb((Color.red(c) * 0.55f).toInt(), (Color.green(c) * 0.5f).toInt(), (Color.blue(c) * 0.75f).toInt())

    private companion object {
        const val BREATH_CYCLE_MS = 8_000L
    }
}

/** Small ring for the counter pill: how much of the session is left. */
class MiniRing(ctx: Context, private val accent: Int, private val warn: Int) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val oval = RectF()
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * d
        color = Color.argb(50, 255, 255, 255)
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * d
        strokeCap = Paint.Cap.ROUND
    }

    /** Fraction of the session left, 0..1. */
    var fraction = 1f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }
    var warning = false
        set(v) {
            field = v
            invalidate()
        }

    override fun onDraw(c: Canvas) {
        val inset = arc.strokeWidth / 2 + d * 0.5f
        oval.set(inset, inset, width - inset, height - inset)
        c.drawArc(oval, 0f, 360f, false, track)
        arc.color = if (warning) warn else accent
        c.drawArc(oval, -90f, 360f * fraction, false, arc)
    }
}

fun semibold(): Typeface =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Typeface.create(Typeface.DEFAULT, 600, false) else Typeface.DEFAULT_BOLD
