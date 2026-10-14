package com.naman.reelrein

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * Silences Instagram while the pause or time's-up screen covers it, by muting the media
 * stream. It deliberately doesn't take audio focus: Instagram pauses the reel on focus loss
 * and doesn't always resume, which left reels paused (and Auto scroll stuck) after Continue.
 * The "we muted it" flag is saved, so an unexpected restart can't leave the phone muted.
 */
class AudioGate(ctx: Context) {
    private val am = ctx.getSystemService(AudioManager::class.java)
    private val flags = ctx.getSharedPreferences("runtime", Context.MODE_PRIVATE)
    private var focus: AudioFocusRequest? = null

    fun quiet() {
        if (!am.isStreamMute(AudioManager.STREAM_MUSIC)) {
            runCatching { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0) }
            flags.edit().putBoolean(MUTED, true).apply()
        }
    }

    fun restore() {
        focus?.let { runCatching { am.abandonAudioFocusRequest(it) } }
        focus = null
        if (flags.getBoolean(MUTED, false)) {
            runCatching { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0) }
            flags.edit().putBoolean(MUTED, false).apply()
        }
    }

    private companion object {
        const val MUTED = "muted_by_reelrein"
    }
}
