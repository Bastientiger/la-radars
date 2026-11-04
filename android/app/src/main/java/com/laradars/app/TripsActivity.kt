package com.laradars.app

import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.laradars.app.databinding.ActivityTripsBinding
import com.laradars.app.databinding.ItemTripBinding

/** Past trips: duration, distance, camera zones crossed and where the driver was speeding. */
class TripsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val b = ActivityTripsBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.back.setOnClickListener { finish() }
        val trips = TripStore.all(this)
        b.empty.visibility = if (trips.isEmpty()) View.VISIBLE else View.GONE
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = Adapter(trips, Units(Prefs(this).metric))
        b.clear.visibility = if (trips.isEmpty()) View.GONE else View.VISIBLE
        b.clear.setOnClickListener {
            AlertDialog.Builder(this).setMessage(R.string.clear_trips_confirm)
                .setPositiveButton(R.string.clear) { _, _ -> TripStore.clear(this); recreate() }
                .setNegativeButton(android.R.string.cancel, null).show()
        }
    }

    private inner class Adapter(val trips: List<Trip>, val units: Units) : RecyclerView.Adapter<Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemTripBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = trips.size

        override fun onBindViewHolder(h: Holder, position: Int) {
            val t = trips[position]
            val date = DateFormat.getMediumDateFormat(this@TripsActivity).format(t.startMs) + " " +
                DateFormat.getTimeFormat(this@TripsActivity).format(t.startMs)
            h.b.date.text = date
            h.b.summary.text = getString(
                R.string.trip_line, t.durationMin, units.distance(t.distanceM),
                units.speed(t.maxSpeedMph / 2.23694f), units.speedUnit,
            )
            h.b.zones.text = if (t.zones.isEmpty()) getString(R.string.trip_no_zones)
            else t.zones.joinToString("\n") { z ->
                val over = z.maxSpeedMph > z.limitMph + AlertEngine.SPEEDING_OVER_MPH
                (if (over) "⚠ " else "✓ ") + getString(
                    R.string.trip_zone, z.street, units.speed(z.maxSpeedMph / 2.23694f), units.speedUnit, units.limit(z.limitMph)
                )
            }
            h.b.zones.setTextColor(ContextCompat.getColor(this@TripsActivity, if (t.speedingZones > 0) R.color.warn else R.color.muted))
        }
    }

    private class Holder(val b: ItemTripBinding) : RecyclerView.ViewHolder(b.root)
}
