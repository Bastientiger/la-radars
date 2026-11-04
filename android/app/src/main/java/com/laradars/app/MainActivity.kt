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
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.laradars.app.databinding.ActivityMainBinding
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var cameras: List<Camera>
    private lateinit var adapter: CameraAdapter
    private var pendingAutoStart = false

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val fine = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (fine) startTrip() else binding.hint.text = getString(R.string.hint_need_location)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        cameras = CameraRepository.load(this)
        adapter = CameraAdapter(cameras, Units(prefs.metric)) { showCameraDialog(it) }
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.subtitle.text = getString(R.string.subtitle, cameras.size, cameras.count { it.isLive })

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
        binding.reportButton.setOnClickListener { reportCameraHere() }

        binding.search.doAfterTextChanged { adapter.filter(it?.toString().orEmpty(), districtFilter()) }
        val districts = listOf(getString(R.string.all_districts)) + (1..15).map { getString(R.string.district_n, it) }
        binding.district.adapter = ArrayAdapter(this, R.layout.spinner_item, districts).also {
            it.setDropDownViewResource(R.layout.spinner_dropdown_item)
        }
        binding.district.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                adapter.filter(binding.search.text?.toString().orEmpty(), districtFilter())
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

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

    private fun districtFilter(): Int = binding.district.selectedItemPosition  // 0 = all

    override fun onResume() {
        super.onResume()
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        binding.batteryButton.visibility =
            if (pm.isIgnoringBatteryOptimizations(packageName)) View.GONE else View.VISIBLE
        // Settings may have changed units or the camera data.
        val units = Units(prefs.metric)
        binding.radiusLabel.text = getString(R.string.radius_label, units.radius(prefs.radiusM))
        binding.speedUnit.text = units.speedUnit
        val fresh = CameraRepository.load(this)
        if (fresh !== cameras || adapter.units.metric != units.metric) {
            cameras = fresh
            adapter = CameraAdapter(cameras, units) { showCameraDialog(it) }
            binding.list.adapter = adapter
            adapter.filter(binding.search.text?.toString().orEmpty(), districtFilter())
            binding.subtitle.text = getString(R.string.subtitle, cameras.size, cameras.count { it.isLive })
        }
        render(TrackerState.current)
        TrackerState.lastTrip?.let { showTripSummary(it); TrackerState.lastTrip = null }
    }

    private fun ensurePermissionsThenStart() {
        val needed = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startTrip() else askPermissions.launch(missing.toTypedArray())
    }

    private fun startTrip() = TrackingService.start(this)

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
                cameras = CameraRepository.load(this)
                adapter = CameraAdapter(cameras, Units(prefs.metric)) { showCameraDialog(it) }
                binding.list.adapter = adapter
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showCameraDialog(cam: Camera) {
        val units = Units(prefs.metric)
        val status = getString(if (cam.isLive) R.string.status_live else R.string.status_construction)
        val message = getString(R.string.between, cam.from, cam.to) + "\n" +
            getString(R.string.limit_label, units.limit(cam.speedLimitMph)) + " · " + status +
            (if (cam.reportLat != null) "\n" + getString(R.string.report_saved) else "")
        AlertDialog.Builder(this)
            .setTitle(cam.street)
            .setMessage(message)
            .setPositiveButton(R.string.open_google_maps) { _, _ ->
                val label = Uri.encode("${cam.street} speed camera")
                val uri = Uri.parse("geo:${cam.midLat},${cam.midLng}?q=${cam.midLat},${cam.midLng}($label)")
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            }
            .setNeutralButton(R.string.show_on_map) { _, _ ->
                startActivity(Intent(this, MapActivity::class.java).putExtra(MapActivity.EXTRA_CAMERA_ID, cam.id))
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
                else -> R.string.state_on
            }
        )
        binding.speed.text = if (s.hasFix) units.speed(s.speedMps).toString() else "--"

        val n = s.nearest
        val inZone = s.hasFix && n != null && n.distanceM <= prefs.radiusM
        if (s.hasFix && n != null) {
            val cam = n.camera
            binding.nearestCard.visibility = View.VISIBLE
            binding.nearestStreet.text = cam.street
            binding.nearestBetween.text = getString(R.string.between, cam.from, cam.to)
            binding.nearestDistance.text = units.distance(n.distanceM)
            binding.nearestMeta.text = getString(R.string.limit_label, units.limit(cam.speedLimitMph)) + "  ·  " +
                getString(if (cam.isLive) R.string.status_live else R.string.status_construction)
            binding.nearestCard.setCardBackgroundColor(
                ContextCompat.getColor(this, if (inZone) R.color.zone else R.color.card)
            )
            val fg = ContextCompat.getColor(this, if (inZone) R.color.text_on_dark else R.color.text)
            val fgMuted = ContextCompat.getColor(this, if (inZone) R.color.ring3 else R.color.muted)
            binding.nearestStreet.setTextColor(fg)
            binding.nearestLabel.setTextColor(fgMuted)
            binding.nearestBetween.setTextColor(fgMuted)
            binding.nearestMeta.setTextColor(ContextCompat.getColor(this, if (inZone) R.color.ring4 else R.color.live))
            binding.nearestDistance.setTextColor(ContextCompat.getColor(this, if (inZone) R.color.ring5 else R.color.accent))
            binding.reportButton.visibility = if (n.distanceM <= 300) View.VISIBLE else View.GONE
            binding.reportButton.setTextColor(ContextCompat.getColor(this, if (inZone) R.color.ring4 else R.color.live))
            binding.speed.setTextColor(ContextCompat.getColor(this, if (s.speeding) R.color.warn else R.color.text_on_dark))
            adapter.updatePosition(s.lat, s.lng)
        } else {
            binding.nearestCard.visibility = View.GONE
            binding.speed.setTextColor(ContextCompat.getColor(this, R.color.text_on_dark))
        }
        binding.pulse.periodMs = when {
            !s.running -> 6000L
            inZone -> 2000L
            else -> 4000L
        }
        binding.pulse.intensity = if (s.running) 1f else 0.5f
        binding.hint.text = getString(if (s.running) R.string.hint_running else R.string.hint_idle)
    }

    companion object {
        const val ACTION_START_TRIP = "com.laradars.app.START_TRIP"
    }
}
