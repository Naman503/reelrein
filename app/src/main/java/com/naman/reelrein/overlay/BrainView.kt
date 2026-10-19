package com.naman.reelrein.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.min

/**
 * ReelRein's brain pal: a little pink brain whose mood follows how many reels you've watched
 * today. Drawn in code (no images), so it stays sharp at any size.
 *
 *  0 happy    big shiny eyes, smile, rosy cheeks
 *  1 meh      half-lidded eyes, flat mouth
 *  2 worried  worried brows, wobbly mouth, cracks appear
 *  3 fried    dizzy X eyes, tongue out, grey and dripping
 */
class BrainView(ctx: Context) : View(ctx) {
    var mood: Int = 0
        set(v) {
            field = v.coerceIn(0, 3)
            invalidate()
        }

    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val eye = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val r = RectF()

    companion object {
        /** Mood from today's reels and the cap (or a default scale when no cap is set). */
        fun moodFor(reels: Int, cap: Int): Int {
            val c = if (cap > 0) cap else 150
            return when {
                reels >= c -> 3
                reels >= c * 2 / 3 -> 2
                reels >= c / 3 -> 1
                else -> 0
            }
        }
    }

    override fun onDraw(c: Canvas) {
        val s = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val u = s / 100f // design on a 100x100 grid

        val pink = when (mood) {
            0 -> Color.parseColor("#F4A3A8")
            1 -> Color.parseColor("#EE9BA2")
            2 -> Color.parseColor("#DE9AA0")
            else -> Color.parseColor("#C9A9AB")
        }
        val dark = when (mood) {
            3 -> Color.parseColor("#8E7476")
            else -> Color.parseColor("#C96D78")
        }
        fill.color = pink
        shade.color = dark
        line.color = dark
        line.strokeWidth = 2.6f * u

        fun x(v: Float) = cx + (v - 50) * u
        fun y(v: Float) = cy + (v - 50) * u

        // Body: a bumpy cloud of lobes
        val lobes = listOf(
            floatArrayOf(30f, 40f, 19f), floatArrayOf(50f, 31f, 21f), floatArrayOf(70f, 40f, 19f),
            floatArrayOf(24f, 58f, 17f), floatArrayOf(76f, 58f, 17f), floatArrayOf(40f, 66f, 19f),
            floatArrayOf(60f, 66f, 19f), floatArrayOf(50f, 52f, 26f),
        )
        // soft outline under the body
        lobes.forEach { (lx, ly, lr) -> c.drawCircle(x(lx), y(ly), (lr + 2.2f) * u, shade) }
        lobes.forEach { (lx, ly, lr) -> c.drawCircle(x(lx), y(ly), lr * u, fill) }

        // Folds
        line.strokeWidth = 2.2f * u
        path.reset(); path.moveTo(x(50f), y(18f)); path.quadTo(x(46f), y(26f), x(51f), y(32f)); c.drawPath(path, line)
        path.reset(); path.moveTo(x(22f), y(40f)); path.quadTo(x(29f), y(37f), x(31f), y(44f)); c.drawPath(path, line)
        path.reset(); path.moveTo(x(78f), y(40f)); path.quadTo(x(71f), y(37f), x(69f), y(44f)); c.drawPath(path, line)

        // Cracks (worried / fried)
        if (mood >= 2) {
            line.color = Color.parseColor("#7A4E55")
            line.strokeWidth = 1.8f * u
            path.reset()
            path.moveTo(x(64f), y(22f)); path.lineTo(x(60f), y(29f)); path.lineTo(x(65f), y(33f)); path.lineTo(x(61f), y(39f))
            c.drawPath(path, line)
            if (mood == 3) {
                path.reset()
                path.moveTo(x(26f), y(48f)); path.lineTo(x(32f), y(52f)); path.lineTo(x(28f), y(57f))
                c.drawPath(path, line)
            }
            line.color = dark
        }

        // Eyes
        val eyeY = 52f
        listOf(38f, 62f).forEach { ex ->
            when (mood) {
                3 -> { // dizzy X
                    line.strokeWidth = 3f * u
                    line.color = Color.parseColor("#4A2E33")
                    c.drawLine(x(ex - 5), y(eyeY - 5), x(ex + 5), y(eyeY + 5), line)
                    c.drawLine(x(ex + 5), y(eyeY - 5), x(ex - 5), y(eyeY + 5), line)
                    line.color = dark
                }
                else -> {
                    eye.color = Color.parseColor("#1E1418")
                    val er = if (mood == 0) 8.5f else 7.5f
                    c.drawCircle(x(ex), y(eyeY), er * u, eye)
                    eye.color = Color.WHITE
                    c.drawCircle(x(ex - 2.6f), y(eyeY - 3f), 2.8f * u, eye)
                    c.drawCircle(x(ex + 2.8f), y(eyeY + 2.4f), 1.3f * u, eye)
                    if (mood == 1) { // heavy lids
                        fill.color = pink
                        r.set(x(ex - er - 1), y(eyeY - er - 2), x(ex + er + 1), y(eyeY - 1.5f))
                        c.drawRect(r, fill)
                        line.strokeWidth = 2f * u
                        c.drawLine(x(ex - er), y(eyeY - 1.5f), x(ex + er), y(eyeY - 1.5f), line)
                    }
                    if (mood == 2) { // worried brows
                        line.strokeWidth = 2.2f * u
                        val tilt = if (ex < 50) 1 else -1
                        c.drawLine(x(ex - 6), y(eyeY - 12 + 2 * tilt), x(ex + 6), y(eyeY - 12 - 2 * tilt), line)
                    }
                }
            }
        }

        // Cheeks
        if (mood == 0) {
            eye.color = Color.argb(110, 255, 120, 140)
            c.drawCircle(x(28f), y(62f), 4.5f * u, eye)
            c.drawCircle(x(72f), y(62f), 4.5f * u, eye)
        }

        // Mouth
        line.color = Color.parseColor("#4A2E33")
        line.strokeWidth = 2.6f * u
        path.reset()
        when (mood) {
            0 -> { path.moveTo(x(44f), y(64f)); path.quadTo(x(50f), y(71f), x(56f), y(64f)) }
            1 -> { path.moveTo(x(45f), y(66f)); path.lineTo(x(55f), y(66f)) }
            2 -> { path.moveTo(x(43f), y(68f)); path.quadTo(x(46.5f), y(64f), x(50f), y(68f)); path.quadTo(x(53.5f), y(72f), x(57f), y(68f)) }
            else -> {
                path.moveTo(x(44f), y(66f)); path.lineTo(x(56f), y(66f))
                eye.color = Color.parseColor("#E37B8A") // tongue
                c.drawCircle(x(52f), y(69.5f), 3.4f * u, eye)
            }
        }
        c.drawPath(path, line)

        // Drip when fried
        if (mood == 3) {
            fill.color = pink
            path.reset()
            path.moveTo(x(64f), y(78f)); path.quadTo(x(66f), y(90f), x(67f), y(92f))
            path.quadTo(x(70f), y(96f), x(72f), y(91f)); path.quadTo(x(72f), y(86f), x(71f), y(78f)); path.close()
            c.drawPath(path, fill)
        }
        line.color = dark
    }
}
