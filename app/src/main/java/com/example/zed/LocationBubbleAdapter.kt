package com.example.zed

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.zed.databinding.LocationBubbleItemBinding

class LocationBubbleAdapter(
    private val locations: List<LocationItem>,
    private val countedLocationIds: Set<String>, // Pass the set of counted IDs
    private val onBubbleClick: (location: LocationItem) -> Unit
) : RecyclerView.Adapter<LocationBubbleAdapter.BubbleViewHolder>() {

    inner class BubbleViewHolder(private val binding: LocationBubbleItemBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(location: LocationItem) {
            val locationText = "A:${location.aisle ?: "?"}-R:${location.rack ?: "?"}-S:${location.shelf ?: "?"}"
            binding.bubbleText.text = locationText

            // ✅ COLORING LOGIC: Check if the location's ID is in the counted set
            val isCounted = countedLocationIds.contains(location.id)

            val backgroundResId = if (isCounted) R.drawable.bubble_blue else R.drawable.bubble_blue
            binding.root.background = ContextCompat.getDrawable(binding.root.context, backgroundResId)

            binding.root.setOnClickListener {
                onBubbleClick(location)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BubbleViewHolder {
        val binding = LocationBubbleItemBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return BubbleViewHolder(binding)
    }

    override fun onBindViewHolder(holder: BubbleViewHolder, position: Int) {
        holder.bind(locations[position])
    }

    override fun getItemCount(): Int = locations.size
}
