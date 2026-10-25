package com.naman.reelrein.telemetry

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.util.Log
import com.naman.reelrein.Keys
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
 * reels, minutes on Reels), setup steps and crashes to POST /v1/app/reelrein with a random
 * install id. Never what's on screen: no captions, accounts, usernames, messages or which
 * reels. Turning it off deletes what this install sent.
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
        val version = versionName()
        val last = prefs.getString(KEY_VERSION, null)
        if (last == null) once("app_installed")
        else if (last != version) event("app_update", mapOf("from" to last.take(16), "to" to version))
        prefs.edit().putString(KEY_VERSION, version).apply()
    }

    /** One occurrence with its time (a choice on a screen, a switch flipped). */
    fun event(name: String, props: Map<String, String> = emptyMap()) {
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
        if (!enabled || n <= 0) return
        synchronized(lock) {
            val c = counters()
            val key = counterKey(today(), name, props)
            c.put(key, c.optInt(key) + n)
            prefs.edit().putString(KEY_COUNTERS, c.toString()).apply()
        }
    }

    /** Time as whole minutes: keeps the leftover seconds so nothing is lost or rounded up. */
    fun minutes(name: String, ms: Long) {
        if (!enabled || ms <= 0) return
        synchronized(lock) {
            val carry = prefs.getLong("carry_$name", 0) + ms
            val whole = (carry / 60_000).toInt()
            prefs.edit().putLong("carry_$name", carry - whole * 60_000L).apply()
            if (whole > 0) count(name, whole)
        }
    }

    /** A milestone sent once per install (setup steps, the first pause screen). */
    fun once(name: String) {
        if (!enabled) return
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

    /** Once a day: how ReelRein is set up (switch states and chosen options only). */
    private fun dailyCheck() {
        val day = today()
        if (prefs.getString(KEY_DAILY, null) == day) return
        prefs.edit().putString(KEY_DAILY, day).apply()
        val s = settings
        val cap = s.getInt(Keys.REEL_CAP, 0)
        event(
            "daily_setup",
            mapOf(
                "pause" to s.getBoolean(Keys.INTERCEPT, true).toString(),
                "pause_s" to s.getInt(Keys.PAUSE_SECONDS, 5).toString(),
                "speed" to s.getBoolean(Keys.SPEED_LOCK, false).toString(),
                "auto_scroll" to s.getBoolean(Keys.AUTO_SCROLL, false).toString(),
                "cap" to bucket(cap),
                "bank_safe" to s.getBoolean(Keys.BANK_SAFE, false).toString(),
                "pill" to (s.getString(Keys.PILL_STYLE, "brain") ?: "brain").take(16),
            ),
        )
    }

    private fun bucket(cap: Int) = when {
        cap <= 0 -> "off"
        cap <= 25 -> "1-25"
        cap <= 50 -> "26-50"
        cap <= 100 -> "51-100"
        else -> "100+"
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

    /** Exception type and stack only: messages can contain screen text, so they are not sent. */
    fun recordCrash(t: Throwable, fatal: Boolean) {
        if (!started) return
        synchronized(lock) {
            val list = crashes()
            if (list.length() >= MAX_CRASHES) return
            val stack = t.stackTrace.take(25).joinToString("\n") { "at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})" }
            val top = t.stackTrace.firstOrNull { it.className.startsWith("com.naman.reelrein") } ?: t.stackTrace.firstOrNull()
            val fp = "${t.javaClass.name}@${top?.className}.${top?.methodName}".hashCode().toUInt().toString(16)
            list.put(
                JSONObject().put("fp", fp).put("m", t.javaClass.name.take(200)).put("st", stack.take(4000))
                    .put("t", System.currentTimeMillis()).put("fatal", fatal),
            )
            // commit(): the process is about to die.
            prefs.edit().putString(KEY_CRASHES, list.toString()).commit()
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
