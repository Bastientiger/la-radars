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
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.util.Locale

/**
 * Foreground service: listens to the GPS while a trip is on, even when another app
 * (Google Maps, Waze, music) is in front, and raises alerts near cameras.
 */
class TrackingService : Service(), LocationListener {

    private lateinit var locationManager: LocationManager
    private lateinit var prefs: Prefs
    private lateinit var engine: AlertEngine
    private lateinit var units: Units
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastFix: Location? = null
    private var headingFix: Location? = null
    private var heading: Double? = null
    private var recorder: TripRecorder? = null
    private var lastBeepMs = 0L
    private var tone: ToneGenerator? = null
    private val banner by lazy { AlertBanner(this) }
    private var movementConfirmed = false
    private var autoStarted = false
    // Loaded off the main thread: 20 000 points take a moment to index. Until then, no muting.
    @Volatile private var freeways: FreewayIndex? = null
    private var freewayVotes = 0
    private var onFreeway = false

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        prefs = Prefs(this)
        units = Units(prefs.metric)
        engine = AlertEngine(CameraRepository.load(this), prefs.radiusM.toDouble(), prefs.onlyLive)
        Thread { freeways = FreewayIndex.load(this) }.start()
        createChannels()
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setAudioAttributes(speechAttributes)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onError(utteranceId: String?) = releaseFocus()
                    override fun onDone(utteranceId: String?) = releaseFocus()
                })
                ttsReady = true
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { prefs.lastManualStopMs = System.currentTimeMillis(); stopTracking(); return START_NOT_STICKY }
            ACTION_SETTINGS_CHANGED -> {
                engine.radiusM = prefs.radiusM.toDouble()
                engine.onlyLive = prefs.onlyLive
                units = Units(prefs.metric)
                return START_STICKY
            }
            ACTION_RELOAD_DATA -> {
                CameraRepository.invalidate()
                engine = AlertEngine(CameraRepository.load(this), prefs.radiusM.toDouble(), prefs.onlyLive)
                return START_STICKY
            }
        }
        if (TrackerState.current.running) return START_STICKY
        startTracking(intent?.getStringExtra(EXTRA_SOURCE))
        return START_STICKY
    }

    private fun startTracking(source: String?) {
        val notification = buildStatusNotification(getString(R.string.status_waiting_gps))
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        try {
            ServiceCompat.startForeground(this, NOTIF_STATUS, notification, type)
        } catch (e: Exception) {
            // Android refuses a location service started from the background without
            // "allow all the time" permission; nothing we can do from here.
            stopSelf()
            return
        }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "laradars:gps").also { it.acquire() }

        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
            if (Build.VERSION.SDK_INT >= 31 && locationManager.allProviders.contains(LocationManager.FUSED_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.FUSED_PROVIDER, 1000L, 0f, this)
            }
        } catch (e: SecurityException) {
            stopTracking()
            return
        }
        StandbyService.stop(this)
        recorder = TripRecorder()
        movementConfirmed = false
        autoStarted = source != null
        freewayVotes = 0
        onFreeway = false
        engine.reset()
        TrackerState.post(Snapshot(running = true))
        ToggleWidget.refresh(this)
        if (source != null && prefs.voice) speak(getString(R.string.speech_auto_started), "auto")
        DataUpdater.checkIfDue(this)
    }

    private fun stopTracking() {
        locationManager.removeUpdates(this)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        banner.reset()
        recorder?.let { r ->
            val trip = r.finish()
            if (trip.durationMin >= 1 || trip.zones.isNotEmpty()) TripStore.add(this, trip)
            TrackerState.lastTrip = trip
        }
        recorder = null
        engine.reset()
        TrackerState.post(Snapshot(running = false))
        ToggleWidget.refresh(this)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        StandbyService.sync(this)
    }

    override fun onLocationChanged(location: Location) {
        lastFix?.let { if (location.time < it.time - 2000) return }
        lastFix = location
        updateHeading(location)

        val speedMph = if (location.hasSpeed()) Units(false).speed(location.speed) else 0
        updateFreeway(location.latitude, location.longitude, speedMph)
        val update = engine.update(location.latitude, location.longitude, heading, speedMph, muted = onFreeway)
        val insideOf = update.speedingIn ?: update.nearest?.takeIf { it.distanceM <= 45.0 }?.camera
        recorder?.onFix(location.latitude, location.longitude, speedMph, insideOf)

        TrackerState.post(
            Snapshot(
                running = true, hasFix = true,
                lat = location.latitude, lng = location.longitude,
                speedMps = if (location.hasSpeed()) location.speed else 0f,
                headingDeg = heading,
                nearest = update.nearest,
                speeding = update.speedingIn != null,
                onFreeway = onFreeway,
            )
        )

        val n = update.nearest
        val statusText = when {
            onFreeway -> getString(R.string.status_freeway)
            n != null -> getString(R.string.status_nearest, n.camera.street, units.distance(n.distanceM))
            else -> getString(R.string.status_waiting_gps)
        }
        notificationManager.notify(NOTIF_STATUS, buildStatusNotification(statusText))

        update.alerts.forEach { alert(it, speedMph) }
        update.speedingIn?.let { speedingBeep() }
        updateBanner(update, speedMph)
        if (autoStarted && !movementConfirmed && (speedMph >= 5 || heading != null)) {
            movementConfirmed = true
            confirmOnTheRoad()
        }
    }

    /**
     * After an automatic start (driving detected, car Bluetooth), one short pop-up the first
     * time the car is seen moving: the driver knows the app woke up and is watching.
     */
    private fun confirmOnTheRoad() {
        val n = NotificationCompat.Builder(this, CHANNEL_ALERTS_SILENT)
            .setSmallIcon(R.drawable.ic_radar)
            .setContentTitle(getString(R.string.on_road_title))
            .setContentText(getString(R.string.on_road_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setTimeoutAfter(8_000)
            .build()
        notificationManager.notify(NOTIF_ON_ROAD, n)
    }

    /**
     * Freeway mode with a little inertia: three fixes along a freeway to enter it, three
     * fixes away from one to leave it, so an overpass or GPS jitter does not flip it.
     */
    private fun updateFreeway(lat: Double, lng: Double, speedMph: Int) {
        // Stopped in traffic (direction unknown): keep whatever state we were in.
        if (heading == null) return
        val match = freeways?.isOnFreeway(lat, lng, heading, speedMph) ?: false
        freewayVotes = if (match) minOf(3, freewayVotes + 1) else maxOf(-3, freewayVotes - 1)
        android.util.Log.d(TAG, "fwy heading=${heading?.toInt()} closest=${freeways?.lastClosestM?.toInt()}m speed=$speedMph match=$match votes=$freewayVotes")
        val before = onFreeway
        if (freewayVotes >= 3) onFreeway = true
        if (freewayVotes <= -3) onFreeway = false
        if (before != onFreeway) {
            android.util.Log.i(TAG, if (onFreeway) "freeway: alerts muted" else "freeway left: alerts on")
            if (onFreeway) banner.reset()
        }
    }

    /** The bar over other apps: shown from the first alert until the camera is passed. */
    private fun updateBanner(update: AlertEngine.Update, speedMph: Int) {
        val n = update.nearest
        val active = n != null && engine.isAlerting(n.camera.id) && prefs.banner && !TrackerState.appVisible
        if (!active || n == null) {
            banner.hide()
            if (update.alerts.any { it.event == AlertEngine.Event.PASSED }) banner.reset()
            return
        }
        val danger = n.distanceM <= 60.0 || speedMph > n.camera.speedLimitMph
        banner.show(
            n.camera.id, units.distanceLive(n.distanceM),
            getString(R.string.banner_street, n.camera.street, n.camera.speedLimitMph), danger,
        )
    }

    /**
     * Direction of travel: derived from the last two fixes once the car has moved 15 m, and
     * refined with the GPS bearing when the two agree (a bearing that contradicts the actual
     * movement, as some receivers report at low speed, is ignored).
     */
    private fun updateHeading(location: Location) {
        val prev = headingFix
        if (prev == null) { headingFix = location; return }
        val moved = Geo.distance(prev.latitude, prev.longitude, location.latitude, location.longitude)
        if (moved >= 15.0) {
            val derived = Geo.bearing(prev.latitude, prev.longitude, location.latitude, location.longitude)
            val gps = if (location.hasBearing() && location.hasSpeed() && location.speed > 2f) location.bearing.toDouble() else null
            heading = if (gps != null && Geo.angleDiff(gps, derived) < 45.0) Geo.meanBearing(gps, derived) else derived
            headingFix = location
        } else if (location.time - prev.time > 20_000) {
            heading = null  // stationary for a while: direction unknown
            headingFix = location
        }
    }

    private fun alert(a: AlertEngine.Alert, speedMph: Int) {
        val cam = a.camera
        val distance = units.distanceRounded(a.distanceM)
        android.util.Log.i(TAG, "${a.event} ${cam.id} ${cam.street} at $distance, $speedMph mph")
        val statusWord = getString(if (cam.isLive) R.string.status_live else R.string.status_construction)
        val over = speedMph > cam.speedLimitMph + AlertEngine.SPEEDING_OVER_MPH

        val (title, speech) = when (a.event) {
            AlertEngine.Event.APPROACH -> getString(R.string.alert_title, distance) to
                getString(if (over) R.string.speech_alert_over else R.string.speech_alert, units.spokenDistance(a.distanceM), cam.speedLimitMph)
            AlertEngine.Event.HALFWAY -> getString(R.string.alert_title, distance) to
                getString(R.string.speech_halfway, units.spokenDistance(a.distanceM))
            AlertEngine.Event.ENTER -> getString(R.string.alert_enter_title) to
                getString(if (over) R.string.speech_enter_over else R.string.speech_enter, cam.speedLimitMph)
            AlertEngine.Event.PASSED -> getString(R.string.alert_passed_title) to getString(R.string.speech_passed)
        }

        if (a.event == AlertEngine.Event.APPROACH || a.event == AlertEngine.Event.ENTER) {
            // With the bar drawn over the screen, the system's own pop-up would only cover it:
            // keep the notification quiet in the shade and play the sound ourselves.
            val barOnScreen = prefs.banner && AlertBanner.canDraw(this) && !TrackerState.appVisible
            val text = getString(R.string.alert_text, cam.street, cam.from, cam.to, units.limit(cam.speedLimitMph), statusWord)
            val channel = when {
                barOnScreen -> CHANNEL_ALERTS_QUIET
                prefs.sound -> CHANNEL_ALERTS
                else -> CHANNEL_ALERTS_SILENT
            }
            val builder = NotificationCompat.Builder(this, channel)
                .setSmallIcon(R.drawable.ic_radar)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(if (barOnScreen) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
                .setTimeoutAfter(60_000)
            notificationManager.notify(NOTIF_ALERT_BASE + cam.id.hashCode(), builder.build())
            if (prefs.vibration) vibrate()
            if (prefs.sound && barOnScreen) {
                if (tone == null) tone = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
                tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 400)
            }
        }
        if (prefs.voice) speak(speech, "alert-${cam.id}-${a.event}")
    }

    /** Short beep every two seconds while speeding inside a zone. */
    private fun speedingBeep() {
        if (!prefs.sound && !prefs.vibration) return
        val now = System.currentTimeMillis()
        if (now - lastBeepMs < 2000) return
        lastBeepMs = now
        if (prefs.sound) {
            if (tone == null) tone = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 250)
        }
        if (prefs.vibration) vibrate()
    }

    private fun vibrate() {
        val v = if (Build.VERSION.SDK_INT >= 31)
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1))
    }

    private fun speak(text: String, id: String) {
        if (!ttsReady) return
        requestFocus()
        val params = Bundle().apply { putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC) }
        tts?.speak(text, TextToSpeech.QUEUE_ADD, params, id)
    }

    // --- audio focus: duck Google Maps / music while we speak -----------------------------

    private val speechAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private var focusRequest: AudioFocusRequest? = null

    private fun requestFocus() {
        if (focusRequest != null) return
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(speechAttributes)
            .build()
        focusRequest = req
        am.requestAudioFocus(req)
    }

    private fun releaseFocus() {
        if (tts?.isSpeaking == true) return
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        focusRequest?.let { am.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    // --- notifications ---------------------------------------------------------------------

    private val notificationManager get() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannels() {
        val status = NotificationChannel(CHANNEL_STATUS, getString(R.string.channel_status), NotificationManager.IMPORTANCE_LOW)
        val alerts = NotificationChannel(CHANNEL_ALERTS, getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(false)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
        }
        val silent = NotificationChannel(CHANNEL_ALERTS_SILENT, getString(R.string.channel_alerts_silent), NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(false)
            setSound(null, null)
        }
        // No pop-up at all: used while the alert bar is drawn over the screen.
        val quiet = NotificationChannel(CHANNEL_ALERTS_QUIET, getString(R.string.channel_alerts_quiet), NotificationManager.IMPORTANCE_LOW)
        notificationManager.createNotificationChannel(status)
        notificationManager.createNotificationChannel(alerts)
        notificationManager.createNotificationChannel(silent)
        notificationManager.createNotificationChannel(quiet)
    }

    private fun buildStatusNotification(text: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, TrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_radar)
            .setContentTitle(getString(R.string.status_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .addAction(0, getString(R.string.action_stop), stopIntent)
            .build()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    override fun onDestroy() {
        banner.reset()
        locationManager.removeUpdates(this)
        wakeLock?.let { if (it.isHeld) it.release() }
        tts?.shutdown()
        tone?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val TAG = "LARadars"
        const val ACTION_STOP = "com.laradars.app.STOP"
        const val ACTION_SETTINGS_CHANGED = "com.laradars.app.SETTINGS_CHANGED"
        const val ACTION_RELOAD_DATA = "com.laradars.app.RELOAD_DATA"
        const val EXTRA_SOURCE = "source"
        const val CHANNEL_STATUS = "status"
        const val CHANNEL_ALERTS = "alerts"
        const val CHANNEL_ALERTS_SILENT = "alerts_silent"
        const val CHANNEL_ALERTS_QUIET = "alerts_quiet"
        const val NOTIF_STATUS = 1
        const val NOTIF_ON_ROAD = 2
        const val NOTIF_ALERT_BASE = 1000

        /** Start a trip from anywhere (activity, widget, auto-start receivers). */
        fun start(context: Context, source: String? = null) {
            val i = Intent(context, TrackingService::class.java)
            if (source != null) i.putExtra(EXTRA_SOURCE, source)
            androidx.core.content.ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TrackingService::class.java).setAction(ACTION_STOP))
        }

        fun notifySettingsChanged(context: Context) {
            if (TrackerState.current.running)
                context.startService(Intent(context, TrackingService::class.java).setAction(ACTION_SETTINGS_CHANGED))
        }

        fun notifyDataChanged(context: Context) {
            if (TrackerState.current.running)
                context.startService(Intent(context, TrackingService::class.java).setAction(ACTION_RELOAD_DATA))
        }
    }
}
