package com.naman.reelrein

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Turns ReelRein's own accessibility service on and off (bank-safe mode). Needs
 * WRITE_SECURE_SETTINGS, which only adb can grant:
 *   adb shell pm grant com.naman.reelrein android.permission.WRITE_SECURE_SETTINGS
 * Only ReelRein's entry is touched; other enabled services (screen readers etc.) are kept.
 */
object A11yToggle {
    fun canToggle(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    private fun me(ctx: Context) = ComponentName(ctx, ReelReinService::class.java).flattenToString()

    private fun enabledList(ctx: Context): List<String> =
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')
            ?.filter { it.isNotBlank() }
            .orEmpty()

    fun isEnabled(ctx: Context) = enabledList(ctx).any { it.startsWith("${ctx.packageName}/") }

    fun enable(ctx: Context): Boolean {
        if (!canToggle(ctx)) return false
        if (isEnabled(ctx)) return true
        return runCatching {
            val list = enabledList(ctx) + me(ctx)
            Settings.Secure.putString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list.joinToString(":"))
            Settings.Secure.putInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        }.isSuccess
    }

    fun disable(ctx: Context): Boolean {
        if (!canToggle(ctx)) return false
        return runCatching {
            val list = enabledList(ctx).filterNot { it.startsWith("${ctx.packageName}/") }
            Settings.Secure.putString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list.joinToString(":"))
        }.isSuccess
    }
}
