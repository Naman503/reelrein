package com.naman.reelrein.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BucketsTest {
    @Test fun limitMinutes() {
        assertEquals("1-5", Buckets.limitMinutes(5))
        assertEquals("6-10", Buckets.limitMinutes(6))
        assertEquals("16-30", Buckets.limitMinutes(30))
        assertEquals("60+", Buckets.limitMinutes(90))
    }

    @Test fun capAndCounts() {
        assertEquals("off", Buckets.cap(0))
        assertEquals("26-50", Buckets.cap(50))
        assertEquals("101-200", Buckets.cap(150))
        assertEquals("200+", Buckets.cap(300))
        assertEquals("0", Buckets.reels(0))
        assertEquals("6-20", Buckets.reels(12))
        assertEquals("200+", Buckets.reels(999))
        assertEquals("1", Buckets.opens(1))
        assertEquals("4-6", Buckets.opens(5))
        assertEquals("15-29", Buckets.days(20))
    }

    @Test fun duration() {
        assertEquals("<1m", Buckets.duration(59_000))
        assertEquals("1-5m", Buckets.duration(60_000))
        assertEquals("15-30m", Buckets.duration(20 * 60_000L))
        assertEquals("2h+", Buckets.duration(5 * 3_600_000L))
    }

    @Test fun trend() {
        assertEquals("none", Buckets.trend(0, 0))
        assertEquals("more", Buckets.trend(10, 0))
        assertEquals("less", Buckets.trend(50, 100))
        assertEquals("same", Buckets.trend(100, 95))
        assertEquals("more", Buckets.trend(200, 100))
    }

    @Test fun settingValueOnlyFixedWords() {
        assertEquals("true", Buckets.settingValue("speed_lock", true))
        assertEquals("glass", Buckets.settingValue("pill_style", "glass"))
        assertEquals("other", Buckets.settingValue("pill_style", "something typed"))
        assertEquals("other", Buckets.settingValue("unknown_key", "free text"))
        assertEquals("default", Buckets.settingValue("hud_x", null))
        assertEquals("26-50", Buckets.settingValue("reel_cap", 50))
        assertEquals("10", Buckets.settingValue("pause_seconds", 10))
        assertEquals("other", Buckets.settingValue("pause_seconds", 7))
        assertEquals("80", Buckets.settingValue("pill_idle_alpha", 78))
        assertEquals("other", Buckets.settingValue("hold_x", 0.7f))
    }

    @Test fun valuesNeverBreakCounterKeys() {
        val values = listOf(
            Buckets.limitMinutes(3), Buckets.cap(120), Buckets.duration(10), Buckets.reels(3), Buckets.opens(30),
            Buckets.waitSeconds(12), Buckets.days(40), Buckets.trend(1, 2), Buckets.percent(33),
        )
        values.forEach { v -> assertFalse(v, v.any { it in "|&=" }) }
        assertTrue(values.all { it.length <= 64 })
    }
}
