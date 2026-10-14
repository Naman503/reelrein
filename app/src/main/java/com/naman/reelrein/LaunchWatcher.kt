package com.naman.reelrein

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process

/**
 * Bank-safe mode: while Instagram is closed the accessibility service is off, so banking apps
 * see no accessibility service from ReelRein. This small foreground service notices Instagram
 * coming to the front and switches the accessibility service back on.
 *
 * Cost: one in-memory usage-events query every 0.8 s, and only while the screen is on.
 */
class LaunchWatcher : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var usm: UsageStatsManager
    private var lastChecked = 0L

    private val poll = object : Runnable {
        override fun run() {
            check()
            handler.postDelayed(this, POLL_MS)
        }
    }

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            handler.removeCallbacks(poll)
            if (intent.action == Intent.ACTION_SCREEN_ON) handler.post(poll)
        }
    }

    override fun onCreate() {
        super.onCreate()
        usm = getSystemService(UsageStatsManager::class.java)
        startInForeground()
        registerReceiver(screen, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
        lastChecked = System.currentTimeMillis() - 2_000
        if (getSystemService(PowerManager::class.java).isInteractive) handler.post(poll)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(screen) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun check() {
        val now = System.currentTimeMillis()
        val events = runCatching { usm.queryEvents(lastChecked, now) }.getOrNull() ?: return
        lastChecked = now
        val e = UsageEvents.Event()
        var instagramOpened = false
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.packageName == IgSignatures.PACKAGE && e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                instagramOpened = true
            }
        }
        if (instagramOpened && !A11yToggle.isEnabled(this)) A11yToggle.enable(this)
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Bank-safe mode", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while ReelRein waits for Instagram with accessibility switched off"
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_verified_user)
            .setContentTitle("ReelRein is waiting for Instagram")
            .setContentText("Accessibility stays off until Instagram opens")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    companion object {
        private const val CHANNEL = "bank_safe"
        private const val NOTIFICATION_ID = 7
        private const val POLL_MS = 800L

        fun hasUsageAccess(ctx: Context): Boolean {
            val ops = ctx.getSystemService(AppOpsManager::class.java)
            val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
            return mode == AppOpsManager.MODE_ALLOWED
        }

        /** Everything bank-safe mode needs is in place. */
        fun ready(ctx: Context) = A11yToggle.canToggle(ctx) && hasUsageAccess(ctx)

        /** @param switchedOff true when the user just turned bank-safe mode off. */
        fun sync(ctx: Context, switchedOff: Boolean = false) {
            val on = Keys.prefs(ctx).getBoolean(Keys.BANK_SAFE, false) && ready(ctx)
            val intent = Intent(ctx, LaunchWatcher::class.java)
            if (on) {
                runCatching { ctx.startForegroundService(intent) }
            } else {
                ctx.stopService(intent)
                // Leaving bank-safe mode: the service should simply stay on from now on.
                if (switchedOff && A11yToggle.canToggle(ctx)) A11yToggle.enable(ctx)
            }
        }
    }
}

/** Restarts the watcher after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Keys.prefs(context).getBoolean(Keys.BANK_SAFE, false)) LaunchWatcher.sync(context)
    }
}
