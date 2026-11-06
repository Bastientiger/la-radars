package com.laradars.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.laradars.app.databinding.ActivityPermissionsBinding
import com.laradars.app.databinding.ItemPermBinding

/** The checklist: every permission, green or with an Allow button. Last step of the tour, and in Settings. */
class PermissionsActivity : AppCompatActivity() {

    private lateinit var b: ActivityPermissionsBinding
    private var onboarding = false
    private var pendingPerm: Perm? = null

    private val askPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val p = pendingPerm; pendingPerm = null
        if (ok && p == Perm.DRIVING_DETECTION) { Prefs(this).autoStartDriving = true; AutoStart.sync(this) }
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPermissionsBinding.inflate(layoutInflater)
        setContentView(b.root)
        onboarding = intent.getBooleanExtra(EXTRA_ONBOARDING, false)
        b.back.visibility = if (onboarding) View.GONE else View.VISIBLE
        b.back.setOnClickListener { finish() }
        b.stepLabel.visibility = if (onboarding) View.VISIBLE else View.GONE
        b.title.text = getString(if (onboarding) R.string.perms_title_onboarding else R.string.perms_title)
        b.next.setOnClickListener {
            if (onboarding) {
                startActivity(Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_START_TRIP))
            }
            finish()
        }
        b.skip.visibility = if (onboarding) View.VISIBLE else View.GONE
        b.skip.setOnClickListener { startActivity(Intent(this, MainActivity::class.java)); finish() }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        b.cards.removeAllViews()
        var missing = 0
        for (perm in Perm.values()) {
            val c = ItemPermBinding.inflate(LayoutInflater.from(this), b.cards, true)
            val done = perm.granted(this)
            c.title.text = getString(perm.titleRes)
            c.subtitle.text = when {
                perm == Perm.BATTERY && Build.MANUFACTURER.equals("samsung", ignoreCase = true) -> getString(R.string.perm_battery_sub_samsung)
                !done && perm.optional -> getString(R.string.perm_optional_sub)
                else -> getString(perm.subtitleRes)
            }
            c.toggle.visibility = View.GONE
            c.action.visibility = View.VISIBLE
            when {
                done -> {
                    c.number.text = "✓"; c.number.setBackgroundResource(R.drawable.perm_circle_done)
                    c.action.text = getString(R.string.perm_on)
                    c.action.setBackgroundResource(R.drawable.perm_pill_done)
                    c.action.setTextColor(ContextCompat.getColor(this, R.color.ring8))
                    c.action.isEnabled = false
                }
                perm.optional -> {
                    c.number.text = "–"; c.number.setBackgroundResource(R.drawable.perm_circle_optional)
                    c.action.text = getString(R.string.perm_optional)
                    c.action.setBackgroundResource(R.drawable.perm_pill_optional)
                    c.action.setTextColor(ContextCompat.getColor(this, R.color.text))
                    c.action.setOnClickListener { request(perm) }
                }
                else -> {
                    missing++
                    c.number.text = "!"; c.number.setBackgroundResource(R.drawable.perm_circle_missing)
                    c.action.text = getString(R.string.perm_allow)
                    c.action.setBackgroundResource(R.drawable.perm_pill)
                    c.action.setTextColor(ContextCompat.getColor(this, R.color.text_on_dark))
                    c.action.setOnClickListener { request(perm) }
                }
            }
        }
        b.body.text = getString(if (missing == 0) R.string.perms_all_good else R.string.perms_body)
        b.next.text = getString(if (onboarding) R.string.start_trip else R.string.perms_done)
        b.skip.text = getString(if (missing == 0) R.string.perms_skip_ok else R.string.perms_skip_missing)
    }

    private fun request(perm: Perm) {
        pendingPerm = perm
        when {
            perm == Perm.BLUETOOTH -> startActivity(Intent(this, SettingsActivity::class.java))
            perm == Perm.BACKGROUND_LOCATION && Build.VERSION.SDK_INT >= 30 -> {
                if (!Perm.LOCATION.granted(this)) { request(Perm.LOCATION); return }
                AlertDialog.Builder(this)
                    .setTitle(R.string.background_location_title)
                    .setMessage(R.string.background_location_explain)
                    .setPositiveButton(R.string.open_settings) { _, _ -> perm.openSettings(this) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            perm.runtimePermission() != null -> askPermission.launch(perm.runtimePermission()!!)
            else -> perm.openSettings(this)
        }
    }

    companion object { const val EXTRA_ONBOARDING = "onboarding" }
}
