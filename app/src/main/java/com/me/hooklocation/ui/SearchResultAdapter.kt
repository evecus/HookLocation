package com.me.hooklocation.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.me.hooklocation.R
import com.me.hooklocation.utils.NominatimClient

class SearchResultAdapter(
    private val items: MutableList<NominatimClient.Place> = mutableListOf(),
    private val onSelect: (NominatimClient.Place) -> Unit
) : RecyclerView.Adapter<SearchResultAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tv_place_name)
        val tvFull: TextView = view.findViewById(R.id.tv_place_full)
        val tvCoords: TextView = view.findViewById(R.id.tv_place_coords)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_search_result, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val place = items[position]
        holder.tvName.text = place.shortName
        holder.tvFull.text = place.displayName
        holder.tvCoords.text = "%.6f, %.6f".format(place.latitude, place.longitude)
        holder.itemView.setOnClickListener { onSelect(place) }
    }

    override fun getItemCount() = items.size

    fun updateResults(results: List<NominatimClient.Place>) {
        items.clear()
        items.addAll(results)
        notifyDataSetChanged()
    }
}
