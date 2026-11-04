package com.laradars.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.laradars.app.databinding.ActivityCamerasBinding

/** The full list of cameras: search by street, filter by district, sorted by distance when driving. */
class CamerasActivity : AppCompatActivity() {

    private lateinit var b: ActivityCamerasBinding
    private lateinit var adapter: CameraAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityCamerasBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.back.setOnClickListener { finish() }

        val prefs = Prefs(this)
        val cameras = CameraRepository.load(this)
        b.subtitle.text = getString(R.string.subtitle, cameras.size, cameras.count { it.isLive })
        adapter = CameraAdapter(cameras, Units(prefs.metric)) { showCameraDialog(it, Units(prefs.metric)) }
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter

        b.search.doAfterTextChanged { adapter.filter(it?.toString().orEmpty(), b.district.selectedItemPosition) }
        val districts = listOf(getString(R.string.all_districts)) + (1..15).map { getString(R.string.district_n, it) }
        b.district.adapter = ArrayAdapter(this, R.layout.spinner_item, districts).also {
            it.setDropDownViewResource(R.layout.spinner_dropdown_item)
        }
        b.district.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                adapter.filter(b.search.text?.toString().orEmpty(), pos)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        TrackerState.live.observe(this) { s -> if (s.hasFix) adapter.updatePosition(s.lat, s.lng) }
    }

    private fun showCameraDialog(cam: Camera, units: Units) {
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
}
