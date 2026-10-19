package com.naman.reelrein

import android.content.Context
import android.content.SharedPreferences
import java.time.LocalDate

/** Setting keys, shared by the service and the settings screen. */
object Keys {
    const val PREFS = "settings"

    const val INTERCEPT = "intercept"           // show the pause screen when Instagram opens
    const val PAUSE_SECONDS = "pause_seconds"   // first pause of the day
    const val ESCALATE = "escalate"             // each later open today waits a bit longer
    const val HUD = "hud"
    const val HUD_REELS_ONLY = "hud_reels_only"
    const val HUD_X = "hud_x"
    const val HUD_Y = "hud_y"
    const val SPEED_LOCK = "speed_lock"         // keep Instagram's 2x held on every reel
    const val HOLD_X = "hold_x"
    const val HOLD_Y = "hold_y"
    const val SPEED_METHOD = "speed_method"     // "lock" (hold + slide, Instagram keeps 2x) or "hold"
    const val LOCK_DIRECTION = "lock_direction" // "down" or "up": which way the slide locks 2x
    const val LAST_MINUTES = "last_minutes"     // last limit picked on the pause / time's up screen
    const val AUTO_SCROLL = "auto_scroll"       // switch on Instagram's own Auto scroll each session
    const val REEL_CAP = "reel_cap"             // daily reel cap (0 = off): "your brain is full"
    const val RELOCK_SECONDS = "relock_seconds" // away from Instagram this long -> pause screen again
    const val BANK_SAFE = "bank_safe"           // switch accessibility off whenever Instagram is closed
    const val DEBUG_LOG = "debug_log"

    // Counter pill: what it shows and how it looks
    const val PILL_REEL_TIME = "pill_reel_time"     // time on reels this session
    const val PILL_REELS = "pill_reels"             // reels this session
    const val PILL_TODAY_REELS = "pill_today_reels" // "/ 148" today
    const val PILL_TODAY_TIME = "pill_today_time"   // time on reels today
    const val PILL_TIME_LEFT = "pill_time_left"     // time left in the limit
    const val PILL_RING = "pill_ring"               // limit ring around the clock
    const val PILL_SPEED = "pill_speed"             // 2x / 1x badge
    const val PILL_SIZE = "pill_size"               // "small" | "medium" | "large"
    const val PILL_STYLE = "pill_style"             // "solid" | "glass" | "outline"
    const val PILL_IDLE_ALPHA = "pill_idle_alpha"   // % opacity when idle (100 = never fades)
    const val PILL_TAP = "pill_tap"                 // "panel" | "speed" | "nothing"

    // Panel (tap the pill): which sections it has
    const val PANEL_SESSION = "panel_session"
    const val PANEL_TODAY = "panel_today"
    const val PANEL_SPEED = "panel_speed"
    const val PANEL_AUTO_SCROLL = "panel_auto_scroll"
    const val PANEL_CHANGE_LIMIT = "panel_change_limit"
    const val PANEL_HIDE = "panel_hide"
    const val PANEL_END = "panel_end"

    const val TIME_SECONDS = "time_seconds"         // "4:05" instead of "4m"
    const val HAPTICS = "haptics"

    fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Every key that changes how the pill or panel is built. */
    val HUD_LAYOUT_KEYS = setOf(
        PILL_REEL_TIME, PILL_REELS, PILL_TODAY_REELS, PILL_TODAY_TIME, PILL_TIME_LEFT, PILL_RING, PILL_SPEED,
        PILL_SIZE, PILL_STYLE, PILL_IDLE_ALPHA, PILL_TAP,
        PANEL_SESSION, PANEL_TODAY, PANEL_SPEED, PANEL_AUTO_SCROLL, PANEL_CHANGE_LIMIT, PANEL_HIDE, PANEL_END,
        HAPTICS,
    )
}

/** The counter pill + panel configuration, read from settings. */
data class HudConfig(
    val reelTime: Boolean,
    val reels: Boolean,
    val todayReels: Boolean,
    val todayTime: Boolean,
    val timeLeft: Boolean,
    val ring: Boolean,
    val speed: Boolean,
    val scale: Float,
    val style: String,
    val idleAlpha: Float,
    val tap: String,
    val panelSession: Boolean,
    val panelToday: Boolean,
    val panelSpeed: Boolean,
    val panelAutoScroll: Boolean,
    val panelChangeLimit: Boolean,
    val panelHide: Boolean,
    val panelEnd: Boolean,
    val haptics: Boolean,
) {
    companion object {
        fun from(p: SharedPreferences) = HudConfig(
            reelTime = p.getBoolean(Keys.PILL_REEL_TIME, true),
            reels = p.getBoolean(Keys.PILL_REELS, true),
            todayReels = p.getBoolean(Keys.PILL_TODAY_REELS, false),
            todayTime = p.getBoolean(Keys.PILL_TODAY_TIME, false),
            timeLeft = p.getBoolean(Keys.PILL_TIME_LEFT, false),
            ring = p.getBoolean(Keys.PILL_RING, true),
            speed = p.getBoolean(Keys.PILL_SPEED, true),
            scale = when (p.getString(Keys.PILL_SIZE, "medium")) {
                "small" -> 0.85f
                "large" -> 1.2f
                else -> 1f
            },
            style = p.getString(Keys.PILL_STYLE, "brain") ?: "brain",
            idleAlpha = p.getInt(Keys.PILL_IDLE_ALPHA, 80) / 100f,
            tap = p.getString(Keys.PILL_TAP, "panel") ?: "panel",
            panelSession = p.getBoolean(Keys.PANEL_SESSION, true),
            panelToday = p.getBoolean(Keys.PANEL_TODAY, true),
            panelSpeed = p.getBoolean(Keys.PANEL_SPEED, true),
            panelAutoScroll = p.getBoolean(Keys.PANEL_AUTO_SCROLL, true),
            panelChangeLimit = p.getBoolean(Keys.PANEL_CHANGE_LIMIT, true),
            panelHide = p.getBoolean(Keys.PANEL_HIDE, true),
            panelEnd = p.getBoolean(Keys.PANEL_END, true),
            haptics = p.getBoolean(Keys.HAPTICS, true),
        )
    }
}

data class DayStat(val date: LocalDate, val opens: Int, val reels: Int, val ms: Long, val reelMs: Long)

/**
 * Daily totals only: opens, reels, foreground time, "+5 min" extensions.
 * No per-reel or per-account data is ever stored.
 */
class StatsStore(ctx: Context) {
    private val p = ctx.getSharedPreferences("stats", Context.MODE_PRIVATE)

    private fun key(d: LocalDate, field: String) = "$d:$field"

    fun addOpen() {
        addInt("opens", 1)
        // Timestamps of recent opens, for "N attempts within the last 24 hours".
        val now = System.currentTimeMillis()
        val recent = openTimes().filter { now - it < DAY_MS } + now
        p.edit().putString("open_times", recent.takeLast(500).joinToString(",")).apply()
    }

    fun opensLast24h(): Int {
        val now = System.currentTimeMillis()
        return openTimes().count { now - it < DAY_MS }
    }

    private fun openTimes(): List<Long> =
        p.getString("open_times", "")!!.split(',').mapNotNull { it.toLongOrNull() }
    fun addReels(n: Int) = addInt("reels", n)
    /** Time with Instagram in front, and the part of it spent on Reels. */
    fun addTimeMs(ms: Long, reelMs: Long) {
        if (ms <= 0 && reelMs <= 0) return
        val d = LocalDate.now()
        p.edit()
            .putLong(key(d, "ms"), p.getLong(key(d, "ms"), 0) + ms)
            .putLong(key(d, "reel_ms"), p.getLong(key(d, "reel_ms"), 0) + reelMs)
            .apply()
    }

    fun day(d: LocalDate = LocalDate.now()) = DayStat(
        date = d,
        opens = p.getInt(key(d, "opens"), 0),
        reels = p.getInt(key(d, "reels"), 0),
        ms = p.getLong(key(d, "ms"), 0),
        reelMs = p.getLong(key(d, "reel_ms"), 0),
    )

    fun lastDays(n: Int): List<DayStat> {
        val today = LocalDate.now()
        return (0 until n).map { day(today.minusDays(it.toLong())) }
    }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }

    private fun addInt(field: String, n: Int) {
        val k = key(LocalDate.now(), field)
        p.edit().putInt(k, p.getInt(k, 0) + n).apply()
    }
}

/** Whether times show seconds ("4:05") or stay short ("4m"). Set from settings. */
@Volatile var showSeconds = true

fun formatDuration(ms: Long): String {
    if (!showSeconds) {
        val m = ms / 60_000
        return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
    }
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m >= 10 -> "${m}m"
        else -> "%d:%02d".format(m, s)
    }
}
