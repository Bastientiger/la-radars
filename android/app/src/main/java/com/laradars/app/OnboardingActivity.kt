package com.laradars.app

import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.materialswitch.MaterialSwitch
import com.laradars.app.databinding.ActivityOnboardingBinding
import com.laradars.app.databinding.ItemPermBinding

/**
 * First-launch tour: why each permission matters, with a button that asks for it right there.
 * Steps: welcome, location, alerts, always ready. The checklist (PermissionsActivity) comes last.
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var b: ActivityOnboardingBinding
    private lateinit var prefs: Prefs
    private var step = 0
    private var pendingPerm: Perm? = null

    private val askPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val p = pendingPerm; pendingPerm = null
        if (ok && p == Perm.DRIVING_DETECTION) { prefs.autoStartDriving = true; AutoStart.syncDrivingDetection(this) }
        if (ok && p == Perm.BLUETOOTH) chooseBluetoothDevice()
        // Android 11+: "all the time" is only offered in the app's settings page.
        if (ok && p == Perm.LOCATION && !Perm.BACKGROUND_LOCATION.granted(this) && android.os.Build.VERSION.SDK_INT >= 30) {
            render()
            return@registerForActivityResult
        }
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)
        step = savedInstanceState?.getInt("step") ?: 0
        b.next.setOnClickListener { if (step < 3) { step++; render() } else finishTour() }
        b.skip.setOnClickListener { if (step < 3) { step++; render() } else finishTour() }
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("step", step)
    }

    override fun onResume() {
        super.onResume()
        render()  // permission states may have changed in the system settings
    }

    private fun finishTour() {
        prefs.onboardingDone = true
        startActivity(Intent(this, PermissionsActivity::class.java).putExtra(PermissionsActivity.EXTRA_ONBOARDING, true))
        finish()
    }

    private fun render() {
        val dots = listOf(b.dot1, b.dot2, b.dot3, b.dot4, b.dot5)
        dots.forEachIndexed { i, v ->
            v.setBackgroundColor(ContextCompat.getColor(this, if (i <= step) R.color.live else R.color.ring1))
        }
        b.cards.removeAllViews()
        b.why.visibility = View.GONE
        b.pulse.visibility = View.GONE
        b.alertPreview.visibility = View.GONE
        b.skip.visibility = View.VISIBLE
        b.skip.text = getString(R.string.onb_skip)
        b.next.text = getString(R.string.onb_continue)

        when (step) {
            0 -> {
                b.stepLabel.text = getString(R.string.onb_step0_label)
                b.title.text = getString(R.string.onb_step0_title)
                b.body.text = getString(R.string.onb_step0_body)
                b.pulse.visibility = View.VISIBLE
                b.next.text = getString(R.string.onb_step0_button)
                b.skip.text = getString(R.string.onb_step0_skip)
            }
            1 -> {
                b.stepLabel.text = getString(R.string.onb_step1_label)
                b.title.text = getString(R.string.onb_step1_title)
                b.body.text = getString(R.string.onb_step1_body)
                b.why.visibility = View.VISIBLE
                b.why.text = getString(R.string.onb_step1_why)
                addCard(1, Perm.LOCATION)
                addCard(2, Perm.BACKGROUND_LOCATION, enabled = Perm.LOCATION.granted(this))
            }
            2 -> {
                b.stepLabel.text = getString(R.string.onb_step2_label)
                b.title.text = getString(R.string.onb_step2_title)
                b.body.text = getString(R.string.onb_step2_body)
                b.alertPreview.visibility = View.VISIBLE
                addCard(3, Perm.NOTIFICATIONS)
                addCard(4, Perm.OVERLAY)
            }
            3 -> {
                b.stepLabel.text = getString(R.string.onb_step3_label)
                b.title.text = getString(R.string.onb_step3_title)
                b.body.text = getString(R.string.onb_step3_body)
                addCard(5, Perm.BATTERY)
                addToggle(6, Perm.DRIVING_DETECTION, prefs.autoStartDriving) { on ->
                    if (on) request(Perm.DRIVING_DETECTION) else { prefs.autoStartDriving = false; AutoStart.syncDrivingDetection(this) }
                }
                addToggle(7, Perm.BLUETOOTH, prefs.autoStartBluetooth) { on ->
                    prefs.autoStartBluetooth = on
                    if (on) request(Perm.BLUETOOTH)
                }
                b.next.text = getString(R.string.onb_step3_button)
            }
        }
    }

    private fun addCard(number: Int, perm: Perm, enabled: Boolean = true) {
        val c = ItemPermBinding.inflate(LayoutInflater.from(this), b.cards, true)
        val done = perm.granted(this)
        c.number.text = if (done) "✓" else number.toString()
        c.number.setBackgroundResource(if (done) R.drawable.perm_circle_done else R.drawable.perm_circle)
        c.title.text = getString(perm.titleRes)
        c.subtitle.text = getString(perm.subtitleRes)
        c.toggle.visibility = View.GONE
        c.action.visibility = View.VISIBLE
        c.action.text = getString(if (done) R.string.perm_done else R.string.perm_allow)
        c.action.isEnabled = !done && enabled
        c.action.alpha = if (enabled || done) 1f else 0.4f
        c.action.setBackgroundResource(if (done) R.drawable.perm_pill_done else R.drawable.perm_pill)
        c.action.setTextColor(ContextCompat.getColor(this, if (done) R.color.ring8 else R.color.text_on_dark))
        c.action.setOnClickListener { request(perm) }
    }

    private fun addToggle(number: Int, perm: Perm, checked: Boolean, onChange: (Boolean) -> Unit) {
        val c = ItemPermBinding.inflate(LayoutInflater.from(this), b.cards, true)
        c.number.text = number.toString()
        c.number.setBackgroundResource(R.drawable.perm_circle)
        c.title.text = getString(perm.titleRes)
        c.subtitle.text = getString(perm.subtitleRes)
        c.action.visibility = View.GONE
        c.toggle.visibility = View.VISIBLE
        (c.toggle as MaterialSwitch).isChecked = checked
        c.toggle.setOnCheckedChangeListener { _, on -> onChange(on) }
    }

    private fun request(perm: Perm) {
        pendingPerm = perm
        val runtime = perm.runtimePermission()
        when {
            perm == Perm.BACKGROUND_LOCATION && android.os.Build.VERSION.SDK_INT >= 30 -> {
                AlertDialog.Builder(this)
                    .setTitle(R.string.background_location_title)
                    .setMessage(R.string.background_location_explain)
                    .setPositiveButton(R.string.open_settings) { _, _ -> perm.openSettings(this) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            runtime != null -> askPermission.launch(runtime)
            else -> perm.openSettings(this)
        }
    }

    private fun chooseBluetoothDevice() {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val devices = try { adapter?.bondedDevices?.toList().orEmpty() } catch (e: SecurityException) { emptyList() }
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
                render()
            }
            .show()
    }
}
