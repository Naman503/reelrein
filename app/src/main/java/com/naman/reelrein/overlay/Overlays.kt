package com.naman.reelrein.overlay

import android.accessibilityservice.AccessibilityService
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.naman.reelrein.HudConfig
import com.naman.reelrein.IgSignatures
import com.naman.reelrein.R
import kotlin.math.abs

data class PauseInfo(val openNumber: Int, val timeToday: String, val reelTimeToday: String, val reelsToday: Int)

data class TimesUpInfo(val sessionReelTime: String, val sessionReels: Int, val sessionTime: String, val reelTimeToday: String)

/** Everything the counter pill and its panel show. */
data class HudState(
    val reelTime: String,         // time on Reels this session
    val sessionReels: Int,
    val usedFraction: Float?,     // share of the limit used (null = no limit)
    val timeLeft: String?,        // null = no limit
    val warning: Boolean,         // under a minute left
    val todayReelTime: String,
    val todayReels: Int,
    val todayOpens: Int,
    val speed2x: Boolean?,        // null = speed lock off, true = 2x on now, false = 1x now
    val speedLockOn: Boolean,
    val autoScroll: Boolean,
)

interface HudActions {
    fun onSpeedLock(on: Boolean)
    fun onAutoScroll(on: Boolean)
    fun onChangeLimit()
    fun onHide()
    fun onEndSession()
    fun onMoved(x: Int, y: Int)
}

/**
 * All windows drawn over Instagram. They are TYPE_ACCESSIBILITY_OVERLAY windows, which an
 * accessibility service may add without the "Display over other apps" permission.
 * Plain Views on purpose: cheap, and no lifecycle plumbing.
 *
 * One calm system for every surface: opaque near-black base with soft light, Instagram's real
 * icon so it's clear what's paused, numbers big and tabular, and every screen ending in the
 * same "set a limit" card, so continuing always means choosing how long. The card sits a
 * little above the bottom edge, in easy thumb reach.
 */
class Overlays(private val service: AccessibilityService, private val onFullShown: () -> Unit, private val onFullHidden: () -> Unit) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val ctx: Context = service
    private val density = service.resources.displayMetrics.density

    private val accent = service.getColor(R.color.rr_accent)
    private val accent2 = service.getColor(R.color.rr_accent_2)
    private val textColor = service.getColor(R.color.rr_text)
    private val textDim = service.getColor(R.color.rr_text_dim)
    private val warn = Color.parseColor("#FFB454")
    private val base = Color.parseColor("#FF0A0B12")
    private val ink = Color.parseColor("#FF0B0C14")

    private var fullView: View? = null
    private val fullAnimators = mutableListOf<ValueAnimator>()
    private var hud: HudViews? = null

    /** From settings; buttons and the pill skip haptics when off. */
    var haptics = true

    val fullShowing get() = fullView != null
    val hudShowing get() = hud != null

    // ---------------- Pause screen ----------------

    fun showPause(
        seconds: Int,
        info: PauseInfo,
        initialMinutes: Int,
        onStart: (minutes: Int) -> Unit,
        onClose: () -> Unit,
    ) {
        val (root, content) = screen("Paused")

        content.addView(spacer(), LinearLayout.LayoutParams(-1, 0, TOP_WEIGHT))
        val breath = BreathView(ctx, accent, accent2)
        content.addView(breath, centered(dp(164), dp(164)))

        val title = text("Take a breath", 26f, textColor, bold = true).apply { setPadding(0, dp(18), 0, dp(6)) }
        content.addView(title, centered())
        val subtitle = text("Instagram will be here in a moment.", 14f, textDim)
        content.addView(subtitle, centered())

        content.addView(statsRow(
            ordinal(info.openNumber) to "Open today",
            info.reelTimeToday to "On reels today",
            info.reelsToday.toString() to "Reels today",
        ), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        content.addView(spacer(), LinearLayout.LayoutParams(-1, 0, MID_WEIGHT))

        val picker = limitCard(initialMinutes, enabled = false, onStart = onStart, closeLabel = "Close Instagram", onClose = onClose)
        content.addView(picker.view, LinearLayout.LayoutParams(-1, -2))
        content.addView(spacer(), LinearLayout.LayoutParams(-1, 0, BOTTOM_WEIGHT))

        breath.onFinished = {
            title.text = "Still want to go in?"
            subtitle.text = "Set a limit. You'll get a nudge when it's up."
            picker.setEnabled(true)
        }
        showFull(root, content)
        breath.start(seconds * 1_000L)
    }

    // ---------------- Time's up / change limit ----------------

    fun showTimesUp(info: TimesUpInfo, initialMinutes: Int, onStart: (minutes: Int) -> Unit, onClose: () -> Unit) {
        limitScreen(
            status = "Time's up",
            title = "Time's up",
            subtitle = "${info.sessionReels} ${if (info.sessionReels == 1) "reel" else "reels"} in ${info.sessionReelTime}. Stop here, or set a new limit.",
            info = info,
            initialMinutes = initialMinutes,
            lockMs = TIMES_UP_LOCK_MS,
            closeLabel = "Close Instagram",
            closeIcon = R.drawable.ic_home,
            onStart = onStart,
            onClose = onClose,
        )
    }

    /** From the panel: pick a new limit mid-session (counted from now). */
    fun showChangeLimit(info: TimesUpInfo, initialMinutes: Int, onStart: (minutes: Int) -> Unit, onCancel: () -> Unit) {
        limitScreen(
            status = "Session",
            title = "Change your limit",
            subtitle = "So far: ${info.sessionReels} ${if (info.sessionReels == 1) "reel" else "reels"} in ${info.sessionReelTime}. The new limit starts now.",
            info = info,
            initialMinutes = initialMinutes,
            lockMs = 0,
            closeLabel = "Keep my current limit",
            closeIcon = R.drawable.ic_close,
            onStart = onStart,
            onClose = onCancel,
        )
    }

    private fun limitScreen(
        status: String,
        title: String,
        subtitle: String,
        info: TimesUpInfo,
        initialMinutes: Int,
        lockMs: Long,
        closeLabel: String,
        closeIcon: Int,
        onStart: (Int) -> Unit,
        onClose: () -> Unit,
    ) {
        val (root, content) = screen(status)

        content.addView(spacer(), LinearLayout.LayoutParams(-1, 0, TOP_WEIGHT))
        val ring = BreathView(ctx, accent, accent2).apply {
            fixedProgress = 1f
            centerIcon = tinted(R.drawable.ic_timer, Color.WHITE)
        }
        content.addView(ring, centered(dp(120), dp(120)))
        content.addView(text(title, 28f, textColor, bold = true).apply { setPadding(0, dp(18), 0, dp(6)) }, centered())
        content.addView(text(subtitle, 14f, textDim), centered())
        content.addView(statsRow(
            info.sessionReelTime to "On reels",
            info.sessionReels.toString() to "Reels",
            info.reelTimeToday to "Reels today",
        ), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        content.addView(spacer(), LinearLayout.LayoutParams(-1, 0, MID_WEIGHT))

        // On time's up, a short beat before "continue" works, so it's a choice rather than a reflex.
        val picker = limitCard(initialMinutes, enabled = lockMs <= 0, onStart = onStart, closeLabel = closeLabel, closeIcon = closeIcon, onClose = onClose)
        content.addView(picker.view, LinearLayout.LayoutParams(-1, -2))
        content.addView(spacer(), LinearLayout.LayoutParams(-1, 0, BOTTOM_WEIGHT))
        showFull(root, content)
        if (lockMs > 0) root.postDelayed({ if (fullView === root) picker.setEnabled(true) }, lockMs)
    }

    private class LimitCard(val view: View, val setEnabled: (Boolean) -> Unit)

    /** "Set a limit" card: big number, drag ruler, quick picks, Continue + a way out. */
    private fun limitCard(
        initial: Int,
        enabled: Boolean,
        onStart: (Int) -> Unit,
        closeLabel: String,
        closeIcon: Int = R.drawable.ic_home,
        onClose: () -> Unit,
    ): LimitCard {
        val card = card()
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        head.addView(caps("Set a limit"), LinearLayout.LayoutParams(0, -2, 1f).apply { bottomMargin = dp(8) })
        val big = text("$initial", 40f, textColor, bold = true).apply { tabular() }
        head.addView(big)
        head.addView(text(" min", 16f, textDim).apply { setPadding(0, 0, 0, dp(6)) })
        card.addView(head, LinearLayout.LayoutParams(-1, -2))

        val picks = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val continueBtn = button("Continue for $initial min", R.drawable.ic_play_arrow, Style.ACCENT) {}
        val ruler = TimeRuler(ctx, accent, accent2, initial).apply {
            onChange = { v ->
                big.text = "$v"
                (continueBtn.getChildAt(1) as TextView).text = "Continue for $v min"
            }
        }
        card.addView(ruler, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        listOf(5, 10, 15, 30).forEachIndexed { i, m ->
            picks.addView(chip("$m min") { ruler.set(m) }, LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                if (i > 0) marginStart = dp(8)
            })
        }
        card.addView(picks, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        pressable(continueBtn) { onStart(ruler.value) }
        card.addView(continueBtn, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(16) })
        card.addView(button(closeLabel, closeIcon, Style.WHITE) { onClose() }, LinearLayout.LayoutParams(-1, dp(52)).apply {
            topMargin = dp(10)
        })

        val lockable = listOf<View>(ruler, picks, continueBtn)
        val setEnabled = { on: Boolean ->
            lockable.forEach { v ->
                v.isEnabled = on
                if (v is LinearLayout) for (i in 0 until v.childCount) v.getChildAt(i).isEnabled = on
                v.animate().alpha(if (on) 1f else 0.32f).setDuration(200).start()
            }
            if (on) haptic(card, HapticFeedbackConstants.CONFIRM)
        }
        lockable.forEach { it.alpha = if (enabled) 1f else 0.32f }
        if (!enabled) setEnabled(false)
        return LimitCard(card, setEnabled)
    }

    fun hideFull() {
        fullAnimators.forEach { it.cancel() }
        fullAnimators.clear()
        val v = fullView ?: return
        fullView = null
        runCatching { wm.removeView(v) }
        onFullHidden()
    }

    private fun showFull(root: View, content: View) {
        fullAnimators.forEach { it.cancel() }
        fullAnimators.clear()
        fullView?.let { runCatching { wm.removeView(it) } }
        fullView = null
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.OPAQUE, // nothing of Instagram shows through, not even during the fade
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        content.alpha = 0f
        content.translationY = dp(14).toFloat()
        runCatching { wm.addView(root, lp) }.onSuccess {
            fullView = root
            onFullShown()
            content.animate().alpha(1f).translationY(0f).setDuration(realTime(320)).setInterpolator(DecelerateInterpolator()).start()
        }
    }

    // ---------------- Counter pill + panel ----------------

    private class HudViews(
        val root: LinearLayout,
        val pill: LinearLayout,
        val config: HudConfig,
        val ring: MiniRing?,
        val reelTime: TextView?,
        val todayTime: TextView?,
        val timeLeft: TextView?,
        val reels: TextView?,
        val todayReels: TextView?,
        val speedChip: TextView?,
        val panel: LinearLayout,
        val pReelTime: TextView?,
        val pReels: TextView?,
        val pLeft: TextView?,
        val pLeftLabel: TextView?,
        val today: TextView?,
        val speedOff: TextView?,
        val speedOn: TextView?,
        val autoScroll: TextView?,
        val dim: Runnable,
        var lastReels: Int = -1,
        var state: HudState? = null,
    )

    @SuppressLint("ClickableViewAccessibility")
    fun showHud(x: Int, y: Int, config: HudConfig, actions: HudActions) {
        if (hud != null) return
        haptics = config.haptics
        val k = config.scale
        fun sdp(v: Int) = (v * k * density).toInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // --- the pill ---
        val pill = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(sdp(10), sdp(7), sdp(10), sdp(7))
            background = GradientDrawable().apply {
                cornerRadius = sdp(26).toFloat()
                when (config.style) {
                    "glass" -> {
                        setColor(Color.argb(140, 20, 20, 30))
                        setStroke(dp(1), Color.argb(70, 255, 255, 255))
                    }
                    "outline" -> {
                        setColor(Color.argb(90, 0, 0, 0))
                        setStroke(dp(2), accent)
                    }
                    else -> {
                        setColor(Color.argb(238, 8, 8, 12))
                        setStroke(dp(1), Color.argb(40, 255, 255, 255))
                    }
                }
            }
            elevation = dp(10).toFloat()
        }
        var groups = 0
        fun divider() {
            if (groups++ == 0) return
            pill.addView(View(ctx).apply { background = rounded(Color.argb(50, 255, 255, 255), 1) }, LinearLayout.LayoutParams(dp(1), sdp(20)).apply {
                marginStart = sdp(11)
                marginEnd = sdp(11)
            })
        }
        fun pillText(size: Float, color: Int = textColor, bold: Boolean = true) =
            text("", size * k, color, bold).apply { tabular() }
        fun pillIcon(res: Int, color: Int, size: Int) {
            pill.addView(icon(res, color), LinearLayout.LayoutParams(sdp(size), sdp(size)).apply { marginEnd = sdp(6) })
        }

        var ring: MiniRing? = null
        var reelTime: TextView? = null
        if (config.reelTime) {
            divider()
            if (config.ring) {
                val box = FrameLayout(ctx)
                ring = MiniRing(ctx, accent, warn)
                box.addView(ring, FrameLayout.LayoutParams(sdp(30), sdp(30)))
                box.addView(icon(R.drawable.ic_schedule, textColor), FrameLayout.LayoutParams(sdp(16), sdp(16), Gravity.CENTER))
                pill.addView(box, LinearLayout.LayoutParams(sdp(30), sdp(30)).apply { marginEnd = sdp(8) })
            } else {
                pillIcon(R.drawable.ic_schedule, textColor, 18)
            }
            reelTime = pillText(17f).also { pill.addView(it) }
        }
        var todayTime: TextView? = null
        if (config.todayTime) {
            divider()
            pillIcon(R.drawable.ic_today, textDim, 16)
            todayTime = pillText(15f, textDim, bold = false).also { pill.addView(it) }
        }
        var timeLeft: TextView? = null
        if (config.timeLeft) {
            divider()
            pillIcon(R.drawable.ic_timer, textColor, 17)
            timeLeft = pillText(16f).also { pill.addView(it) }
        }
        var reels: TextView? = null
        var todayReels: TextView? = null
        if (config.reels || config.todayReels) {
            divider()
            pillIcon(R.drawable.ic_movie, textColor, 18)
            if (config.reels) reels = pillText(17f).also { pill.addView(it) }
            if (config.todayReels) {
                todayReels = pillText(14f, textDim, bold = false).also {
                    pill.addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginStart = if (config.reels) sdp(4) else 0 })
                }
            }
        }
        var speedChip: TextView? = null
        if (config.speed) {
            speedChip = text("2×", 14f * k, Color.WHITE, bold = true).apply { setPadding(sdp(10), sdp(5), sdp(10), sdp(5)) }
            pill.addView(speedChip, LinearLayout.LayoutParams(-2, -2).apply { marginStart = if (groups > 0) sdp(11) else 0 })
            groups++
        }
        if (groups == 0) { // nothing chosen: keep a small handle so the panel is still reachable
            pillIcon(R.drawable.ic_movie, textColor, 18)
        }
        root.addView(pill, LinearLayout.LayoutParams(-2, -2))

        // --- the panel (tap the pill) ---
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(Color.argb(246, 12, 12, 20))
                setStroke(dp(1), Color.argb(40, 255, 255, 255))
            }
            elevation = dp(12).toFloat()
            visibility = View.GONE
        }
        var first = true
        fun gap() = if (first) { first = false; 0 } else dp(14)

        var pReelTime: TextView? = null
        var pReels: TextView? = null
        var pLeft: TextView? = null
        var pLeftLabel: TextView? = null
        if (config.panelSession) {
            panel.addView(caps("This session"), LinearLayout.LayoutParams(-2, -2).apply { topMargin = gap() })
            val stats = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            fun statCol(label: String): Pair<TextView, TextView> {
                val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                val v = text("–", 22f, textColor, bold = true).apply { gravity = Gravity.START; tabular() }
                val l = text(label, 12f, textDim).apply { gravity = Gravity.START }
                col.addView(v)
                col.addView(l)
                stats.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
                return v to l
            }
            pReelTime = statCol("on reels").first
            pReels = statCol("reels").first
            statCol("left").let { pLeft = it.first; pLeftLabel = it.second }
            panel.addView(stats, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }

        var today: TextView? = null
        if (config.panelToday) {
            today = text("", 13f, textDim).apply { gravity = Gravity.START }
            panel.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(Color.argb(20, 255, 255, 255), 12)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                addView(icon(R.drawable.ic_today, textDim), LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(8) })
                addView(today, LinearLayout.LayoutParams(0, -2, 1f))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = if (config.panelSession) dp(12) else gap() })
        }

        var speedOff: TextView? = null
        var speedOn: TextView? = null
        var autoScroll: TextView? = null
        if (config.panelSpeed || config.panelAutoScroll) {
            panel.addView(caps("Playback"), LinearLayout.LayoutParams(-2, -2).apply { topMargin = gap() })
        }
        if (config.panelSpeed) {
            val speedRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                background = rounded(Color.argb(20, 255, 255, 255), 14)
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            speedOff = segment("1×") { actions.onSpeedLock(false) }
            speedOn = segment("2× every reel") { actions.onSpeedLock(true) }
            speedRow.addView(speedOff, LinearLayout.LayoutParams(0, dp(38), 1f))
            speedRow.addView(speedOn, LinearLayout.LayoutParams(0, dp(38), 2f).apply { marginStart = dp(4) })
            panel.addView(speedRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        if (config.panelAutoScroll) {
            autoScroll = text("Auto scroll", 14f, textColor, bold = true).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(14), 0)
                compoundDrawablePadding = dp(10)
            }
            panel.addView(autoScroll, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(8) })
        }

        val actionsRow = mutableListOf<View>()
        if (config.panelChangeLimit) {
            actionsRow += smallAction("Change limit", R.drawable.ic_more_time) { actions.onChangeLimit() }
        }
        if (config.panelHide) {
            actionsRow += smallAction("Hide pill", R.drawable.ic_visibility) { actions.onHide() }
        }
        if (actionsRow.isNotEmpty()) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            actionsRow.forEachIndexed { i, v ->
                row.addView(v, LinearLayout.LayoutParams(0, dp(44), 1f).apply { if (i > 0) marginStart = dp(8) })
            }
            panel.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = gap() })
        }
        if (config.panelEnd) {
            panel.addView(button("End session", R.drawable.ic_home, Style.WHITE) { actions.onEndSession() }, LinearLayout.LayoutParams(-1, dp(46)).apply {
                topMargin = if (actionsRow.isNotEmpty()) dp(8) else gap()
            })
        }
        root.addView(panel, LinearLayout.LayoutParams(dp(300), -2).apply { topMargin = dp(8) })

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            this.x = x
            this.y = if (y >= 0) y else dp(52)
        }

        val dim = Runnable {
            if (panel.visibility != View.VISIBLE) pill.animate().alpha(config.idleAlpha).setDuration(realTime(400)).start()
        }
        val views = HudViews(
            root, pill, config, ring, reelTime, todayTime, timeLeft, reels, todayReels, speedChip,
            panel, pReelTime, pReels, pLeft, pLeftLabel, today, speedOff, speedOn, autoScroll, dim,
        )
        autoScroll?.setOnClickListener {
            haptic(it, HapticFeedbackConstants.VIRTUAL_KEY)
            actions.onAutoScroll(!(views.state?.autoScroll ?: false))
        }

        // Pill: tap does what's set in settings (panel / toggle 2x / nothing), drag moves it.
        val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        pill.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    startX = lp.x; startY = lp.y
                    dragging = false
                    wake(views)
                    v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80).start()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(root, lp) }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    if (ev.actionMasked == MotionEvent.ACTION_UP) {
                        if (dragging) {
                            actions.onMoved(lp.x, lp.y)
                        } else {
                            haptic(v, HapticFeedbackConstants.VIRTUAL_KEY)
                            when (config.tap) {
                                "speed" -> actions.onSpeedLock(!(views.state?.speedLockOn ?: false))
                                "nothing" -> Unit
                                else -> setPanel(views, panel.visibility != View.VISIBLE)
                            }
                        }
                    }
                }
            }
            true
        }
        // A tap anywhere else closes the panel.
        root.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE && panel.visibility == View.VISIBLE) setPanel(views, false)
            false
        }

        runCatching { wm.addView(root, lp) }.onSuccess {
            hud = views
            wake(views)
        }
    }

    private fun setPanel(h: HudViews, open: Boolean) {
        if (open) {
            h.panel.alpha = 0f
            h.panel.translationY = -dp(8).toFloat()
            h.panel.visibility = View.VISIBLE
            h.panel.animate().alpha(1f).translationY(0f).setDuration(realTime(180)).start()
        } else {
            h.panel.visibility = View.GONE
        }
        wake(h)
    }

    fun closeHudPanel() {
        hud?.let { if (it.panel.visibility == View.VISIBLE) setPanel(it, false) }
    }

    fun updateHud(s: HudState) {
        val h = hud ?: return
        h.state = s
        h.reelTime?.apply {
            text = s.reelTime
            setTextColor(if (s.warning && h.config.ring) warn else textColor)
        }
        h.ring?.apply {
            fraction = s.usedFraction ?: 0f
            warning = s.warning
        }
        h.todayTime?.text = s.todayReelTime
        h.timeLeft?.apply {
            text = s.timeLeft ?: "∞"
            setTextColor(if (s.warning) warn else textColor)
        }
        h.reels?.text = s.sessionReels.toString()
        h.todayReels?.text = if (h.reels != null) "/ ${s.todayReels}" else "${s.todayReels} today"

        h.speedChip?.let { chip ->
            when (s.speed2x) {
                null -> chip.visibility = View.GONE
                true -> {
                    chip.visibility = View.VISIBLE
                    chip.text = "2×"
                    chip.setTextColor(Color.WHITE)
                    chip.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(accent, accent2)).apply {
                        cornerRadius = dp(16).toFloat()
                    }
                }
                false -> {
                    chip.visibility = View.VISIBLE
                    chip.text = "1×"
                    chip.setTextColor(textDim)
                    chip.background = rounded(Color.argb(30, 255, 255, 255), 16)
                }
            }
        }

        // panel
        h.pReelTime?.text = s.reelTime
        h.pReels?.text = s.sessionReels.toString()
        h.pLeft?.apply {
            text = s.timeLeft ?: "∞"
            setTextColor(if (s.warning) warn else textColor)
        }
        h.pLeftLabel?.text = if (s.timeLeft != null) "left" else "no limit"
        h.today?.text = "Today  ${s.todayReelTime} on reels · ${s.todayReels} reels · ${s.todayOpens} opens"
        h.speedOff?.let { styleSegment(it, !s.speedLockOn) }
        h.speedOn?.let { styleSegment(it, s.speedLockOn) }
        h.autoScroll?.apply {
            text = if (s.autoScroll) "Auto scroll  ·  On" else "Auto scroll  ·  Off"
            background = if (s.autoScroll) {
                GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(withAlpha(accent, 90), withAlpha(accent2, 90))).apply {
                    cornerRadius = dp(14).toFloat()
                }
            } else {
                rounded(Color.argb(20, 255, 255, 255), 14)
            }
            setCompoundDrawablesRelative(sized(R.drawable.ic_play_arrow, textColor, 18), null, null, null)
        }

        if (s.sessionReels != h.lastReels || s.warning) wake(h)
        h.lastReels = s.sessionReels
    }

    fun hideHud() {
        hud?.let {
            it.root.removeCallbacks(it.dim)
            runCatching { wm.removeView(it.root) }
        }
        hud = null
    }

    fun hideAll() {
        hideFull()
        hideHud()
        hideCancelPad()
    }

    private fun wake(h: HudViews) {
        h.root.removeCallbacks(h.dim)
        h.pill.animate().alpha(1f).setDuration(150).start()
        if (h.config.idleAlpha < 1f) h.root.postDelayed(h.dim, 4_000)
    }

    private fun smallAction(label: String, iconRes: Int, onClick: () -> Unit) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(Color.argb(20, 255, 255, 255))
            setStroke(dp(1), Color.argb(34, 255, 255, 255))
        }
        addView(icon(iconRes, textColor), LinearLayout.LayoutParams(dp(17), dp(17)).apply { marginEnd = dp(7) })
        addView(text(label, 13f, textColor, bold = true))
        pressable(this, onClick)
    }

    private fun haptic(v: View, type: Int) {
        if (haptics) v.performHapticFeedback(type)
    }

    // ---------------- Cancel pad ----------------
    // A 2 px invisible window in the top-left corner. A tap injected here makes Android cancel
    // a held press elsewhere (see SpeedLock.cancelHeldPress) without touching Instagram.

    private var cancelPad: View? = null

    /** Adds the pad if needed; returns the screen point to tap. */
    fun ensureCancelPad(): PointF {
        if (cancelPad == null) {
            val v = View(ctx).apply { setOnTouchListener { _, _ -> true } }
            val lp = WindowManager.LayoutParams(
                2, 2,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSPARENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            runCatching { wm.addView(v, lp) }.onSuccess { cancelPad = v }
        }
        return PointF(1f, 1f)
    }

    private fun hideCancelPad() {
        cancelPad?.let { runCatching { wm.removeView(it) } }
        cancelPad = null
    }

    // ---------------- building blocks ----------------

    /** Full-screen root: opaque base, soft light blobs, header, and a padded content column. */
    private fun screen(status: String): Pair<FrameLayout, LinearLayout> {
        val root = FrameLayout(ctx).apply {
            setBackgroundColor(base)
            isClickable = true // swallow touches so Instagram underneath can't be used
        }
        root.addView(blob(accent, 150), FrameLayout.LayoutParams(dp(420), dp(420)).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = -dp(170)
            topMargin = -dp(120)
        })
        root.addView(blob(accent2, 120), FrameLayout.LayoutParams(dp(460), dp(460)).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            rightMargin = -dp(200)
            bottomMargin = -dp(160)
        })

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val pad = dp(20)
        content.setPadding(pad, pad + dp(24), pad, pad + dp(8))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            root.setOnApplyWindowInsetsListener { _, insets ->
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                content.setPadding(pad + b.left, pad + b.top, pad + b.right, pad + b.bottom)
                insets
            }
        }
        content.addView(header(status), LinearLayout.LayoutParams(-1, -2))
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        return root to content
    }

    /** Instagram's icon + name on the left, a small status chip on the right. */
    private fun header(status: String) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val igIcon = runCatching { service.packageManager.getApplicationIcon(IgSignatures.PACKAGE) }.getOrNull()
        if (igIcon != null) {
            addView(ImageView(ctx).apply {
                setImageDrawable(igIcon)
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, dp(8).toFloat())
                    }
                }
                clipToOutline = true
            }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(10) })
        }
        addView(text("Instagram", 16f, textColor, bold = true).apply { gravity = Gravity.START }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(text(status, 12f, textDim).apply {
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = rounded(Color.argb(22, 255, 255, 255), 12)
        })
    }

    private fun blob(color: Int, alpha: Int) = View(ctx).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = dp(210).toFloat()
            colors = intArrayOf(withAlpha(color, alpha), Color.TRANSPARENT)
        }
        if (motion()) {
            ObjectAnimator.ofFloat(this, View.TRANSLATION_Y, 0f, dp(36).toFloat()).apply {
                duration = realTime(9_000)
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                fullAnimators += this
                start()
            }
        }
    }

    private fun statsRow(vararg items: Pair<String, String>) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        items.forEachIndexed { i, (value, label) ->
            if (i > 0) {
                addView(View(ctx).apply { setBackgroundColor(Color.argb(30, 255, 255, 255)) }, LinearLayout.LayoutParams(dp(1), dp(30)))
            }
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(text(value, 20f, textColor, bold = true).apply { tabular() })
                addView(caps(label).apply {
                    setPadding(0, dp(4), 0, 0)
                    gravity = Gravity.CENTER
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    private fun card() = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(18))
        background = GradientDrawable().apply {
            cornerRadius = dp(28).toFloat()
            setColor(Color.argb(22, 255, 255, 255))
            setStroke(dp(1), Color.argb(30, 255, 255, 255))
        }
    }

    private fun chip(label: String, onClick: () -> Unit) = text(label, 13f, textColor, bold = true).apply {
        background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(Color.argb(20, 255, 255, 255))
            setStroke(dp(1), Color.argb(34, 255, 255, 255))
        }
        pressable(this, onClick)
    }

    private fun segment(label: String, onClick: () -> Unit) = text(label, 14f, textColor, bold = true).apply {
        pressable(this, onClick)
    }

    private fun styleSegment(v: TextView, selected: Boolean) {
        v.background = if (selected) {
            GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(accent, accent2)).apply { cornerRadius = dp(11).toFloat() }
        } else {
            null
        }
        v.setTextColor(if (selected) Color.WHITE else textDim)
    }

    private enum class Style { WHITE, ACCENT }

    private fun button(label: String, iconRes: Int, style: Style, onClick: () -> Unit) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        val fg = if (style == Style.WHITE) ink else Color.WHITE
        background = if (style == Style.WHITE) {
            rounded(Color.parseColor("#FFF3F3FA"), 18)
        } else {
            GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(accent, accent2)).apply { cornerRadius = dp(18).toFloat() }
        }
        addView(icon(iconRes, fg), LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(10) })
        addView(text(label, 16f, fg, bold = true))
        pressable(this, onClick)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun pressable(v: View, onClick: () -> Unit) {
        v.isClickable = true
        var lastClick = 0L
        v.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - lastClick > 600) { // no double-fires
                lastClick = now
                haptic(it, HapticFeedbackConstants.VIRTUAL_KEY)
                onClick()
            }
        }
        v.setOnTouchListener { view, ev ->
            if (view.isEnabled) when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
            }
            false
        }
    }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        gravity = Gravity.CENTER
        includeFontPadding = false
        if (bold) typeface = semibold()
    }

    private fun caps(s: String) = text(s.uppercase(), 11f, textDim).apply {
        letterSpacing = 0.1f
        typeface = semibold()
        gravity = Gravity.START
    }

    private fun TextView.tabular() {
        fontFeatureSettings = "tnum"
    }

    private fun spacer() = View(ctx)

    private fun centered(w: Int = -2, h: Int = -2) = LinearLayout.LayoutParams(w, h).apply { gravity = Gravity.CENTER_HORIZONTAL }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
    }

    private fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    private fun tinted(res: Int, color: Int): Drawable = service.getDrawable(res)!!.mutate().apply { setTint(color) }

    private fun sized(res: Int, color: Int, sizeDp: Int): Drawable = tinted(res, color).apply { setBounds(0, 0, dp(sizeDp), dp(sizeDp)) }

    private fun icon(res: Int, color: Int) = ImageView(ctx).apply { setImageDrawable(tinted(res, color)) }

    private fun ordinal(n: Int): String {
        val suffix = if (n % 100 in 11..13) "th" else when (n % 10) {
            1 -> "st"
            2 -> "nd"
            3 -> "rd"
            else -> "th"
        }
        return "$n$suffix"
    }

    private fun animatorScale() =
        Settings.Global.getFloat(service.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

    private fun motion() = animatorScale() > 0f

    /** Undo the phone's animator-speed setting so motion keeps real-world timing. */
    private fun realTime(ms: Long): Long {
        val scale = animatorScale()
        return if (scale > 0f) (ms / scale).toLong() else ms
    }

    private fun dp(v: Int) = (v * density).toInt()

    private companion object {
        const val IDLE_ALPHA = 0.8f
        const val TIMES_UP_LOCK_MS = 3_000L

        // Free space above the art, between stats and card, and below the card: the card
        // floats a little above the bottom edge instead of sitting on it.
        const val TOP_WEIGHT = 0.8f
        const val MID_WEIGHT = 0.8f
        const val BOTTOM_WEIGHT = 1f
    }
}
