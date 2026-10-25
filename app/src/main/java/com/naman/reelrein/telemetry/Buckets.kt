package com.naman.reelrein.telemetry

/**
 * Turns exact numbers into a few fixed ranges before they go into a usage report, and
 * setting values into fixed words. Pure Kotlin (no Android), so it's unit-tested.
 * Values must never contain '|', '&' or '=' (they're the counter-key separators).
 */
object Buckets {
    /** A session limit or extension in minutes: "6-10", not every exact number. */
    fun limitMinutes(m: Int) = when {
        m <= 5 -> "1-5"
        m <= 10 -> "6-10"
        m <= 15 -> "11-15"
        m <= 30 -> "16-30"
        m <= 60 -> "31-60"
        else -> "60+"
    }

    /** Daily reel cap setting. */
    fun cap(cap: Int) = when {
        cap <= 0 -> "off"
        cap <= 25 -> "1-25"
        cap <= 50 -> "26-50"
        cap <= 100 -> "51-100"
        cap <= 200 -> "101-200"
        else -> "200+"
    }

    /** A length of time (a session, time on Reels). */
    fun duration(ms: Long) = when {
        ms < 60_000 -> "<1m"
        ms < 5 * 60_000 -> "1-5m"
        ms < 15 * 60_000 -> "5-15m"
        ms < 30 * 60_000 -> "15-30m"
        ms < 60 * 60_000 -> "30-60m"
        ms < 120 * 60_000 -> "1-2h"
        else -> "2h+"
    }

    /** A count of reels. */
    fun reels(n: Int) = when {
        n <= 0 -> "0"
        n <= 5 -> "1-5"
        n <= 20 -> "6-20"
        n <= 50 -> "21-50"
        n <= 100 -> "51-100"
        n <= 200 -> "101-200"
        else -> "200+"
    }

    /** Instagram opens in a day, or "this is the Nth open today". */
    fun opens(n: Int) = when {
        n <= 0 -> "0"
        n == 1 -> "1"
        n <= 3 -> "2-3"
        n <= 6 -> "4-6"
        n <= 10 -> "7-10"
        n <= 20 -> "11-20"
        else -> "20+"
    }

    /** Seconds the pause screen made you wait. */
    fun waitSeconds(s: Int) = when {
        s <= 5 -> "1-5"
        s <= 10 -> "6-10"
        s <= 20 -> "11-20"
        else -> "21+"
    }

    /** Days in a row (streaks). */
    fun days(n: Int) = when {
        n <= 0 -> "0"
        n == 1 -> "1"
        n <= 3 -> "2-3"
        n <= 7 -> "4-7"
        n <= 14 -> "8-14"
        n <= 29 -> "15-29"
        else -> "30+"
    }

    /** Today against yesterday, for the daily summary. */
    fun trend(today: Long, before: Long): String {
        if (before <= 0 && today <= 0) return "none"
        if (before <= 0) return "more"
        val ratio = today.toDouble() / before
        return when {
            ratio < 0.8 -> "less"
            ratio > 1.2 -> "more"
            else -> "same"
        }
    }

    /** Pill opacity when idle, to the nearest 10 %. */
    fun percent(p: Int) = ((p.coerceIn(0, 100) + 5) / 10 * 10).toString()

    /** Allowed words for the settings that are a choice between a few options. */
    private val CHOICES = mapOf(
        "speed_method" to setOf("lock", "hold"),
        "lock_direction" to setOf("down", "up"),
        "pill_size" to setOf("small", "medium", "large"),
        "pill_style" to setOf("brain", "solid", "glass", "outline"),
        "pill_tap" to setOf("panel", "speed", "nothing"),
    )
    private val PAUSE_SECONDS = setOf(3, 5, 10, 15, 20)
    private val RELOCK_SECONDS = setOf(0, 10, 30, 60, 120, 300)

    /**
     * What a changed setting is now, as a fixed word: true/false for switches, one of the
     * listed options for choices, a range for numbers, "default" when it was reset.
     * Anything unexpected becomes "other", so free text can never get through.
     */
    fun settingValue(key: String, value: Any?): String = when {
        value == null -> "default"
        value is Boolean -> value.toString()
        key == "reel_cap" && value is Int -> cap(value)
        key == "pause_seconds" && value is Int -> if (value in PAUSE_SECONDS) value.toString() else "other"
        key == "relock_seconds" && value is Int -> if (value in RELOCK_SECONDS) value.toString() else "other"
        key == "pill_idle_alpha" && value is Int -> percent(value)
        value is String && CHOICES[key]?.contains(value) == true -> value
        else -> "other"
    }
}
