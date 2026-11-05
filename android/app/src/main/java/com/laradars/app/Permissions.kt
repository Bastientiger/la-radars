package com.laradars.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/** Everything the app may ask the phone for, with its current state and how to request it. */
enum class Perm(val titleRes: Int, val subtitleRes: Int, val optional: Boolean = false) {
    LOCATION(R.string.perm_location, R.string.perm_location_sub),
    BACKGROUND_LOCATION(R.string.perm_background, R.string.perm_background_sub),
    NOTIFICATIONS(R.string.perm_notifications, R.string.perm_notifications_sub),
    OVERLAY(R.string.perm_overlay, R.string.perm_overlay_sub),
    BATTERY(R.string.perm_battery, R.string.perm_battery_sub),
    DRIVING_DETECTION(R.string.perm_driving, R.string.perm_driving_sub, optional = true),
    BLUETOOTH(R.string.perm_bluetooth, R.string.perm_bluetooth_sub, optional = true);

    fun granted(context: Context): Boolean = when (this) {
        LOCATION -> has(context, Manifest.permission.ACCESS_FINE_LOCATION)
        BACKGROUND_LOCATION -> AutoStart.hasBackgroundLocation(context)
        NOTIFICATIONS -> Build.VERSION.SDK_INT < 33 || has(context, Manifest.permission.POST_NOTIFICATIONS)
        OVERLAY -> AlertBanner.canDraw(context)
        BATTERY -> (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)
        DRIVING_DETECTION -> Prefs(context).autoStartDriving && AutoStart.hasActivityPermission(context)
        BLUETOOTH -> Prefs(context).autoStartBluetooth && Prefs(context).bluetoothDevice.isNotEmpty() && AutoStart.hasBluetoothPermission(context)
    }

    /** Runtime permissions Android can ask for with a system dialog; null when it is a settings screen. */
    fun runtimePermission(): String? = when (this) {
        LOCATION -> Manifest.permission.ACCESS_FINE_LOCATION
        BACKGROUND_LOCATION -> if (Build.VERSION.SDK_INT in 29..29) Manifest.permission.ACCESS_BACKGROUND_LOCATION else null
        NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null
        DRIVING_DETECTION -> if (Build.VERSION.SDK_INT >= 29) Manifest.permission.ACTIVITY_RECOGNITION else null
        BLUETOOTH -> if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_CONNECT else null
        else -> null
    }

    /** Opens the system screen where the user grants it (for the ones without a dialog). */
    fun openSettings(activity: Activity) {
        val pkg = Uri.parse("package:${activity.packageName}")
        val intent = when (this) {
            OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)
            BATTERY -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)
            BACKGROUND_LOCATION -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
        }
        runCatching { activity.startActivity(intent) }
    }

    companion object {
        private fun has(context: Context, p: String) =
            ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

        /** The ones the app needs to work fully (optional auto-start ones excluded). */
        val required = values().filter { !it.optional }
    }
}
