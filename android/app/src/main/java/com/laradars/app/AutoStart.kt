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
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity

/**
 * Starts a trip by itself: when Android detects the phone is in a vehicle, or when the car's
 * Bluetooth connects. Both are allowed to launch a foreground service from the background,
 * but the service only gets GPS in the background with "Allow all the time" location.
 */
object AutoStart {

    fun hasBackgroundLocation(context: Context): Boolean =
        Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasActivityPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    fun hasBluetoothPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun transitionIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 10, Intent(context, DrivingReceiver::class.java).setAction(DrivingReceiver.ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    )

    /** Registers (or removes) the "in vehicle" detection with Google Play services. */
    fun syncDrivingDetection(context: Context) {
        val client = ActivityRecognition.getClient(context)
        val pi = transitionIntent(context)
        if (!Prefs(context).autoStartDriving || !hasActivityPermission(context)) {
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
        TrackingService.start(context, source)
    }
}
