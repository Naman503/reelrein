package com.naman.reelrein

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug log in the app's private files, only written while "Debug log" is on. Some phones
 * (vivo) drop app logcat output, so this is the reliable copy. Capped at ~200 KB.
 * Read it with: adb shell run-as com.naman.reelrein cat files/debug.log
 */
object DebugFile {
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun append(ctx: Context, line: String) {
        runCatching {
            val f = File(ctx.filesDir, "debug.log")
            if (f.length() > 200_000) f.renameTo(File(ctx.filesDir, "debug.old.log"))
            f.appendText("${time.format(Date())} $line\n")
        }
    }
}
