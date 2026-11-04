package com.laradars.app

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.laradars.app.databinding.ItemCameraBinding

/** The cameras, sorted by distance once we have a GPS fix, by district before that. */
class CameraAdapter(
    private val all: List<Camera>,
    val units: Units,
    private val onClick: (Camera) -> Unit,
) : RecyclerView.Adapter<CameraAdapter.Holder>() {

    private var distances: Map<String, Double> = emptyMap()
    private var query = ""
    private var district = 0
    private var items: List<Camera> = all
    private var lastSortMs = 0L

    class Holder(val b: ItemCameraBinding) : RecyclerView.ViewHolder(b.root)

    fun filter(query: String, district: Int) {
        this.query = query.trim()
        this.district = district
        rebuild()
    }

    fun updatePosition(lat: Double, lng: Double) {
        val now = System.currentTimeMillis()
        if (now - lastSortMs < 2000) return  // re-sorting every second would make the list jump
        lastSortMs = now
        distances = all.associate { it.id to it.distanceTo(lat, lng) }
        rebuild()
    }

    private fun rebuild() {
        var list = all
        if (district > 0) list = list.filter { it.district == district }
        if (query.isNotEmpty()) list = list.filter {
            it.street.contains(query, true) || it.from.contains(query, true) || it.to.contains(query, true)
        }
        if (distances.isNotEmpty()) list = list.sortedBy { distances[it.id] ?: Double.MAX_VALUE }
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemCameraBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: Holder, position: Int) {
        val cam = items[position]
        val dist = distances[cam.id]
        val ctx = h.b.root.context
        h.b.street.text = cam.street
        h.b.between.text = ctx.getString(R.string.between, cam.from, cam.to)
        h.b.limit.text = ctx.getString(R.string.limit_label, units.limit(cam.speedLimitMph))
        h.b.status.text = ctx.getString(if (cam.isLive) R.string.status_live else R.string.status_construction)
        h.b.status.setTextColor(ContextCompat.getColor(ctx, if (cam.isLive) R.color.live else R.color.muted))
        h.b.dot.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(ctx, if (cam.isLive) R.color.accent else R.color.ring2)
        )
        h.b.distance.text = if (dist == null) ctx.getString(R.string.district_short, cam.district) else units.distance(dist)
        h.b.root.setOnClickListener { onClick(cam) }
    }
}
