package com.naman.reelrein.telemetry

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.util.Log
import android.app.NotificationManager
import android.os.PowerManager
import com.naman.reelrein.A11yToggle
import com.naman.reelrein.Keys
import com.naman.reelrein.LaunchWatcher
import com.naman.reelrein.StatsStore
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Anonymous usage reporting (Settings → Privacy → Share anonymous usage), on by default and
 * shown on first open. It sends which features are used, daily counts (Instagram opens,
 * reels, minutes on Reels, sessions by length), what was chosen on ReelRein's own screens,
 * settings as fixed words (on/off, "glass", "6-10" min), setup steps, permission states,
 * whether ReelRein's own steps worked (2x lock, Auto scroll, the pill showing), error types
 * and crashes to POST /v1/app/reelrein with a random install id. Never what's on screen: no
 * captions, accounts, usernames, comments, messages or which reels. Exact numbers go out as
 * ranges (see [Buckets]). Turning it off deletes what this install sent.
 *
 * The service and the settings screen share this one object (same process). Reports go out
 * at most every 6 hours, on a background thread, and wait for the next chance when offline.
 */
object Usage {
    const val ENDPOINT = "https://api.np-dev.duckdns.org/v1/app/reelrein"
    private const val TAG = "ReelRein"
    private const val FLUSH_EVERY_MS = 6 * 3_600_000L
    private const val MAX_PENDING = 500
    private const val MAX_CRASHES = 20

    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences
    private val lock = Any()
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var started = false

    /** Every settings change, from the settings screen, the pill or the service. */
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
        if (key == null || key in QUIET_KEYS) return@OnSharedPreferenceChangeListener
        // Its key and the new value as a fixed word (see Buckets.settingValue), never free text.
        event("settings_changed", mapOf("setting" to key.take(32), "value" to Buckets.settingValue(key, p.all[key])))
    }

    private val settings get() = Keys.prefs(app)
    val enabled get() = started && settings.getBoolean(Keys.SHARE_USAGE, true)

    val installId: String
        get() = prefs.getString(KEY_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_ID, it).apply()
        }

    /** Called from the settings screen and the service; safe to call more than once. */
    fun start(ctx: Context) {
        synchronized(lock) {
            if (started) return
            app = ctx.applicationContext
            prefs = app.getSharedPreferences("telemetry", Context.MODE_PRIVATE)
            started = true
        }
        installCrashHandler()
        settings.registerOnSharedPreferenceChangeListener(settingsListener)
        val version = versionName()
        val last = prefs.getString(KEY_VERSION, null)
        if (last == null) once("app_installed")
        else if (last != version) event("app_update", mapOf("from" to last.take(16), "to" to version))
        prefs.edit().putString(KEY_VERSION, version).apply()
    }

    /** One occurrence with its time (a choice on a screen, a switch flipped). */
    fun event(name: String, props: Map<String, String> = emptyMap()) {
        if (!started) return
        if (!enabled) return
        synchronized(lock) {
            val list = pending()
            if (list.length() >= MAX_PENDING) return
            list.put(JSONObject().put("n", name).put("t", System.currentTimeMillis()).apply {
                if (props.isNotEmpty()) put("p", JSONObject(props))
            })
            prefs.edit().putString(KEY_PENDING, list.toString()).apply()
        }
    }

    /** A daily counter (reels today, Instagram opens today…), sent once the day is over. */
    fun count(name: String, n: Int = 1, props: Map<String, String> = emptyMap()) {
        if (!started || !enabled || n <= 0) return
        synchronized(lock) {
            val c = counters()
            val key = counterKey(today(), name, props)
            c.put(key, c.optInt(key) + n)
            prefs.edit().putString(KEY_COUNTERS, c.toString()).apply()
        }
    }

    /** Time as whole minutes: keeps the leftover seconds so nothing is lost or rounded up. */
    fun minutes(name: String, ms: Long) {
        if (!started || !enabled || ms <= 0) return
        synchronized(lock) {
            val carry = prefs.getLong("carry_$name", 0) + ms
            val whole = (carry / 60_000).toInt()
            prefs.edit().putLong("carry_$name", carry - whole * 60_000L).apply()
            if (whole > 0) count(name, whole)
        }
    }

    /** A milestone sent once per install (setup steps, the first pause screen). */
    fun once(name: String) {
        if (!started || !enabled) return
        synchronized(lock) {
            val done = prefs.getStringSet(KEY_ONCE, emptySet()).orEmpty()
            if (name in done) return
            prefs.edit().putStringSet(KEY_ONCE, done + name).apply()
        }
        event(name)
    }

    /** Sharing switched off: forget the queue and ask the server to delete what was sent. */
    fun turnedOff() {
        if (!started) return
        synchronized(lock) {
            prefs.edit().remove(KEY_PENDING).remove(KEY_COUNTERS).remove(KEY_CRASHES)
                .putBoolean(KEY_OFF_PENDING, true).apply()
        }
        flushSoon(force = true)
    }

    fun turnedOn() {
        if (!started) return
        prefs.edit().putBoolean(KEY_OFF_PENDING, false).apply()
    }

    /** Sends what's waiting if the last report was over 6 hours ago (or [force]). */
    fun flushSoon(force: Boolean = false) {
        if (!started) return
        val last = prefs.getLong(KEY_LAST_FLUSH, 0)
        if (!force && System.currentTimeMillis() - last < FLUSH_EVERY_MS) return
        io.execute { runCatching { flush() } }
    }

    private fun flush() {
        if (prefs.getBoolean(KEY_OFF_PENDING, false)) {
            if (post(base().put("off", true))) prefs.edit().putBoolean(KEY_OFF_PENDING, false).apply()
            return
        }
        if (!enabled) return
        dailyCheck()
        val sent = synchronized(lock) {
            val events = pending()
            val c = counters()
            val today = today()
            val keys = mutableListOf<String>()
            for (key in c.keys()) {
                val (day, name, props) = parseCounterKey(key) ?: continue
                if (day == today) continue // only finished days
                events.put(JSONObject().put("n", name).put("t", noonOf(day)).put("c", c.getInt(key)).apply {
                    if (props.isNotEmpty()) put("p", JSONObject(props))
                })
                keys += key
            }
            Sent(base().put("e", events).put("cr", crashes()), keys, pending().length(), crashes().length())
        }
        prefs.edit().putLong(KEY_LAST_FLUSH, System.currentTimeMillis()).apply()
        if (sent.counters.isEmpty() && sent.events == 0 && sent.crashes == 0) return
        if (!post(sent.body)) return
        synchronized(lock) {
            // Drop what went out; anything queued meanwhile stays for next time.
            val ev = pending()
            val rest = JSONArray().apply { for (i in sent.events until ev.length()) put(ev.get(i)) }
            val c = counters().apply { sent.counters.forEach { remove(it) } }
            val cr = crashes()
            val crRest = JSONArray().apply { for (i in sent.crashes until cr.length()) put(cr.get(i)) }
            prefs.edit().putString(KEY_PENDING, rest.toString()).putString(KEY_COUNTERS, c.toString())
                .putString(KEY_CRASHES, crRest.toString()).apply()
        }
    }

    /**
     * Once a day: how ReelRein is set up (switch states and chosen options only, split over a
     * few events because an event holds at most 8 values), which permissions it has, and how
     * yesterday went (in ranges).
     */
    private fun dailyCheck() {
        val day = today()
        if (prefs.getString(KEY_DAILY, null) == day) return
        prefs.edit().putString(KEY_DAILY, day).apply()
        val s = settings
        fun b(key: String, def: Boolean) = s.getBoolean(key, def).toString()
        fun v(key: String, def: Any) = Buckets.settingValue(key, s.all[key] ?: def)
        event(
            "daily_setup",
            mapOf(
                "pause" to b(Keys.INTERCEPT, true),
                "pause_s" to s.getInt(Keys.PAUSE_SECONDS, 5).toString(),
                "speed" to b(Keys.SPEED_LOCK, false),
                "auto_scroll" to b(Keys.AUTO_SCROLL, false),
                "cap" to Buckets.cap(s.getInt(Keys.REEL_CAP, 0)),
                "bank_safe" to b(Keys.BANK_SAFE, false),
                "pill" to v(Keys.PILL_STYLE, "brain"),
            ),
        )
        event(
            "daily_setup_more",
            mapOf(
                "escalate" to b(Keys.ESCALATE, true),
                "relock_s" to v(Keys.RELOCK_SECONDS, 10),
                "speed_method" to v(Keys.SPEED_METHOD, "lock"),
                "lock_dir" to v(Keys.LOCK_DIRECTION, "down"),
                "haptics" to b(Keys.HAPTICS, true),
                "seconds" to b(Keys.TIME_SECONDS, true),
                "debug_log" to b(Keys.DEBUG_LOG, false),
                "press_point" to (if (s.contains(Keys.HOLD_X) || s.contains(Keys.HOLD_Y)) "custom" else "default"),
            ),
        )
        event(
            "daily_pill",
            mapOf(
                "show" to b(Keys.HUD, true),
                "reels_only" to b(Keys.HUD_REELS_ONLY, false),
                "size" to v(Keys.PILL_SIZE, "medium"),
                "style" to v(Keys.PILL_STYLE, "brain"),
                "tap" to v(Keys.PILL_TAP, "panel"),
                "fade" to v(Keys.PILL_IDLE_ALPHA, 80),
                "moved" to (s.contains(Keys.HUD_X)).toString(),
            ),
        )
        event(
            "daily_pill_parts",
            mapOf(
                "brain" to b(Keys.PILL_BRAIN, true),
                "reel_time" to b(Keys.PILL_REEL_TIME, true),
                "reels" to b(Keys.PILL_REELS, true),
                "today_reels" to b(Keys.PILL_TODAY_REELS, false),
                "today_time" to b(Keys.PILL_TODAY_TIME, false),
                "time_left" to b(Keys.PILL_TIME_LEFT, false),
                "ring" to b(Keys.PILL_RING, true),
                "speed" to b(Keys.PILL_SPEED, true),
            ),
        )
        event(
            "daily_panel",
            mapOf(
                "session" to b(Keys.PANEL_SESSION, true),
                "today" to b(Keys.PANEL_TODAY, true),
                "speed" to b(Keys.PANEL_SPEED, true),
                "auto_scroll" to b(Keys.PANEL_AUTO_SCROLL, true),
                "change_limit" to b(Keys.PANEL_CHANGE_LIMIT, true),
                "hide" to b(Keys.PANEL_HIDE, true),
                "end" to b(Keys.PANEL_END, true),
            ),
        )
        runCatching { event("daily_permissions", permissions()) }
        runCatching { yesterday(s.getInt(Keys.REEL_CAP, 0)) }
    }

    /** Which permissions ReelRein has right now (yes/no each). */
    private fun permissions(): Map<String, String> = mapOf(
        "accessibility" to A11yToggle.isEnabled(app).toString(),
        "battery" to (app.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(app.packageName) == true).toString(),
        "usage_access" to LaunchWatcher.hasUsageAccess(app).toString(),
        "secure_settings" to A11yToggle.canToggle(app).toString(),
        "notifications" to (app.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true).toString(),
    )

    /** Yesterday in ranges, and streaks: days in a row under the reel cap, or lighter than the day before. */
    private fun yesterday(cap: Int) {
        val stats = StatsStore(app)
        val today = LocalDate.now()
        val days = (1..31).map { stats.day(today.minusDays(it.toLong())) } // [0] = yesterday
        val y = days[0]
        if (y.opens == 0 && y.ms == 0L && days[1].opens == 0) return // ReelRein not in use then
        event(
            "day_summary",
            mapOf(
                "reels" to Buckets.reels(y.reels),
                "reel_time" to Buckets.duration(y.reelMs),
                "ig_time" to Buckets.duration(y.ms),
                "opens" to Buckets.opens(y.opens),
                "vs_before" to Buckets.trend(y.reelMs, days[1].reelMs),
                "cap" to when {
                    cap <= 0 -> "no_cap"
                    y.reels <= cap -> "under"
                    else -> "over"
                },
            ),
        )
        if (cap > 0) {
            val under = days.takeWhile { it.reels <= cap }.size
            event("streak", mapOf("kind" to "under_cap", "days" to Buckets.days(under)))
            if (y.reels <= cap) count("goal_met") else count("goal_missed")
        }
        val lighter = days.zipWithNext().takeWhile { (d, before) -> d.reelMs < before.reelMs || (d.reelMs == 0L && before.reelMs == 0L) }.size
        event("streak", mapOf("kind" to "lighter", "days" to Buckets.days(lighter)))
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, t ->
            try {
                if (enabled) recordCrash(t, fatal = true)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, t)
        }
    }

    /**
     * Something of ours failed but the app carried on (an overlay that couldn't be added, the
     * pause screen not opening...). [where] is a fixed word naming the place. Counted per day,
     * and the first of each kind also goes in the crash list (type and code location only).
     */
    fun nonFatal(where: String, t: Throwable) {
        if (!started || !enabled) return
        count("error", props = mapOf("where" to where))
        runCatching { recordCrash(t, fatal = false, where = where) }
    }

    /** Exception type and stack only: messages can contain screen text, so they are not sent. */
    fun recordCrash(t: Throwable, fatal: Boolean, where: String? = null) {
        if (!started) return
        synchronized(lock) {
            val list = crashes()
            if (list.length() >= MAX_CRASHES) return
            val stack = t.stackTrace.take(25).joinToString("\n") { "at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})" }
            val top = t.stackTrace.firstOrNull { it.className.startsWith("com.naman.reelrein") } ?: t.stackTrace.firstOrNull()
            val fp = "${t.javaClass.name}@${top?.className}.${top?.methodName}".hashCode().toUInt().toString(16)
            // The same non-fatal problem only once per report.
            if (!fatal && (0 until list.length()).any { list.getJSONObject(it).optString("fp") == fp }) return
            // Our own words only: the place it happened and the error type (plus the cause's type).
            val cause = t.cause?.let { " caused by ${it.javaClass.name}" }.orEmpty()
            val msg = (if (where != null) "$where: " else "") + t.javaClass.name + cause
            list.put(
                JSONObject().put("fp", fp).put("m", msg.take(500)).put("st", stack.take(4000))
                    .put("t", System.currentTimeMillis()).put("fatal", fatal),
            )
            // commit(): the process may be about to die.
            if (fatal) prefs.edit().putString(KEY_CRASHES, list.toString()).commit()
            else prefs.edit().putString(KEY_CRASHES, list.toString()).apply()
        }
    }

    private fun base(): JSONObject {
        val tablet = (app.resources.configuration.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK) >= Configuration.SCREENLAYOUT_SIZE_LARGE
        return JSONObject()
            .put("i", installId)
            .put("v", versionName())
            .put("pf", "android")
            .put("os", "Android")
            .put("osv", Build.VERSION.RELEASE.take(32))
            .put("dc", if (tablet) "tablet" else "phone")
            .put("dm", "${Build.MANUFACTURER} ${Build.MODEL}".take(48))
            .put("loc", Locale.getDefault().toLanguageTag().take(16))
    }

    private fun post(body: JSONObject): Boolean = try {
        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.doOutput = true
        conn.setRequestProperty("content-type", "application/json")
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val ok = conn.responseCode in 200..299
        conn.disconnect()
        ok
    } catch (e: Exception) {
        Log.i(TAG, "usage report not sent: ${e.javaClass.simpleName}")
        false
    }

    private fun versionName(): String = runCatching {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName
    }.getOrNull() ?: "?"

    private fun pending() = JSONArray(prefs.getString(KEY_PENDING, null) ?: "[]")
    private fun counters() = JSONObject(prefs.getString(KEY_COUNTERS, null) ?: "{}")
    private fun crashes() = JSONArray(prefs.getString(KEY_CRASHES, null) ?: "[]")

    private data class Sent(val body: JSONObject, val counters: List<String>, val events: Int, val crashes: Int)

    /** "2026-10-26|reels|" or "2026-10-26|pause_result|choice=opened". */
    internal fun counterKey(day: String, name: String, props: Map<String, String>) =
        "$day|$name|" + props.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }

    internal fun parseCounterKey(key: String): Triple<String, String, Map<String, String>>? {
        val parts = key.split('|')
        if (parts.size != 3) return null
        val props = if (parts[2].isEmpty()) emptyMap() else parts[2].split('&').mapNotNull {
            val kv = it.split('=', limit = 2)
            if (kv.size == 2) kv[0] to kv[1] else null
        }.toMap()
        return Triple(parts[0], parts[1], props)
    }

    private fun dayFormat() = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    fun today(): String = dayFormat().format(Date())
    /** A counter's day as a timestamp: noon local time, so it lands on that day in any report. */
    fun noonOf(day: String): Long =
        (dayFormat().apply { timeZone = TimeZone.getDefault() }.parse(day)?.time ?: System.currentTimeMillis()) + 12 * 3_600_000L

    /** Settings keys not worth a "settings_changed" event (drag positions, last picked limit). */
    val QUIET_KEYS = setOf(
        Keys.HUD_X, Keys.HUD_Y, Keys.HOLD_X, Keys.HOLD_Y, Keys.LAST_MINUTES, Keys.SHARE_USAGE, Keys.USAGE_NOTICE,
    )

    /** "What the user just did" helpers, so call sites stay one line. */
    fun ui(action: String) = event("ui_action", mapOf("action" to action))


    private const val KEY_ID = "install_id"
    private const val KEY_VERSION = "last_version"
    private const val KEY_PENDING = "pending"
    private const val KEY_COUNTERS = "counters"
    private const val KEY_CRASHES = "crashes"
    private const val KEY_ONCE = "once"
    private const val KEY_DAILY = "daily_checked"
    private const val KEY_OFF_PENDING = "off_pending"
    private const val KEY_LAST_FLUSH = "last_flush"
}
