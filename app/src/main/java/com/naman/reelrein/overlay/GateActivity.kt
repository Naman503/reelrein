package com.naman.reelrein.overlay

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import java.lang.ref.WeakReference

/**
 * The pause / time's-up / change-limit screens live in this ordinary full-screen activity,
 * opened on top of Instagram (One Sec does the same). Being a real screen, not a window above
 * everything, means the phone keeps working: Home, Back and the notification shade behave
 * normally. And Instagram, now behind another app, pauses itself, so the reel stops.
 *
 * The content is built by [Overlays]; this activity just hosts it and reports Back and
 * leaving (Home, switching apps) through [Gate].
 */
class GateActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val root = Gate.pendingRoot
        if (root == null) {
            finish()
            return
        }
        show(root)
        Gate.current = WeakReference(this)
        onBackPressedDispatcher.addCallback(this) { back() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) setShowWhenLocked(false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun show(root: View) {
        (root.parent as? ViewGroup)?.removeView(root)
        setContentView(root)
    }

    /** Back = "I don't want to open Instagram". Hooked both ways, as some phones skip one. */
    private fun back() {
        Gate.log("back")
        Gate.onBack?.invoke() ?: close()
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            if (event.action == android.view.KeyEvent.ACTION_UP) back()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** Home or recents. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        Gate.log("leave hint (finishing=$isFinishing)")
        if (!isFinishing) Gate.onLeave?.invoke()
    }

    /** Covered by another app (e.g. opened from a notification). Screen-off doesn't count. */
    override fun onStop() {
        super.onStop()
        val screenOn = getSystemService(PowerManager::class.java)?.isInteractive != false
        Gate.log("stop (finishing=$isFinishing, screenOn=$screenOn)")
        if (!isFinishing && !isChangingConfigurations && screenOn) Gate.onLeave?.invoke()
    }

    override fun onDestroy() {
        if (Gate.current?.get() === this) Gate.current = null
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    fun close() {
        finish()
        overridePendingTransition(0, android.R.anim.fade_out)
    }
}

/** Glue between [Overlays] (builds the screens) and [GateActivity] (shows them). */
object Gate {
    var pendingRoot: View? = null
    var current: WeakReference<GateActivity>? = null
    var onBack: (() -> Unit)? = null
    var onLeave: (() -> Unit)? = null
    var log: (String) -> Unit = {}

    val showing get() = current?.get() != null

    fun close() {
        pendingRoot = null
        current?.get()?.close()
        current = null
    }
}

