package com.laradars.app

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.laradars.app.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding
    private lateinit var prefs: Prefs

    private val askActivity = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        prefs.autoStartDriving = ok
        AutoStart.syncDrivingDetection(this)
        refresh()
    }
    private val askBluetooth = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) chooseBluetoothDevice() else { prefs.autoStartBluetooth = false; refresh() }
    }
    private val askBackground = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)
        b.back.setOnClickListener { finish() }

        b.unitsGroup.setOnCheckedChangeListener { _, id ->
            prefs.metric = id == R.id.unitsMetric
            TrackingService.notifySettingsChanged(this)
        }
        b.voice.setOnCheckedChangeListener { _, on -> prefs.voice = on }
        b.sound.setOnCheckedChangeListener { _, on -> prefs.sound = on }
        b.vibration.setOnCheckedChangeListener { _, on -> prefs.vibration = on }
        b.onlyLive.setOnCheckedChangeListener { _, on -> prefs.onlyLive = on; TrackingService.notifySettingsChanged(this) }

        b.autoDriving.setOnCheckedChangeListener { _, on ->
            if (on && !AutoStart.hasActivityPermission(this) && Build.VERSION.SDK_INT >= 29) {
                askActivity.launch(Manifest.permission.ACTIVITY_RECOGNITION)
            } else {
                prefs.autoStartDriving = on
                AutoStart.syncDrivingDetection(this)
                refresh()
            }
        }
        b.autoBluetooth.setOnCheckedChangeListener { _, on ->
            prefs.autoStartBluetooth = on
            if (on && prefs.bluetoothDevice.isEmpty()) ensureBluetoothThenChoose()
            refresh()
        }
        b.chooseDevice.setOnClickListener { ensureBluetoothThenChoose() }
        b.backgroundLocation.setOnClickListener { requestBackgroundLocation() }

        b.updateNow.setOnClickListener { checkUpdates() }
        b.trips.setOnClickListener { startActivity(Intent(this, TripsActivity::class.java)) }
        b.clearReports.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage(R.string.clear_reports_confirm)
                .setPositiveButton(R.string.clear) { _, _ ->
                    prefs.clearReports(); CameraRepository.invalidate(); TrackingService.notifyDataChanged(this); refresh()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        b.version.text = getString(R.string.version_label, BuildConfig.VERSION_NAME)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        b.unitsGroup.check(if (prefs.metric) R.id.unitsMetric else R.id.unitsUs)
        b.voice.isChecked = prefs.voice
        b.sound.isChecked = prefs.sound
        b.vibration.isChecked = prefs.vibration
        b.onlyLive.isChecked = prefs.onlyLive
        b.autoDriving.isChecked = prefs.autoStartDriving
        b.autoBluetooth.isChecked = prefs.autoStartBluetooth
        b.chooseDevice.visibility = if (prefs.autoStartBluetooth) View.VISIBLE else View.GONE
        b.chooseDevice.text = if (prefs.bluetoothDeviceName.isEmpty()) getString(R.string.choose_device)
        else getString(R.string.device_chosen, prefs.bluetoothDeviceName)

        val needsBackground = (prefs.autoStartDriving || prefs.autoStartBluetooth) && !AutoStart.hasBackgroundLocation(this)
        b.backgroundLocation.visibility = if (needsBackground) View.VISIBLE else View.GONE
        b.autoStartHint.text = getString(
            if (needsBackground) R.string.auto_start_needs_background else R.string.auto_start_hint
        )

        b.dataVersion.text = if (prefs.dataVersion.isEmpty()) getString(R.string.data_bundled)
        else getString(R.string.data_version, prefs.dataVersion)
        b.lastCheck.text = if (prefs.lastDataCheckMs == 0L) getString(R.string.never_checked)
        else getString(R.string.last_checked, DateFormat.getMediumDateFormat(this).format(prefs.lastDataCheckMs))
        b.clearReports.visibility = if (prefs.reports().isEmpty()) View.GONE else View.VISIBLE
    }

    private fun ensureBluetoothThenChoose() {
        if (Build.VERSION.SDK_INT >= 31 && !AutoStart.hasBluetoothPermission(this)) {
            askBluetooth.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else chooseBluetoothDevice()
    }

    private fun chooseBluetoothDevice() {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val devices = try {
            adapter?.bondedDevices?.toList().orEmpty()
        } catch (e: SecurityException) { emptyList() }
        if (devices.isEmpty()) {
            AlertDialog.Builder(this).setMessage(R.string.no_paired_devices).setPositiveButton(android.R.string.ok, null).show()
            return
        }
        val names = devices.map { d -> runCatching { d.name }.getOrNull() ?: d.address }
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_device)
            .setItems(names.toTypedArray()) { _, i ->
                prefs.bluetoothDevice = devices[i].address
                prefs.bluetoothDeviceName = names[i]
                refresh()
            }
            .show()
    }

    private fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT < 29) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            askBackground.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }
        // Android 11+ only grants "all the time" from the app's settings page.
        AlertDialog.Builder(this)
            .setTitle(R.string.background_location_title)
            .setMessage(R.string.background_location_explain)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                if (Build.VERSION.SDK_INT >= 30) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                } else askBackground.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun checkUpdates() {
        b.updateNow.isEnabled = false
        b.lastCheck.text = getString(R.string.checking)
        DataUpdater.check(this) { r ->
            b.updateNow.isEnabled = true
            refresh()
            when {
                r.error != null -> AlertDialog.Builder(this)
                    .setMessage(getString(R.string.update_failed, r.error)).setPositiveButton(android.R.string.ok, null).show()
                r.newApp != null -> AlertDialog.Builder(this)
                    .setTitle(getString(R.string.app_update_title, r.newApp.appVersionName))
                    .setMessage(if (r.dataUpdated) R.string.app_update_and_data else R.string.app_update_message)
                    .setPositiveButton(R.string.download) { _, _ -> DataUpdater.downloadApk(this, r.newApp) }
                    .setNegativeButton(R.string.later, null)
                    .show()
                r.dataUpdated -> AlertDialog.Builder(this)
                    .setMessage(getString(R.string.data_updated, r.dataVersion)).setPositiveButton(android.R.string.ok, null).show()
                else -> AlertDialog.Builder(this)
                    .setMessage(R.string.up_to_date).setPositiveButton(android.R.string.ok, null).show()
            }
        }
    }
}
