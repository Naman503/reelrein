package com.naman.reelrein

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.SharedPreferences
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.naman.reelrein.SessionState.Session
import com.naman.reelrein.overlay.Gate
import com.naman.reelrein.overlay.HudActions
import com.naman.reelrein.overlay.HudState
import com.naman.reelrein.overlay.Overlays
import com.naman.reelrein.overlay.PauseInfo
import com.naman.reelrein.overlay.TimesUpInfo
import java.lang.ref.WeakReference
import com.naman.reelrein.telemetry.Usage

/**
 * The only long-running piece. While Instagram is closed it receives nothing but app-switch
 * events (and in bank-safe mode it isn't running at all); scroll/click events are switched on
 * only while Instagram is in front.
 *
 * Flow: Instagram opens -> pause screen -> pick a session length -> counter pill + reel
 * counting (+ 2x if enabled) -> time's up screen -> close or +5 min.
 */
class ReelReinService : AccessibilityService(), SharedPreferences.OnSharedPreferenceChangeListener {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var stats: StatsStore
    private lateinit var overlays: Overlays
    private lateinit var speed: SpeedLock
    private lateinit var audio: AudioGate
    private lateinit var autoScroll: AutoScroll
    private val state = SessionState
    private val counter get() = state.counter

    private var connected = false
    private var igInFront = false
    private var lastTick = 0L
    private var pendingMs = 0L
    private var pendingReelMs = 0L
    private var ticks = 0
    private var onReels = false

    // Speed: after a real touch cancels a HOLD, or the pill was tapped for 1x,
    // wait until the next reel settles before turning 2x on again.
    private var waitingForNextReel = false
    private var lostAt = 0L
    private var lastScrollUptime = 0L

    // Which reel (pager position) is on screen, and how many lock attempts it has had.
    private var currentIndex = -1
    private var attempts = 0

    override fun onServiceConnected() {
        Usage.start(this)
        Usage.once("setup_accessibility")
        prefs = Keys.prefs(this)
        stats = StatsStore(this)
        audio = AudioGate(this)
        audio.restore() // in case a previous run was killed while Instagram was muted
        // No muting needed any more: Instagram pauses itself behind the pause screen.
        overlays = Overlays(this, onFullShown = {}, onFullHidden = {})
        Gate.onBack = { if (igInFront) closeInstagram() else overlays.hideFull() }
        Gate.onLeave = { onGateLeft() }
        Gate.onResumed = {
            // Lift the cover once the pause screen has been in front for a moment.
            handler.removeCallbacks(dropCover)
            handler.postDelayed(dropCover, COVER_SETTLE_MS)
        }
        Gate.log = { m -> debug { "gate: $m" } }
        speed = SpeedLock(
            this, handler,
            onLost = { onHoldLost() },
            readHint = ::readSpeedHint,
            pagerMovedSince = { since -> lastScrollUptime > since },
            onChanged = { refreshHud() },
            cancelPoint = { if (igInFront) overlays.ensureCancelPad() else null },
            log = { m -> debug { m } },
            lockConfirmed = { visibleText(IgSignatures.TOAST_LOCKED) },
            onResult = ::onLockResult,
        )
        autoScroll = AutoScroll(this, handler) { m -> debug { m } }
        showSeconds = prefs.getBoolean(Keys.TIME_SECONDS, true)
        prefs.registerOnSharedPreferenceChangeListener(this)
        connected = true
        instanceRef = WeakReference(this)
        setListening(false)
        // Bank-safe mode switches us on because Instagram just opened: catch up right away.
        handler.postDelayed({ if (!igInFront && instagramOnTop()) enterInstagram() }, 120)
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (!connected) return
        val pkg = e.packageName?.toString() ?: return
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> onWindowChanged(pkg, e)
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> if (igInFront && pkg == IgSignatures.PACKAGE) onScrolled(e)
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> if (igInFront && pkg == IgSignatures.PACKAGE) scheduleSurfaceCheck(600)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (connected) {
            if (igInFront) leaveInstagram()
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            overlays.hideAll()
            audio.restore()
        }
        if (instanceRef.get() === this) instanceRef = WeakReference(null)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ---------------- foreground tracking ----------------

    private fun onWindowChanged(pkg: String, e: AccessibilityEvent) {
        debug { "window pkg=$pkg cls=${e.className}" }
        when {
            pkg == IgSignatures.PACKAGE -> {
                handler.removeCallbacks(verifyLeft)
                // Instagram jumped back above the pause screen (it finishes launching after
                // we open): hide it again and put the pause screen back in front.
                if (overlays.fullShowing && !Gate.resumed) {
                    overlays.showCover()
                    Gate.open(this)
                }
                if (!igInFront) enterInstagram() else scheduleSurfaceCheck(400)
            }
            pkg == packageName || pkg in IgSignatures.TRANSIENT_PACKAGES || pkg == imePackage() -> Unit
            // Another app's window appeared. It may only be a popup or overlay on top of
            // Instagram (One Sec, chat heads, call screens), so check before treating it as leaving.
            igInFront -> {
                handler.removeCallbacks(verifyLeft)
                handler.postDelayed(verifyLeft, 250)
            }
        }
    }

    private val verifyLeft = Runnable {
        if (igInFront && !instagramOnTop()) leaveInstagram()
    }

    /** True when the app window the user is looking at belongs to Instagram. */
    private fun instagramOnTop(): Boolean {
        val apps = runCatching { windows }.getOrNull().orEmpty()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        if (apps.isEmpty()) return rootInActiveWindow?.packageName?.toString() == IgSignatures.PACKAGE
        val top = apps.firstOrNull { it.isActive } ?: apps.maxByOrNull { it.layer }
        return top?.root?.packageName?.toString() == IgSignatures.PACKAGE
    }

    private fun enterInstagram() {
        handler.removeCallbacks(sleep)
        igInFront = true
        setListening(true)
        lastTick = now()
        handler.removeCallbacks(tick)
        handler.post(tick)

        if (state.session != null && now() - state.lastLeftAt < relockMs()) {
            // Came back quickly (share sheet, notification, app switch): same session.
            overlays.ensureCancelPad()
            scheduleSurfaceCheck(500)
            refreshHud()
            return
        }

        state.session = null
        state.autoScrollDone = false
        state.hudHidden = false
        counter.reset()
        stats.addOpen()
        Usage.count("instagram_opened")
        if (prefs.getBoolean(Keys.INTERCEPT, true)) showPause() else startSession(null)
    }

    private fun leaveInstagram() {
        if (!igInFront) return
        accrue()
        flushTime()
        igInFront = false
        state.lastLeftAt = now()
        handler.removeCallbacks(tick)
        handler.removeCallbacks(surfaceCheck)
        handler.removeCallbacks(engage)
        handler.removeCallbacks(verifyLeft)
        speed.forget()
        setOnReels(false)
        overlays.hideAll()
        // A session that hit its limit is over; reopening starts with the pause screen again.
        if (state.session?.timesUpShown == true) state.session = null
        setListening(false)
        Usage.flushSoon()
        if (prefs.getBoolean(Keys.BANK_SAFE, false) && LaunchWatcher.ready(this)) {
            handler.removeCallbacks(sleep)
            handler.postDelayed(sleep, SLEEP_AFTER_MS)
        }
    }

    /** Bank-safe mode: switch accessibility off until the watcher sees Instagram again. */
    private val sleep = Runnable {
        if (igInFront) return@Runnable
        debug { "bank-safe: switching off" }
        audio.restore()
        disableSelf()
    }

    /** Subscribe to scroll/click events (and not-important views) only while Instagram is in front. */
    private fun setListening(active: Boolean) {
        val info = serviceInfo ?: return
        info.eventTypes = if (active) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED or
                AccessibilityEvent.TYPE_VIEW_CLICKED or
                AccessibilityEvent.TYPE_VIEW_LONG_CLICKED
        } else {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        }
        val baseFlags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        info.flags = if (active) baseFlags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS else baseFlags
        serviceInfo = info
    }

    // ---------------- session ----------------

    private fun showPause() {
        val day = stats.day()
        val base = prefs.getInt(Keys.PAUSE_SECONDS, 5)
        val seconds = if (prefs.getBoolean(Keys.ESCALATE, true)) {
            (base + (day.opens - 1) * ESCALATE_STEP_S).coerceIn(base, MAX_PAUSE_S.coerceAtLeast(base))
        } else {
            base
        }
        speed.forget()
        Usage.count("pause_shown")
        Usage.once("first_pause")
        overlays.showPause(
            seconds = seconds,
            info = PauseInfo(day.opens, stats.opensLast24h(), formatDuration(day.ms), formatDuration(day.reelMs), day.reels, comparison(), brainMood()),
            initialMinutes = lastMinutes(),
            onStart = { minutes ->
                rememberMinutes(minutes)
                Usage.count("pause_result", props = mapOf("choice" to "opened"))
                Usage.count("limit_chosen", props = mapOf("minutes" to minutesBucket(minutes)))
                startSession(minutes * 60_000L)
                backToInstagram()
            },
            onClose = {
                Usage.count("pause_result", props = mapOf("choice" to "closed"))
                closeInstagram()
            },
        )
        refreshHud()
    }

    private fun startSession(limitMs: Long?) {
        overlays.ensureCancelPad() // ready before the first 2x press
        state.session = Session(limitMs)
        lastTick = now()
        refreshHud()
        scheduleSurfaceCheck(300)
    }

    private fun showTimesUp(s: Session) {
        s.timesUpShown = true
        speed.forget()
        Usage.count("times_up")
        overlays.showTimesUp(
            sessionInfo(s),
            initialMinutes = lastMinutes(),
            onStart = { minutes ->
                rememberMinutes(minutes)
                Usage.count("times_up_result", props = mapOf("choice" to "extended"))
                s.limitMs = s.usedMs + minutes * 60_000L
                s.timesUpShown = false
                backToInstagram()
            },
            onClose = {
                Usage.count("times_up_result", props = mapOf("choice" to "closed"))
                closeInstagram()
            },
        )
        refreshHud()
    }

    /** How long you can be away from Instagram before the pause screen comes back (0 = always). */
    private fun relockMs() = prefs.getInt(Keys.RELOCK_SECONDS, DEFAULT_RELOCK_S) * 1000L

    private fun lastMinutes() = prefs.getInt(Keys.LAST_MINUTES, 10)

    private fun rememberMinutes(m: Int) = prefs.edit().putInt(Keys.LAST_MINUTES, m).apply()

    /** Limits in a few groups, so the report says "5–10 min", not every exact number. */
    private fun minutesBucket(m: Int) = when {
        m <= 5 -> "1-5"
        m <= 10 -> "6-10"
        m <= 15 -> "11-15"
        m <= 30 -> "16-30"
        m <= 60 -> "31-60"
        else -> "60+"
    }

    /**
     * "Close Instagram" / "I don't want to open Instagram" / Back on the pause screen: go home,
     * then end Instagram's background process so it's really closed, not just hidden.
     */
    private fun closeInstagram() {
        debug { "close Instagram" }
        overlays.hideFull()
        speed.forget()
        state.session = null
        performGlobalAction(GLOBAL_ACTION_HOME)
        killInstagramSoon()
    }

    /**
     * Android protects a just-used app for a while, so try now and again a minute later
     * (skipped if you've gone back to Instagram in the meantime).
     */
    private fun killInstagramSoon() {
        for (delay in listOf(KILL_DELAY_MS, KILL_RETRY_MS)) {
            handler.postDelayed({
                if (igInFront) return@postDelayed // you went back to Instagram
                runCatching {
                    getSystemService(android.app.ActivityManager::class.java).killBackgroundProcesses(IgSignatures.PACKAGE)
                }
                debug { "asked Android to close Instagram's background process" }
            }, delay)
        }
    }

    /** "Open Instagram for N min" / Continue: our screen closes and Instagram resumes underneath. */
    private fun backToInstagram() {
        overlays.hideFull()
        lastTick = now()
        refreshHud()
        scheduleSurfaceCheck(500)
        waitingForNextReel = false
        scheduleEngage(SETTLE_MS + 300)
    }

    private val dropCover = Runnable { if (Gate.resumed) overlays.hideCover() }

    /**
     * The pause screen went to the background. If that's Instagram popping back on top
     * (it finishes launching after we open), put the pause screen back. If it's Home or
     * another app, treat it as closing Instagram.
     */
    private fun onGateLeft() {
        if (!overlays.fullShowing) return
        handler.removeCallbacks(checkGateLeft)
        handler.postDelayed(checkGateLeft, 250)
    }

    private val checkGateLeft = Runnable {
        if (!overlays.fullShowing || Gate.resumed) return@Runnable
        if (igInFront && instagramOnTop()) {
            overlays.showCover()
            Gate.open(this)
            return@Runnable
        }
        overlays.hideFull()
        if (!igInFront) return@Runnable // a preview from the settings screen
        val s = state.session
        if (s == null || s.timesUpShown) {
            state.session = null
            leaveInstagram()
            killInstagramSoon()
        }
    }

    /** "12m less on Reels than yesterday so far" (compares with yesterday up to this time). */
    private fun comparison(): String? {
        val today = stats.day()
        val yesterday = stats.day(java.time.LocalDate.now().minusDays(1))
        if (yesterday.reelMs <= 0) return null
        val diff = today.reelMs - yesterday.reelMs
        return when {
            diff <= -60_000 -> "${formatDuration(-diff)} less on Reels than all of yesterday"
            diff >= 60_000 -> "${formatDuration(diff)} more on Reels than all of yesterday"
            else -> "About the same time on Reels as yesterday"
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!igInFront) return
            accrue()
            val s = state.session
            val limit = s?.limitMs
            if (s != null && limit != null && !s.timesUpShown && s.usedMs >= limit) showTimesUp(s)
            if (pendingMs >= FLUSH_MS) flushTime()
            // Safety net for a missed "left Instagram" event.
            if (++ticks % 5 == 0 && !overlays.fullShowing && !instagramOnTop()) {
                leaveInstagram()
                return
            }
            refreshHud()
            handler.postDelayed(this, 1_000)
        }
    }

    /** Time only counts while a session is running and no full-screen overlay covers Instagram. */
    private fun accrue() {
        val t = now()
        val d = t - lastTick
        lastTick = t
        val s = state.session ?: return
        if (overlays.fullShowing || d <= 0) return
        s.usedMs += d
        pendingMs += d
        if (onReels) {
            s.reelMs += d
            pendingReelMs += d
        }
    }

    private fun flushTime() {
        stats.addTimeMs(pendingMs, pendingReelMs)
        Usage.minutes("instagram_minutes", pendingMs)
        Usage.minutes("reel_minutes", pendingReelMs)
        pendingMs = 0
        pendingReelMs = 0
    }

    // ---------------- reels ----------------

    private fun onScrolled(e: AccessibilityEvent) {
        lastScrollUptime = SystemClock.uptimeMillis()
        if (state.session == null) return
        val src = e.source
        val id = src?.viewIdResourceName
        debug { "scroll id=$id cls=${e.className} from=${e.fromIndex} to=${e.toIndex} count=${e.itemCount}" }

        if (!IgSignatures.isReelsId(id) || (src != null && !isBig(src))) {
            // Another large list moved (feed, profile grid): we're not on Reels any more.
            if (onReels && src != null && isBig(src)) setOnReels(false)
            return
        }
        state.knownReelsId = id
        setOnReels(true)
        val move = counter.onScroll(e, screenHeight())
        if (move == ReelCounter.Move.FORWARD) addReel()
        // A different reel is on screen, however it got there (swipe, Auto scroll, refresh):
        // whatever was locked belonged to the previous reel.
        if (e.fromIndex >= 0 && e.fromIndex == e.toIndex) {
            if (e.fromIndex != currentIndex) {
                currentIndex = e.fromIndex
                onNewReel()
            }
        } else if (move != ReelCounter.Move.NONE) {
            onNewReel() // pager without indexes
        }
        // Every scroll event pushes this back, so the press only lands once the reel has stopped.
        // Auto scroll still to do (e.g. the first reel was an ad)? Try once the reel settles.
        if (!state.autoScrollDone && prefs.getBoolean(Keys.AUTO_SCROLL, false)) {
            handler.removeCallbacks(autoScrollWhenSettled)
            handler.postDelayed(autoScrollWhenSettled, SETTLE_MS)
        }
        scheduleEngage(SETTLE_MS)
    }

    /** A different reel is on screen: 2x has to be applied again. */
    private fun onNewReel() {
        waitingForNextReel = false
        attempts = 0
        speed.forget()
        refreshHud()
    }

    /** An unconfirmed lock (no "Locked at 2x" toast) gets one more try on the same reel. */
    private fun onLockResult(r: SpeedLock.Result) {
        if (r == SpeedLock.Result.LOCKED) Usage.count("speed_locked")
        if (r == SpeedLock.Result.UNCONFIRMED && attempts < 1) {
            attempts++
            scheduleEngage(RETRY_MS)
        }
    }

    /**
     * A visible, on-screen node in any Instagram window containing [text] (Instagram's own UI
     * labels only). All windows, because toasts like "Locked at 2x speed" live in their own.
     */
    private fun visibleText(text: String): Boolean {
        val h = screenHeight()
        val r = Rect()
        val roots = runCatching { windows }.getOrNull().orEmpty().mapNotNull { it.root }
            .ifEmpty { listOfNotNull(rootInActiveWindow) }
            .filter { it.packageName?.toString() == IgSignatures.PACKAGE }
        return roots.any { root ->
            root.findAccessibilityNodeInfosByText(text).any { n ->
                n.getBoundsInScreen(r)
                n.isVisibleToUser && r.top >= 0 && r.bottom <= h && r.height() > 0
            }
        }
    }

    private fun setOnReels(value: Boolean) {
        if (onReels == value) return
        onReels = value
        debug { "onReels=$value" }
        if (value) {
            if (state.session != null && counter.markEntered()) addReel()
            waitingForNextReel = false
            if (!maybeSetAutoScroll()) scheduleEngage(SETTLE_MS)
        } else {
            handler.removeCallbacks(engage)
            speed.forget()
            currentIndex = -1 // coming back may be a different pager
        }
        refreshHud()
    }

    private fun addReel() {
        val s = state.session ?: return
        s.reels++
        stats.addReels(1)
        Usage.count("reels")
        refreshHud()
        checkReelCap()
    }

    /**
     * Daily reel cap: at the cap (and every 25 reels after, if you keep going) the brain pal
     * is "full" and Instagram pauses behind a screen saying so.
     */
    private fun checkReelCap() {
        val cap = prefs.getInt(Keys.REEL_CAP, 0)
        if (cap <= 0 || overlays.fullShowing) return
        val today = stats.day()
        val runtime = getSharedPreferences("runtime", MODE_PRIVATE)
        val ackDay = runtime.getString("cap_ack_day", "")
        val ackAt = if (ackDay == today.date.toString()) runtime.getInt("cap_ack_reels", 0) else 0
        val next = if (ackAt > 0) ackAt + CAP_REMIND_EVERY else cap
        if (today.reels < next) return
        val s = state.session ?: return
        speed.forget()
        Usage.count("reel_cap_hit")
        overlays.showBrainFull(
            reels = today.reels,
            cap = cap,
            info = sessionInfo(s),
            onClose = {
                Usage.count("reel_cap_result", props = mapOf("choice" to "closed"))
                closeInstagram()
            },
            onContinue = {
                Usage.count("reel_cap_result", props = mapOf("choice" to "continued"))
                runtime.edit().putString("cap_ack_day", today.date.toString()).putInt("cap_ack_reels", today.reels).apply()
                backToInstagram()
            },
        )
        refreshHud()
    }

    private fun brainMood(): Int = com.naman.reelrein.overlay.BrainView.moodFor(stats.day().reels, prefs.getInt(Keys.REEL_CAP, 0))

    private fun scheduleSurfaceCheck(delayMs: Long) {
        handler.removeCallbacks(surfaceCheck)
        handler.postDelayed(surfaceCheck, delayMs)
    }

    /** After taps and window changes, look for the Reels pager on screen (bounded search). */
    private val surfaceCheck = Runnable {
        if (!igInFront || state.session == null) return@Runnable
        val root = rootInActiveWindow ?: return@Runnable
        if (root.packageName?.toString() != IgSignatures.PACKAGE) return@Runnable
        val found = findReelsPager(root)
        debug { "surfaceCheck found=$found" }
        setOnReels(found)
    }

    private fun findReelsPager(root: AccessibilityNodeInfo): Boolean {
        state.knownReelsId?.let { id ->
            if (root.findAccessibilityNodeInfosByViewId(id).any { it.isVisibleToUser && isBig(it) }) return true
        }
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val n = queue.removeFirst()
            visited++
            val id = n.viewIdResourceName
            if (IgSignatures.isReelsId(id) && n.isVisibleToUser && isBig(n)) {
                state.knownReelsId = id
                return true
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
        }
        return false
    }

    private fun isBig(n: AccessibilityNodeInfo): Boolean {
        val r = Rect()
        n.getBoundsInScreen(r)
        return r.height() >= screenHeight() / 2
    }

    // ---------------- speed ----------------

    private fun speedOn() = prefs.getBoolean(Keys.SPEED_LOCK, false)

    private fun speedMethod() =
        if (prefs.getString(Keys.SPEED_METHOD, "lock") == "hold") SpeedLock.Method.HOLD else SpeedLock.Method.LOCK

    private fun holdX() = resources.displayMetrics.widthPixels * prefs.getFloat(Keys.HOLD_X, IgSignatures.DEFAULT_HOLD_X)
    private fun holdY() = resources.displayMetrics.heightPixels * prefs.getFloat(Keys.HOLD_Y, IgSignatures.DEFAULT_HOLD_Y)

    /** Signed slide for the LOCK gesture: positive is down. */
    private fun slideDy(): Float {
        val dist = screenHeight() * LOCK_SLIDE_FRACTION
        return if (prefs.getString(Keys.LOCK_DIRECTION, "down") == "up") -dist else dist
    }

    private val engage = Runnable {
        if (!igInFront || !onReels || waitingForNextReel || overlays.fullShowing) return@Runnable
        if (state.session == null || !speedOn() || autoScroll.running) return@Runnable
        if (isAdOnScreen()) {
            debug { "ad on screen: no 2x press" }
            return@Runnable
        }
        debug { "engage ${speedMethod()} at ${holdX()},${holdY()}" }
        speed.engage(speedMethod(), holdX(), holdY(), slideDy())
    }

    /**
     * Ads have no 2x and a tap on them opens the advertiser, so never press on one. Looks for
     * Instagram's exact ad labels ("Ad", "Sponsored") or call-to-action buttons ("Install now",
     * "Learn more"...) on the current screen; the reels above and below sit off-screen.
     */
    private fun isAdOnScreen(): Boolean {
        val root = rootInActiveWindow ?: return false
        val h = screenHeight()
        val r = Rect()
        return IgSignatures.AD_LABELS.any { label ->
            root.findAccessibilityNodeInfosByText(label).any { n ->
                n.getBoundsInScreen(r)
                val onScreen = r.top >= 0 && r.bottom <= h && r.height() > 0
                onScreen && (n.text?.toString()?.trim().equals(label, true) || n.contentDescription?.toString()?.trim().equals(label, true))
            }
        }
    }

    /**
     * Instagram's speed hint under a held finger: "Slide down to lock 2x speed" or
     * "Slide down for normal speed". It has no view ID, so it's found by those words.
     * This and the "Ad"/"Sponsored" label are the only on-screen text ReelRein looks at, and
     * nothing is stored.
     */
    private fun readSpeedHint(): SpeedLock.Hint {
        val root = rootInActiveWindow ?: return SpeedLock.Hint.NONE
        if (root.packageName?.toString() != IgSignatures.PACKAGE) return SpeedLock.Hint.NONE
        // Only labels actually on screen: a hidden leftover from the previous reel must not count.
        if (visibleText(IgSignatures.HINT_LOCKED)) return SpeedLock.Hint.LOCKED
        if (visibleText(IgSignatures.HINT_CAN_LOCK)) return SpeedLock.Hint.CAN_LOCK
        return SpeedLock.Hint.NONE
    }

    private fun scheduleEngage(delayMs: Long) {
        handler.removeCallbacks(engage)
        handler.postDelayed(engage, delayMs)
    }

    private fun onHoldLost() {
        debug { "hold lost (real touch)" }
        lostAt = now()
        waitingForNextReel = true
        refreshHud()
    }

    // ---------------- counter pill ----------------

    private val hudActions = object : HudActions {
        override fun onSpeedLock(on: Boolean) {
            Usage.event("pill_action", mapOf("action" to if (on) "speed_on" else "speed_off"))
            prefs.edit().putBoolean(Keys.SPEED_LOCK, on).apply() // the listener does the rest
            refreshHud()
        }

        override fun onAutoScroll(on: Boolean) {
            Usage.event("pill_action", mapOf("action" to if (on) "auto_scroll_on" else "auto_scroll_off"))
            prefs.edit().putBoolean(Keys.AUTO_SCROLL, on).apply()
            overlays.closeHudPanel()
            runAutoScroll(on)
        }

        override fun onChangeLimit() {
            val s = state.session ?: return
            Usage.event("pill_action", mapOf("action" to "change_limit"))
            overlays.closeHudPanel()
            speed.forget()
            overlays.showChangeLimit(
                sessionInfo(s),
                initialMinutes = lastMinutes(),
                onStart = { minutes ->
                    rememberMinutes(minutes)
                    s.limitMs = s.usedMs + minutes * 60_000L
                    s.timesUpShown = false
                    overlays.hideFull()
                    refreshHud()
                    waitingForNextReel = false
                    scheduleEngage(SETTLE_MS)
                },
                onCancel = {
                    overlays.hideFull()
                    refreshHud()
                    waitingForNextReel = false
                    scheduleEngage(SETTLE_MS)
                },
            )
            refreshHud()
        }

        override fun onHide() {
            Usage.event("pill_action", mapOf("action" to "hide"))
            state.hudHidden = true
            overlays.hideHud()
        }

        override fun onEndSession() {
            Usage.event("pill_action", mapOf("action" to "end_session"))
            closeInstagram()
        }

        override fun onMoved(x: Int, y: Int) {
            prefs.edit().putInt(Keys.HUD_X, x).putInt(Keys.HUD_Y, y).apply()
        }
    }

    private fun sessionInfo(s: Session) = TimesUpInfo(
        sessionReelTime = formatDuration(s.reelMs),
        sessionReels = s.reels,
        sessionTime = formatDuration(s.usedMs),
        reelTimeToday = formatDuration(stats.day().reelMs + pendingReelMs),
    )

    private fun refreshHud() {
        val s = state.session
        val want = igInFront && s != null && !overlays.fullShowing && !state.hudHidden &&
            prefs.getBoolean(Keys.HUD, true) &&
            (onReels || !prefs.getBoolean(Keys.HUD_REELS_ONLY, false))
        if (!want || s == null) {
            overlays.hideHud()
            return
        }
        if (!overlays.hudShowing) {
            overlays.showHud(prefs.getInt(Keys.HUD_X, 0), prefs.getInt(Keys.HUD_Y, -1), HudConfig.from(prefs), hudActions)
        }
        val limit = s.limitMs
        val left = limit?.let { (it - s.usedMs).coerceAtLeast(0) }
        val day = stats.day()
        overlays.updateHud(
            HudState(
                reelTime = formatDuration(s.reelMs),
                sessionReels = s.reels,
                usedFraction = if (limit != null && limit > 0) (s.usedMs.toFloat() / limit).coerceIn(0f, 1f) else null,
                timeLeft = left?.let { formatDuration(it) },
                warning = left != null && left < 60_000,
                todayReelTime = formatDuration(day.reelMs + pendingReelMs),
                todayReels = day.reels,
                todayOpens = day.opens,
                speed2x = if (speedOn()) speed.active else null,
                speedLockOn = speedOn(),
                autoScroll = prefs.getBoolean(Keys.AUTO_SCROLL, false),
                mood = brainMood(),
            ),
        )
    }

    // ---------------- auto scroll ----------------

    private val autoScrollWhenSettled = Runnable { maybeSetAutoScroll() }

    /** First time on Reels this session: switch on Instagram's Auto scroll. True if it started. */
    private fun maybeSetAutoScroll(): Boolean {
        if (!prefs.getBoolean(Keys.AUTO_SCROLL, false) || state.autoScrollDone || autoScroll.running) return false
        if (state.session == null || overlays.fullShowing || isAdOnScreen()) return false
        runAutoScroll(true)
        return true
    }

    private fun runAutoScroll(want: Boolean) {
        if (!igInFront || !onReels) return
        handler.removeCallbacks(engage)
        speed.forget()
        autoScroll.apply(want) { result ->
            state.autoScrollDone = true // one try per session, even if it failed
            refreshHud()
            scheduleEngage(SETTLE_MS) // 2x after the menus are closed
        }
    }

    // ---------------- previews (from the settings screen) ----------------

    private fun previewPause() {
        if (igInFront) return
        val day = stats.day()
        overlays.showPause(
            seconds = prefs.getInt(Keys.PAUSE_SECONDS, 5),
            info = PauseInfo(day.opens + 1, stats.opensLast24h() + 1, formatDuration(day.ms), formatDuration(day.reelMs), day.reels),
            initialMinutes = lastMinutes(),
            onStart = { overlays.hideFull() },
            onClose = { overlays.hideFull() },
        )
    }

    private fun previewTimesUp() {
        if (igInFront) return
        val day = stats.day()
        overlays.showTimesUp(
            TimesUpInfo("8:12", 37, "10m", formatDuration(day.reelMs)),
            initialMinutes = lastMinutes(),
            onStart = { overlays.hideFull() },
            onClose = { overlays.hideFull() },
        )
    }

    private fun previewHud() {
        if (igInFront) return
        val preview = object : HudActions {
            override fun onSpeedLock(on: Boolean) = Unit
            override fun onAutoScroll(on: Boolean) = Unit
            override fun onChangeLimit() = Unit
            override fun onHide() = overlays.hideHud()
            override fun onEndSession() = overlays.hideHud()
            override fun onMoved(x: Int, y: Int) = hudActions.onMoved(x, y)
        }
        overlays.hideHud()
        overlays.showHud(prefs.getInt(Keys.HUD_X, 0), prefs.getInt(Keys.HUD_Y, -1), HudConfig.from(prefs), preview)
        val day = stats.day()
        overlays.updateHud(
            HudState(
                reelTime = "7:42", sessionReels = 12, usedFraction = 0.6f, timeLeft = "5:18", warning = false,
                todayReelTime = formatDuration(day.reelMs), todayReels = day.reels, todayOpens = day.opens,
                speed2x = if (speedOn()) true else null, speedLockOn = speedOn(), autoScroll = prefs.getBoolean(Keys.AUTO_SCROLL, false),
            ),
        )
        handler.postDelayed({ if (!igInFront) overlays.hideHud() }, 10_000)
    }

    override fun onSharedPreferenceChanged(p: SharedPreferences, key: String?) {
        // Which setting changed (its key, never a value); positions and the last limit are noise.
        if (key != null && key !in Usage.QUIET_KEYS) Usage.event("settings_changed", mapOf("setting" to key.take(32)))
        if (key == Keys.TIME_SECONDS) showSeconds = p.getBoolean(key, true)
        if (key in Keys.HUD_LAYOUT_KEYS || key == Keys.HUD_X || key == Keys.HUD_Y) {
            // Rebuild the pill with the new layout (a pill dragged on screen is already there).
            if (overlays.hudShowing && key != Keys.HUD_X && key != Keys.HUD_Y) {
                overlays.hideHud()
                if (igInFront) refreshHud() else previewHud() // live preview over the settings screen
            }
        }
        if (!igInFront) return
        when (key) {
            Keys.SPEED_LOCK -> if (p.getBoolean(key, false)) {
                waitingForNextReel = false
                scheduleEngage(SETTLE_MS)
            } else {
                speed.disengage(speedMethod(), holdX(), holdY(), slideDy())
            }
            Keys.HUD, Keys.HUD_REELS_ONLY -> refreshHud()
        }
    }

    // ---------------- helpers ----------------

    private fun imePackage(): String? =
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')

    private fun screenHeight() = resources.displayMetrics.heightPixels

    private fun now() = SystemClock.elapsedRealtime()

    private inline fun debug(msg: () -> String) {
        if (!prefs.getBoolean(Keys.DEBUG_LOG, false)) return
        val m = msg()
        Log.i(TAG, m) // vivo hides Log.d from apps, and sometimes Log.i too...
        DebugFile.append(this, m) // ...so also keep a small file: adb shell run-as com.naman.reelrein cat files/debug.log
    }

    companion object {
        private var instanceRef = WeakReference<ReelReinService>(null)

        enum class Preview { PAUSE, TIMES_UP, HUD }

        /** Shows an overlay over the settings screen. False when the service isn't running. */
        fun preview(which: Preview): Boolean {
            val s = instanceRef.get() ?: return false
            when (which) {
                Preview.PAUSE -> s.previewPause()
                Preview.TIMES_UP -> s.previewTimesUp()
                Preview.HUD -> s.previewHud()
            }
            return true
        }

        private const val TAG = "ReelRein"
        const val DEFAULT_RELOCK_S = 10               // leave and come back within this = same session
        private const val SLEEP_AFTER_MS = 5_000L     // bank-safe: switch off this long after leaving
        private const val FLUSH_MS = 30_000L
        private const val SETTLE_MS = 300L            // quiet time after the last scroll before 2x
        private const val RETRY_MS = 400L
        private const val KILL_DELAY_MS = 1_200L
        private const val CAP_REMIND_EVERY = 25
        private const val COVER_SETTLE_MS = 450L
        private const val KILL_RETRY_MS = 60_000L
        private const val LOCK_SLIDE_FRACTION = 0.14f // how far the lock gesture slides
        private const val ESCALATE_STEP_S = 3
        private const val MAX_PAUSE_S = 30
        private const val MAX_NODES = 800
    }
}
