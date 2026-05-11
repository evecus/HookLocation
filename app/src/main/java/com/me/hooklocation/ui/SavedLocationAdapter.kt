package com.me.hooklocation.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.me.hooklocation.R
import com.me.hooklocation.model.SavedLocation
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SavedLocationAdapter(
    private val items: MutableList<SavedLocation>,
    private val onQuickEnable: (SavedLocation) -> Unit,
    private val onDelete: (SavedLocation) -> Unit
) : RecyclerView.Adapter<SavedLocationAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tv_name)
        val tvCoords: TextView = view.findViewById(R.id.tv_coords)
        val tvDate: TextView = view.findViewById(R.id.tv_date)
        val btnEnable: com.google.android.material.button.MaterialButton =
            view.findViewById(R.id.btn_quick_enable)
        val btnDelete: ImageButton = view.findViewById(R.id.btn_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_saved_location, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val loc = items[position]
        holder.tvName.text = loc.name.ifBlank { "未命名位置" }
        holder.tvCoords.text = "WGS-84: %.6f, %.6f".format(loc.wgsLat, loc.wgsLon)
        holder.tvDate.text = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            .format(Date(loc.createdAt))
        holder.btnEnable.setOnClickListener { onQuickEnable(loc) }
        holder.btnDelete.setOnClickListener { onDelete(loc) }
    }

    override fun getItemCount() = items.size

    fun updateList(newList: List<SavedLocation>) {
        items.clear()
        items.addAll(newList)
        notifyDataSetChanged()
    }
}
