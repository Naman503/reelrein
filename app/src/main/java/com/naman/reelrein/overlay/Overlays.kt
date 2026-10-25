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
import com.naman.reelrein.telemetry.Usage
import kotlin.math.abs

data class PauseInfo(
    val openNumber: Int,
    val attempts24h: Int,
    val timeToday: String,
    val reelTimeToday: String,
    val reelsToday: Int,
    val comparison: String? = null, // e.g. "12m less on Reels than yesterday so far"
    val mood: Int = 0,              // brain pal mood from today's reels
)

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
    val mood: Int = 0,             // brain pal mood, 0 happy .. 3 fried
)

interface HudActions {
    fun onSpeedLock(on: Boolean)
    fun onAutoScroll(on: Boolean)
    fun onChangeLimit()
    fun onHide()
    fun onEndSession()
    fun onMoved(x: Int, y: Int)
    fun onPanel(open: Boolean) {}
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
    private val lavender = service.getColor(R.color.rr_lavender)
    private val lavenderDeep = service.getColor(R.color.rr_lavender_deep)
    private val lavenderInk = service.getColor(R.color.rr_lavender_ink)
    private val brainInk = Color.parseColor("#FF5C3311")
    private val ink = Color.parseColor("#FF0B0C14")

    private var fullView: View? = null
    private val fullAnimators = mutableListOf<ValueAnimator>()
    private var hud: HudViews? = null

    /** From settings; buttons and the pill skip haptics when off. */
    var haptics = true

    val fullShowing get() = fullView != null

    /** Which full screen is up ("pause", "times_up", "brain_full", "change_limit") and its step, for usage counts. */
    var fullKind: String? = null
        private set
    var fullStep: String? = null
        private set
    /** Called when a screen reaches a step (fixed words only). */
    var onStep: (screen: String, step: String) -> Unit = { _, _ -> }

    private fun step(kind: String, s: String) {
        fullKind = kind
        fullStep = s
        onStep(kind, s)
    }
    val hudShowing get() = hud != null

    // ---------------- Pause screen: breathe -> attempts -> dial ----------------

    /**
     * One Sec-style flow, one calm step at a time:
     *  1. an hourglass drains while you breathe;
     *  2. "N attempts to open Instagram within the last 24 hours", with
     *     "I don't want to open Instagram" as the main button and "Continue on Instagram" quiet;
     *  3. a dial to choose how long, then "Open Instagram for N min".
     */
    fun showPause(
        seconds: Int,
        info: PauseInfo,
        initialMinutes: Int,
        onStart: (minutes: Int) -> Unit,
        onClose: () -> Unit,
    ) {
        val (root, content) = screen("Paused")
        step("pause", "breathe")
        val stage = FrameLayout(ctx)
        content.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))

        // 1. breathe
        val breathe = column()
        breathe.addView(stepDots(1), centered().apply { topMargin = dp(4) })
        breathe.addView(spacer(), LinearLayout.LayoutParams(-1, 0, 1f))
        val glass = HourglassView(ctx)
        breathe.addView(glass, centered(dp(150), dp(230)))
        breathe.addView(text("Take a deep breath", 26f, textColor, bold = true).apply { setPadding(0, dp(36), 0, dp(10)) }, centered())
        val prompt = text("Breathe in…", 17f, textDim)
        breathe.addView(prompt, centered())
        breathe.addView(spacer(), LinearLayout.LayoutParams(-1, 0, 1.3f))
        stage.addView(breathe, FrameLayout.LayoutParams(-1, -1))

        // alternate the prompt every 4 s while the glass drains
        var inhale = true
        val swap = object : Runnable {
            override fun run() {
                if (fullView !== root || breathe.parent == null) return
                inhale = !inhale
                prompt.animate().alpha(0f).setDuration(200).withEndAction {
                    prompt.text = if (inhale) "Breathe in…" else "Breathe out…"
                    prompt.animate().alpha(1f).setDuration(300).start()
                }.start()
                root.postDelayed(this, 4_000)
            }
        }

        glass.onFinished = {
            step("pause", "attempts")
            swapStage(stage, attemptsStage(
                number = info.attempts24h,
                label = if (info.attempts24h == 1) "attempt to open Instagram within the last 24 hours." else "attempts to open Instagram within the last 24 hours.",
                info = info,
                primary = "I don't want to open Instagram",
                onPrimary = onClose,
                secondary = "Continue on Instagram",
                secondaryLockMs = 0,
                onSecondary = {
                    step("pause", "dial")
                    swapStage(stage, dialStage(initialMinutes, "How much time do you need?", onStart, "Never mind, close Instagram", onClose, step = 3))
                },
            ))
        }
        showFull(root, content)
        glass.start(seconds * 1_000L)
        root.postDelayed(swap, 4_000)
    }

    // ---------------- Time's up / change limit ----------------

    fun showTimesUp(info: TimesUpInfo, initialMinutes: Int, onStart: (minutes: Int) -> Unit, onClose: () -> Unit) {
        val (root, content) = screen("Time's up")
        step("times_up", "times_up")
        val stage = FrameLayout(ctx)
        content.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))
        swapStage(stage, timesUpStage(info, onClose) {
            step("times_up", "dial")
            swapStage(stage, dialStage(initialMinutes, "How much more time?", onStart, "Close Instagram", onClose))
        }, animate = false)
        showFull(root, content)
    }

    /** Daily reel cap reached: the brain pal is fried. Close, or keep watching after a beat. */
    fun showBrainFull(reels: Int, cap: Int, info: TimesUpInfo, onClose: () -> Unit, onContinue: () -> Unit) {
        val (root, content) = screen("Brain full")
        step("brain_full", "brain_full")
        val v = column()
        v.addView(spacer(), LinearLayout.LayoutParams(-1, 0, 0.6f))
        v.addView(BrainView(ctx).apply { mood = 3 }, centered(dp(170), dp(170)))
        v.addView(text("Your brain is full", 30f, textColor, bold = true).apply { setPadding(0, dp(22), 0, dp(10)) }, centered())
        v.addView(text("That's $reels reels today. Your daily cap is $cap.", 17f, textDim).apply { setPadding(dp(12), 0, dp(12), 0) }, centered())
        v.addView(text("${info.reelTimeToday} on Reels today", 14f, textDim).apply { setPadding(0, dp(8), 0, 0); alpha = 0.8f }, centered())
        v.addView(spacer(), LinearLayout.LayoutParams(-1, 0, 1f))
        addActions(v, "Give my brain a break", onClose, "Keep watching", secondaryLockMs = BRAIN_LOCK_MS, onSecondary = onContinue)
        content.addView(v, LinearLayout.LayoutParams(-1, 0, 1f))
        showFull(root, content)
    }

    /** From the panel: pick a new limit mid-session (counted from now). */
    fun showChangeLimit(info: TimesUpInfo, initialMinutes: Int, onStart: (minutes: Int) -> Unit, onCancel: () -> Unit) {
        val (root, content) = screen("Session")
        step("change_limit", "dial")
        val stage = FrameLayout(ctx)
        content.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))
        swapStage(stage, dialStage(initialMinutes, "Change your limit", onStart, "Keep my current limit", onCancel,
            subtitle = "So far: ${info.sessionReels} ${if (info.sessionReels == 1) "reel" else "reels"} in ${info.sessionReelTime}. The new limit starts now."), animate = false)
        showFull(root, content)
    }

    private fun timesUpStage(info: TimesUpInfo, onClose: () -> Unit, onContinue: () -> Unit): View {
        val v = column()
        v.addView(spacer(), LinearLayout.LayoutParams(-1, 0, 0.8f))
        v.addView(HourglassView(ctx).apply { fixedProgress = 1f }, centered(dp(96), dp(148)))
        v.addView(text("Time's up", 34f, textColor, bold = true).apply { setPadding(0, dp(28), 0, dp(12)) }, centered())
        v.addView(text(
            "You spent ${info.sessionReelTime} on Reels and watched ${info.sessionReels} ${if (info.sessionReels == 1) "reel" else "reels"}.",
            17f, textDim,
        ).apply { setPadding(dp(12), 0, dp(12), 0) }, centered())
        v.addView(text("${info.reelTimeToday} on Reels today", 14f, textDim).apply { setPadding(0, dp(8), 0, 0); alpha = 0.8f }, centered())
        v.addView(spacer(), LinearLayout.LayoutParams(-1, 0, 1f))
        addActions(v, "Close Instagram", onClose, "Continue on Instagram", secondaryLockMs = TIMES_UP_LOCK_MS, onSecondary = onContinue)
        return v
    }

    private fun attemptsStage(
        number: Int,
        label: String,
        info: PauseInfo,
        primary: String,
        onPrimary: () -> Unit,
        secondary: String,
        secondaryLockMs: Long,
        onSecondary: () -> Unit,
    ): View {
        val v = column()
        v.addView(stepDots(2), centered().apply { topMargin = dp(4) })
        v.addView(spacer(), LinearLayout.LayoutParams(-1, 0, 0.3f))
        v.addView(BrainView(ctx).apply { mood = info.mood }, centered(dp(84), dp(84)))
        v.addView(text("$number", 80f, textColor, bold = true).apply { tabular(); includeFontPadding = true }, centered())
        v.addView(text(label, 18f, textColor).apply { setPadding(dp(16), dp(2), dp(16), 0) }, centered())

        // Today at a glance
        val grid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val row1 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val row2 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(statTile(R.drawable.ic_schedule, info.reelTimeToday, "on Reels today"), LinearLayout.LayoutParams(0, -2, 1f))
        row1.addView(statTile(R.drawable.ic_movie, "${info.reelsToday}", "reels watched"), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
        row2.addView(statTile(R.drawable.ic_today, info.timeToday, "in Instagram today"), LinearLayout.LayoutParams(0, -2, 1f))
        row2.addView(statTile(R.drawable.ic_login, "${info.openNumber}", "opens today"), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
        grid.addView(row1)
        grid.addView(row2, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        v.addView(grid, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(28) })
        if (info.comparison != null) {
            v.addView(text(info.comparison, 14f, textDim).apply { setPadding(0, dp(14), 0, 0) }, centered())
        }

        v.addView(spacer(), LinearLayout.LayoutParams(-1, 0, 1f))
        addActions(v, primary, onPrimary, secondary, secondaryLockMs, onSecondary)
        return v
    }

    private fun statTile(iconRes: Int, value: String, label: String) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(Color.argb(16, 255, 255, 255))
            setStroke(dp(1), Color.argb(22, 255, 255, 255))
        }
        addView(icon(iconRes, lavender), LinearLayout.LayoutParams(dp(18), dp(18)))
        addView(text(value, 22f, textColor, bold = true).apply { gravity = Gravity.START; tabular(); setPadding(0, dp(10), 0, dp(2)) })
        addView(text(label, 12.5f, textDim).apply { gravity = Gravity.START })
    }

    /** Three small dots at the top of the pause flow: where you are (breathe · decide · time). */
    private fun stepDots(step: Int) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        for (i in 1..3) {
            addView(View(ctx).apply {
                background = rounded(if (i == step) lavender else Color.argb(50, 255, 255, 255), 4)
            }, LinearLayout.LayoutParams(if (i == step) dp(22) else dp(8), dp(8)).apply {
                marginStart = dp(3); marginEnd = dp(3)
            })
        }
    }

    private fun dialStage(
        initial: Int,
        title: String,
        onStart: (Int) -> Unit,
        secondary: String,
        onSecondary: () -> Unit,
        subtitle: String? = null,
        step: Int? = null,
    ): View {
        val v = column()
        if (step != null) v.addView(stepDots(step), centered().apply { topMargin = dp(4) })
        v.addView(spacer(), LinearLayout.LayoutParams(-1, 0, 0.45f))
        v.addView(text(title, 26f, textColor, bold = true), centered())
        if (subtitle != null) v.addView(text(subtitle, 14f, textDim).apply { setPadding(dp(16), dp(10), dp(16), 0) }, centered())
        val primaryLabel = { m: Int -> "Open Instagram for $m min" }
        lateinit var primaryText: TextView
        val dial = ArcDial(ctx, lavenderDeep, lavender, initial).apply {
            haptics = this@Overlays.haptics
            onChange = { m -> primaryText.text = primaryLabel(m) }
        }
        v.addView(dial, centered(dp(280), dp(280)).apply { topMargin = dp(24) })

        val picks = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        listOf(5, 10, 15, 30).forEach { m ->
            picks.addView(chip("$m min") { dial.set(m) }, LinearLayout.LayoutParams(dp(68), dp(34)).apply {
                marginStart = dp(4); marginEnd = dp(4)
            })
        }
        v.addView(picks, centered().apply { topMargin = dp(8) })
        v.addView(spacer(), LinearLayout.LayoutParams(-1, 0, 1f))
        val btn = primaryPill(primaryLabel(dial.value)) { onStart(dial.value) }
        primaryText = btn
        v.addView(btn, LinearLayout.LayoutParams(-1, dp(62)))
        v.addView(quietLink(secondary) { onSecondary() }, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(6) })
        v.addView(spacer(), LinearLayout.LayoutParams(-1, 0, 0.35f))
        return v
    }

    /** Big lavender pill + quiet text link underneath (One Sec's pattern), lifted off the bottom edge. */
    private fun addActions(
        v: LinearLayout,
        primary: String,
        onPrimary: () -> Unit,
        secondary: String,
        secondaryLockMs: Long,
        onSecondary: () -> Unit,
    ) {
        v.addView(primaryPill(primary) { onPrimary() }, LinearLayout.LayoutParams(-1, dp(62)))
        val link = quietLink(secondary) { onSecondary() }
        v.addView(link, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(6) })
        v.addView(spacer(), LinearLayout.LayoutParams(-1, 0, 0.35f))
        if (secondaryLockMs > 0) {
            // A short beat before "continue" works, so it's a choice rather than a reflex.
            link.isEnabled = false
            link.alpha = 0.35f
            var left = (secondaryLockMs / 1000).toInt()
            link.text = "$secondary ($left)"
            val tick = object : Runnable {
                override fun run() {
                    if (link.parent == null) return
                    left--
                    if (left > 0) {
                        link.text = "$secondary ($left)"
                        link.postDelayed(this, 1_000)
                    } else {
                        link.text = secondary
                        link.isEnabled = true
                        link.animate().alpha(1f).setDuration(200).start()
                    }
                }
            }
            link.postDelayed(tick, 1_000)
        }
    }

    private fun swapStage(stage: FrameLayout, next: View, animate: Boolean = true) {
        val old = if (stage.childCount > 0) stage.getChildAt(0) else null
        next.alpha = if (animate && old != null) 0f else 1f
        stage.addView(next, FrameLayout.LayoutParams(-1, -1))
        if (old != null) {
            old.animate().alpha(0f).setDuration(realTime(180)).withEndAction { stage.removeView(old) }.start()
            next.animate().alpha(1f).setStartDelay(realTime(120)).setDuration(realTime(260)).start()
        }
        haptic(stage, HapticFeedbackConstants.CONFIRM)
    }

    private fun column() = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
    }

    private fun primaryPill(label: String, onClick: () -> Unit) = text(label, 17f, lavenderInk, bold = true).apply {
        background = rounded(lavender, 31)
        pressable(this, onClick)
    }

    private fun quietLink(label: String, onClick: () -> Unit) = text(label, 17f, textColor, bold = true).apply {
        pressable(this, onClick)
    }

    // ---------------- Cover ----------------
    // An opaque window in the pause screen's own colours, drawn the instant Instagram appears.
    // Starting the pause screen takes a moment and Instagram may jump back on top while it
    // finishes launching; the cover hides all of that. It lets touches through and lifts
    // once the pause screen is settled underneath.

    private var cover: View? = null

    fun showCover() {
        if (cover != null) return
        val v = View(ctx).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(service.getColor(R.color.rr_bg_top), service.getColor(R.color.rr_bg_mid), service.getColor(R.color.rr_bg_bottom)),
            )
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.OPAQUE,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        runCatching { wm.addView(v, lp) }.onSuccess { cover = v }.onFailure { Usage.nonFatal("cover_add", it) }
    }

    fun hideCover() {
        val v = cover ?: return
        cover = null
        v.animate().alpha(0f).setDuration(realTime(140)).withEndAction { runCatching { wm.removeView(v) } }.start()
    }

    val coverShowing get() = cover != null

    fun hideFull() {
        hideCover()
        fullAnimators.forEach { it.cancel() }
        fullAnimators.clear()
        if (fullView == null) return
        fullView = null
        fullKind = null
        fullStep = null
        Gate.close()
        onFullHidden()
    }

    /**
     * Full screens open as [GateActivity] on top of Instagram, not as a window above the whole
     * phone: Home, Back and the notification shade keep working, and Instagram pauses itself.
     */
    private fun showFull(root: View, content: View) {
        fullAnimators.forEach { it.cancel() }
        fullAnimators.clear()
        fullView = root
        content.alpha = 0f
        content.translationY = dp(14).toFloat()
        content.animate().alpha(1f).translationY(0f).setStartDelay(realTime(60)).setDuration(realTime(320))
            .setInterpolator(DecelerateInterpolator()).start()
        Gate.pendingRoot = root
        val open = Gate.current?.get()
        if (open != null) {
            open.show(root) // already up: swap the screen in place
        } else {
            showCover() // instant, while the gate screen starts underneath
            Gate.open(service)
        }
        onFullShown()
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
        val brain: BrainView?,
        val brainCount: TextView?,
        val panelBrain: BrainView,
        val moodTitle: TextView,
        val moodSub: TextView,
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
                    "brain" -> { // brain pal: warm cream pill
                        cornerRadius = sdp(30).toFloat()
                        setColor(Color.parseColor("#FFFBE7BF"))
                        setStroke(dp(1), Color.argb(60, 160, 112, 46))
                    }
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

        // Brain pal style: the brain mascot and today's reel count, like a little friend
        // keeping score. (The time on reels can sit next to it; other items are for the
        // classic styles.)
        val brainStyle = config.style == "brain"
        var brain: BrainView? = null
        var brainCount: TextView? = null
        if (brainStyle) {
            pill.setPadding(sdp(8), sdp(6), sdp(16), sdp(6))
            brain = BrainView(ctx)
            pill.addView(brain, LinearLayout.LayoutParams(sdp(40), sdp(40)).apply { marginEnd = sdp(8) })
            brainCount = text("0", 24f * k, brainInk, bold = true).apply {
                tabular()
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 800, false)
            }
            pill.addView(brainCount)
            groups++
        }

        if (!brainStyle && config.brain) {
            brain = BrainView(ctx)
            pill.addView(brain, LinearLayout.LayoutParams(sdp(34), sdp(34)).apply { marginEnd = sdp(4) })
            groups++
        }

        var ring: MiniRing? = null
        var reelTime: TextView? = null
        if (brainStyle && config.reelTime) {
            pill.addView(View(ctx).apply { background = rounded(Color.argb(60, 92, 51, 17), 1) }, LinearLayout.LayoutParams(dp(1), sdp(20)).apply {
                marginStart = sdp(11); marginEnd = sdp(11)
            })
            reelTime = pillText(16f, brainInk).also { pill.addView(it) }
        }
        if (config.reelTime && !brainStyle) {
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
        if (config.todayTime && !brainStyle) {
            divider()
            pillIcon(R.drawable.ic_today, textDim, 16)
            todayTime = pillText(15f, textDim, bold = false).also { pill.addView(it) }
        }
        var timeLeft: TextView? = null
        if (config.timeLeft && !brainStyle) {
            divider()
            pillIcon(R.drawable.ic_timer, textColor, 17)
            timeLeft = pillText(16f).also { pill.addView(it) }
        }
        var reels: TextView? = null
        var todayReels: TextView? = null
        if ((config.reels || config.todayReels) && !brainStyle) {
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

        // Header: the brain pal and how it's doing today.
        val panelBrain = BrainView(ctx)
        val moodTitle = text("", 16f, textColor, bold = true).apply { gravity = Gravity.START }
        val moodSub = text("", 12.5f, textDim).apply { gravity = Gravity.START }
        panel.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(FrameLayout(ctx).apply {
                background = rounded(Color.parseColor("#FFFBE7BF"), 16)
                addView(panelBrain, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER))
            }, LinearLayout.LayoutParams(dp(54), dp(54)).apply { marginEnd = dp(12) })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(moodTitle)
                addView(moodSub)
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }, LinearLayout.LayoutParams(-1, -2))
        first = false

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
            root, pill, config, ring, reelTime, todayTime, timeLeft, reels, todayReels, speedChip, brain, brainCount, panelBrain, moodTitle, moodSub,
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
                                else -> {
                                    val open = panel.visibility != View.VISIBLE
                                    setPanel(views, open)
                                    actions.onPanel(open)
                                }
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

        runCatching { wm.addView(root, lp) }.onFailure { Usage.nonFatal("pill_add", it) }.onSuccess {
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
        val brainStyle = h.config.style == "brain"
        h.brain?.mood = s.mood
        h.panelBrain.mood = s.mood
        h.moodTitle.text = listOf("Happy brain", "A bit tired", "Getting worried", "Brain is fried")[s.mood.coerceIn(0, 3)]
        h.moodSub.text = "${s.todayReels} reels today · ${s.todayReelTime} on Reels"
        h.brainCount?.text = s.todayReels.toString()
        h.reelTime?.apply {
            text = s.reelTime
            setTextColor(if (brainStyle) brainInk else if (s.warning && h.config.ring) warn else textColor)
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
                    if (brainStyle) {
                        chip.setTextColor(brainInk)
                        chip.background = rounded(Color.argb(40, 92, 51, 17), 16)
                        return@let
                    }
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
            runCatching { wm.addView(v, lp) }.onSuccess { cancelPad = v }.onFailure { Usage.nonFatal("cancel_pad_add", it) }
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
        // One Sec's calm look: a plain dark gradient, cool at the top, warm at the bottom.
        val root = FrameLayout(ctx).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(service.getColor(R.color.rr_bg_top), service.getColor(R.color.rr_bg_mid), service.getColor(R.color.rr_bg_bottom)),
            )
            isClickable = true // swallow touches so Instagram underneath can't be used
        }

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val pad = dp(24)
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
        const val BRAIN_LOCK_MS = 5_000L

        // Free space above the art, between stats and card, and below the card: the card
        // floats a little above the bottom edge instead of sitting on it.
        const val TOP_WEIGHT = 0.8f
        const val MID_WEIGHT = 0.8f
        const val BOTTOM_WEIGHT = 1f
    }
}
