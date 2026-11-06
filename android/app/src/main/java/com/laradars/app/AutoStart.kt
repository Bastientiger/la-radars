package com.laradars.app

import android.Manifest
import android.app.DownloadManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity

/**
 * Starts a trip by itself, whichever of three triggers fires first:
 *  - the phone travels at car speed (StandbyService, cheap position check every 90 s);
 *  - Google Play detects "in vehicle" motion (slower, sensor-based);
 *  - the car's Bluetooth connects.
 * All three may launch a foreground service from the background, but the service only
 * gets GPS in the background with "Allow all the time" location.
 */
object AutoStart {

    private const val TAG = TrackingService.TAG
    fun hasBackgroundLocation(context: Context): Boolean =
        Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasFineLocation(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasActivityPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    fun hasBluetoothPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun broadcast(context: Context, code: Int, cls: Class<*>, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context, code, Intent(context, cls).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

    /**
     * (Re)registers both background watchers with Google Play services, or removes them when
     * the setting is off. Registrations do not survive a reboot or an app update, so this runs
     * at every app launch, at boot and after each update, not only when the switch is flipped.
     */
    fun sync(context: Context) {
        val on = Prefs(context).autoStartDriving
        syncDrivingDetection(context, on)
        StandbyService.sync(context)
    }

    private fun syncDrivingDetection(context: Context, on: Boolean) {
        val client = ActivityRecognition.getClient(context)
        val pi = broadcast(context, 10, DrivingReceiver::class.java, DrivingReceiver.ACTION)
        if (!on || !hasActivityPermission(context)) {
            runCatching { client.removeActivityTransitionUpdates(pi) }
            return
        }
        val request = ActivityTransitionRequest(
            listOf(
                ActivityTransition.Builder()
                    .setActivityType(DetectedActivity.IN_VEHICLE)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                    .build(),
            )
        )
        try {
            client.requestActivityTransitionUpdates(request, pi)
        } catch (e: SecurityException) {
            // permission revoked meanwhile; the setting stays off until re-enabled
        }
    }

    /** Fired by Play services when the phone starts moving like a vehicle. */
    class DrivingReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!ActivityTransitionResult.hasResult(intent)) return
            val result = ActivityTransitionResult.extractResult(intent) ?: return
            val entered = result.transitionEvents.any {
                it.activityType == DetectedActivity.IN_VEHICLE && it.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER
            }
            if (entered) maybeStart(context, "driving")
        }

        companion object { const val ACTION = "com.laradars.app.DRIVING" }
    }

    /** Fired by Android when any Bluetooth device connects. */
    class BluetoothReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
            val prefs = Prefs(context)
            if (!prefs.autoStartBluetooth || prefs.bluetoothDevice.isEmpty()) return
            val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            if (device?.address.equals(prefs.bluetoothDevice, ignoreCase = true)) maybeStart(context, "bluetooth")
        }
    }

    /** After a reboot or an app update, Play services has forgotten us: register again. */
    class BootReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) sync(context)
        }
    }

    /** Opens the installer once an app update has finished downloading. */
    class DownloadReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id >= 0) DataUpdater.installDownloaded(context, id)
        }
    }

    private fun maybeStart(context: Context, source: String) {
        if (TrackerState.current.running) return
        if (!hasBackgroundLocation(context)) return
        Log.i(TAG, "auto-start: $source")
        try {
            TrackingService.start(context, source)
        } catch (e: Exception) {
            // Android 12+ may refuse a background start; the standby service retries by speed.
            Log.w(TAG, "background start refused: $e")
        }
    }
}
