package com.laradars.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.laradars.app.databinding.ActivityMainBinding
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var heroMap: HeroMap

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val fine = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (fine) startTrip() else binding.hint.text = getString(R.string.hint_need_location)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)
        heroMap = HeroMap(this, binding.heroMap)
        AutoStart.sync(this)
        if (!prefs.onboardingDone && intent?.action != ACTION_START_TRIP) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }

        binding.radiusSlider.value = prefs.radiusM.toFloat()
        binding.radiusSlider.addOnChangeListener { _, value, _ ->
            val v = value.roundToInt()
            prefs.radiusM = v
            binding.radiusLabel.text = getString(R.string.radius_label, Units(prefs.metric).radius(v))
            TrackingService.notifySettingsChanged(this)
        }

        binding.toggle.setOnClickListener {
            if (TrackerState.current.running) TrackingService.stop(this) else ensurePermissionsThenStart()
        }
        binding.batteryButton.setOnClickListener { requestIgnoreBatteryOptimizations() }
        binding.mapButton.setOnClickListener { startActivity(Intent(this, MapActivity::class.java)) }
        binding.infoButton.setOnClickListener { startActivity(Intent(this, InfoActivity::class.java)) }
        binding.settingsButton.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.allCamerasButton.setOnClickListener { startActivity(Intent(this, CamerasActivity::class.java)) }
        binding.reportButton.setOnClickListener { reportCameraHere() }

        TrackerState.live.observe(this) { render(it) }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_START_TRIP && !TrackerState.current.running) ensurePermissionsThenStart()
    }

    override fun onResume() {
        super.onResume()
        TrackerState.appVisible = true
        binding.heroMap.onResume()
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        binding.batteryButton.visibility =
            if (pm.isIgnoringBatteryOptimizations(packageName)) View.GONE else View.VISIBLE
        val units = Units(prefs.metric)
        binding.radiusLabel.text = getString(R.string.radius_label, units.radius(prefs.radiusM))
        binding.speedUnit.text = units.speedUnit
        val cameras = CameraRepository.load(this)
        binding.subtitle.text = getString(R.string.subtitle, cameras.size, cameras.count { it.isLive })
        binding.allCamerasButton.text = getString(R.string.all_cameras_button, cameras.size)
        render(TrackerState.current)
    }

    override fun onPause() {
        TrackerState.appVisible = false
        binding.heroMap.onPause()
        super.onPause()
    }

    private fun ensurePermissionsThenStart() {
        val needed = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startTrip() else askPermissions.launch(missing.toTypedArray())
    }

    private fun startTrip() {
        TrackingService.start(this)
        // The tour already covered the bar; this is for people who skipped it.
        if (prefs.banner && !AlertBanner.canDraw(this) && !prefs.bannerAsked && prefs.onboardingDone) {
            prefs.bannerAsked = true
            AlertDialog.Builder(this)
                .setTitle(R.string.banner_permission_title)
                .setMessage(R.string.banner_permission_explain)
                .setPositiveButton(R.string.allow) { _, _ -> openOverlaySettings(this) }
                .setNegativeButton(R.string.later, null)
                .show()
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        runCatching { startActivity(intent) }
    }

    private fun reportCameraHere() {
        val s = TrackerState.current
        val n = s.nearest ?: return
        if (!s.hasFix) return
        AlertDialog.Builder(this)
            .setTitle(R.string.report_title)
            .setMessage(getString(R.string.report_message, n.camera.street))
            .setPositiveButton(R.string.report_confirm) { _, _ ->
                prefs.saveReport(n.camera.id, s.lat, s.lng)
                CameraRepository.invalidate()
                TrackingService.notifyDataChanged(this)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showTripSummary(t: Trip) {
        val units = Units(prefs.metric)
        val msg = getString(
            R.string.trip_summary,
            t.durationMin, units.distance(t.distanceM), t.zones.size, t.speedingZones,
            units.speed(t.maxSpeedMph / 2.23694f), units.speedUnit,
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.trip_done)
            .setMessage(msg)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.trips_all) { _, _ -> startActivity(Intent(this, TripsActivity::class.java)) }
            .show()
    }

    private fun render(s: Snapshot) {
        val units = Units(prefs.metric)
        if (!s.running) TrackerState.lastTrip?.let { showTripSummary(it); TrackerState.lastTrip = null }

        binding.toggle.text = getString(if (s.running) R.string.stop_trip else R.string.start_trip)
        binding.toggle.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (s.running) R.color.stop else R.color.go)
        )
        binding.stateText.text = getString(
            when {
                !s.running -> R.string.state_off
                !s.hasFix -> R.string.state_waiting
                s.onFreeway -> R.string.state_freeway
                else -> R.string.state_on
            }
        )
        binding.speed.text = if (s.hasFix) units.speed(s.speedMps).toString() else "--"

        val n = s.nearest
        val speedMph = (s.speedMps * 2.23694f).roundToInt()
        val inRadius = s.hasFix && n != null && n.distanceM <= prefs.radiusM
        val inZone = s.hasFix && n != null && n.distanceM <= 45.0
        val overLimit = n != null && speedMph > n.camera.speedLimitMph

        // Speed colour: navy normally, amber when over the limit while approaching, red in the zone.
        val speedColor = when {
            inZone && overLimit -> R.color.warn
            inRadius && overLimit -> R.color.amber
            else -> R.color.text
        }
        binding.speed.setTextColor(ContextCompat.getColor(this, speedColor))

        if (s.hasFix && n != null) {
            val cam = n.camera
            binding.nearestCard.visibility = View.VISIBLE
            binding.nearestStreet.text = cam.street
            binding.nearestBetween.text = getString(R.string.between, cam.from, cam.to)
            binding.nearestDistance.text = units.distance(n.distanceM)
            binding.nearestMeta.text = getString(R.string.limit_label, units.limit(cam.speedLimitMph)) + "  ·  " +
                getString(if (cam.isLive) R.string.status_live else R.string.status_construction)
            binding.nearestCard.setCardBackgroundColor(
                ContextCompat.getColor(this, if (inRadius) R.color.zone else R.color.card)
            )
            val fg = ContextCompat.getColor(this, if (inRadius) R.color.text_on_dark else R.color.text)
            val fgMuted = ContextCompat.getColor(this, if (inRadius) R.color.ring3 else R.color.muted)
            binding.nearestStreet.setTextColor(fg)
            binding.nearestLabel.setTextColor(fgMuted)
            binding.nearestBetween.setTextColor(fgMuted)
            binding.nearestMeta.setTextColor(ContextCompat.getColor(this, if (inRadius) R.color.ring4 else R.color.live))
            binding.nearestDistance.setTextColor(ContextCompat.getColor(this, if (inRadius) R.color.ring5 else R.color.accent))
            binding.reportButton.visibility = if (n.distanceM <= 300) View.VISIBLE else View.GONE
            binding.reportButton.setTextColor(ContextCompat.getColor(this, if (inRadius) R.color.ring4 else R.color.live))

            binding.limitBadge.visibility = if (inRadius) View.VISIBLE else View.GONE
            binding.limitBadge.text = getString(R.string.limit_badge, cam.speedLimitMph)
            binding.limitBadge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, speedColor))
            binding.limitBadge.setTextColor(
                ContextCompat.getColor(this, if (speedColor == R.color.amber) R.color.text else R.color.text_on_dark)
            )

            binding.heroMap.visibility = View.VISIBLE
            heroMap.update(s.lat, s.lng, s.headingDeg, cam, n.distanceM)
            binding.pulse.targetScale = PulseView.scaleForDistance(n.distanceM)
            binding.pulse.showArrow = true
        } else {
            binding.nearestCard.visibility = View.GONE
            binding.limitBadge.visibility = View.GONE
            binding.heroMap.visibility = View.INVISIBLE
            binding.pulse.targetScale = 1f
            binding.pulse.showArrow = false
        }
        binding.pulse.periodMs = when {
            !s.running -> 6000L
            inRadius -> 2000L
            else -> 4000L
        }
        binding.pulse.intensity = if (s.running) 1f else 0.5f
        binding.hint.text = getString(if (s.running) R.string.hint_running else R.string.hint_idle)
    }

    companion object {
        const val ACTION_START_TRIP = "com.laradars.app.START_TRIP"

        fun openOverlaySettings(activity: AppCompatActivity) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${activity.packageName}"))
            runCatching { activity.startActivity(intent) }
        }
    }
}
