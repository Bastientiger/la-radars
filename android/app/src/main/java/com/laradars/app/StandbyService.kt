package com.laradars.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Light "waiting for driving" service. Android only hands a sleeping app its position a few
 * times an hour, so a tiny foreground service (one silent notification) keeps a cheap
 * position check running: cell towers and Wi-Fi every 90 s (network provider), plus any fix
 * another app such as Google Maps obtains meanwhile (passive provider).
 * Car speed seen -> the real tracking service takes over.
 */
class StandbyService : Service(), LocationListener {

    private lateinit var locationManager: LocationManager
    private lateinit var prefs: Prefs
    private var prev: SpeedTrigger.Fix? = null

    override fun onLocationChanged(loc: Location) {
        // The passive provider replays the last known fix on registration: it may be from the
        // end of the previous trip, at car speed. Only fresh positions count.
        if (System.currentTimeMillis() - loc.time > MAX_AGE_MS) return
        val now = SpeedTrigger.Fix(
            loc.latitude, loc.longitude, loc.time, loc.accuracy.toDouble(),
            if (loc.hasSpeed()) loc.speed.toDouble() else null,
        )
        val driving = SpeedTrigger.isDriving(prev, now)
        Log.d(TrackingService.TAG, "standby fix acc=${loc.accuracy} speed=${now.speedMps} driving=$driving")
        prev = now
        if (!driving || TrackerState.current.running) return
        if (loc.time - prefs.lastManualStopMs < MANUAL_STOP_COOLDOWN_MS) return
        Log.i(TrackingService.TAG, "auto-start: speed")
        TrackingService.start(this, "speed")
    }

    @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.channel_standby), NotificationManager.IMPORTANCE_MIN)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!AutoStart.hasFineLocation(this)) { stopSelf(); return START_NOT_STICKY }
        try {
            locationManager.removeUpdates(this)
            val providers = locationManager.allProviders
            if (providers.contains(LocationManager.NETWORK_PROVIDER))
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, INTERVAL_MS, 0f, this)
            if (providers.contains(LocationManager.PASSIVE_PROVIDER))
                locationManager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 15_000L, 0f, this)
        } catch (e: SecurityException) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { locationManager.removeUpdates(this) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 20,
            Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_START_TRIP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_radar)
            .setContentTitle(getString(R.string.standby_title))
            .setContentText(getString(R.string.standby_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val CHANNEL = "standby"
        const val NOTIF_ID = 4
        const val INTERVAL_MS = 90_000L
        const val MAX_AGE_MS = 30_000L
        /** No automatic restart right after the driver stopped a trip by hand. */
        const val MANUAL_STOP_COOLDOWN_MS = 15 * 60_000L

        /** Runs the watcher when auto-start is on and allowed, stops it otherwise. */
        fun sync(context: Context) {
            val prefs = Prefs(context)
            val i = Intent(context, StandbyService::class.java)
            val wanted = prefs.autoStartDriving && AutoStart.hasFineLocation(context) &&
                AutoStart.hasBackgroundLocation(context) && !TrackerState.current.running
            if (wanted) {
                try {
                    ContextCompat.startForegroundService(context, i)
                } catch (e: Exception) {
                    // Background start refused (not allowed from this state): next app launch retries.
                    Log.w(TrackingService.TAG, "standby start refused: $e")
                }
            } else context.stopService(i)
        }

        fun stop(context: Context) = context.stopService(Intent(context, StandbyService::class.java))
    }
}
