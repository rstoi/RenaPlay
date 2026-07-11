package com.baita.renaplay.player

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.baita.renaplay.R

data class TrackOption(
    val label: String,
    val selected: Boolean,
    val action: () -> Unit
)

class TrackOptionAdapter(
    private val options: List<TrackOption>,
    private val onSelected: (TrackOption) -> Unit
) : RecyclerView.Adapter<TrackOptionAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.track_option_title)
        val check: TextView = view.findViewById(R.id.track_option_check)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_track_option, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val option = options[position]
        holder.title.text = option.label
        holder.check.visibility = if (option.selected) View.VISIBLE else View.INVISIBLE
        holder.itemView.setOnClickListener { onSelected(option) }
    }

    override fun getItemCount(): Int = options.size

    fun firstSelectedIndex(): Int = options.indexOfFirst { it.selected }.coerceAtLeast(0)
}
