package com.overlaytranslator.util

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Centralised permission checks so MainActivity and CaptureService agree on state.
 */
object Permissions {

    /** `SYSTEM_ALERT_WINDOW` — required to draw the translation overlay. */
    fun hasOverlayPermission(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    fun overlaySettingsIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
    }

    /** `POST_NOTIFICATIONS` — required on API 33+ for the foreground-service notification. */
    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * True when Android 12+ would block touches through our overlay.
     * `block_untrusted_touches` defaults to 1 (blocking) and is a global setting
     * the user can flip in Developer options or via adb.
     */
    fun isUntrustedTouchBlocked(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return false
        return try {
            Settings.Global.getInt(
                context.contentResolver,
                "block_untrusted_touches",
                1
            ) == 1
        } catch (_: Settings.SettingNotFoundException) {
            false
        } catch (_: SecurityException) {
            // Some OEMs restrict reading this; assume blocking so the user is warned.
            true
        }
    }

    /** The one-shot adb command that unblocks overlay touch passthrough. */
    const val UNTRUSTED_TOUCH_ADB_COMMAND: String =
        "adb shell settings put global block_untrusted_touches 0"

    /** Battery-optimization exemption so the foreground service survives Doze. */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    @Suppress("unused")
    private fun appOpsCheck(context: Context, op: String): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = ops.checkOpNoThrow(op, Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
