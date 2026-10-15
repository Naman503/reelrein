package com.naman.reelrein

import android.content.Context
import android.media.AudioManager

/**
 * Silences Instagram while the pause, time's-up or change-limit screen covers it.
 *
 * Muting the media stream alone isn't enough: with Bluetooth earbuds that use "absolute
 * volume", the mute flag often isn't passed on and Instagram keeps playing in your ears.
 * So the media volume itself goes to 0 (that is sent to the earbuds), plus the mute flag,
 * and your exact volume is put back afterwards.
 *
 * It deliberately doesn't take audio focus: Instagram pauses the reel on focus loss and
 * doesn't always resume, which left reels paused (and Auto scroll stuck) after Continue.
 * The saved volume is kept on disk, so an unexpected restart can't leave the phone silent.
 */
class AudioGate(ctx: Context) {
    private val am = ctx.getSystemService(AudioManager::class.java)
    private val flags = ctx.getSharedPreferences("runtime", Context.MODE_PRIVATE)

    fun quiet() {
        if (!flags.contains(SAVED_VOLUME)) {
            val volume = am.getStreamVolume(STREAM)
            flags.edit().putInt(SAVED_VOLUME, volume).commit() // commit: must survive a crash
        }
        runCatching { am.setStreamVolume(STREAM, 0, 0) }
        runCatching { am.adjustStreamVolume(STREAM, AudioManager.ADJUST_MUTE, 0) }
    }

    fun restore() {
        if (!flags.contains(SAVED_VOLUME)) return
        val volume = flags.getInt(SAVED_VOLUME, am.getStreamVolume(STREAM))
        runCatching { am.adjustStreamVolume(STREAM, AudioManager.ADJUST_UNMUTE, 0) }
        runCatching { am.setStreamVolume(STREAM, volume, 0) }
        flags.edit().remove(SAVED_VOLUME).apply()
    }

    private companion object {
        const val STREAM = AudioManager.STREAM_MUSIC
        const val SAVED_VOLUME = "volume_before_pause_screen"
    }
}
