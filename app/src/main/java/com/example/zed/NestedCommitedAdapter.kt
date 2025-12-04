package com.example.zed

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.blue
import androidx.recyclerview.widget.RecyclerView
import com.example.zed.databinding.InventoryFragmentItemBubbleBinding

class NestedCommitedAdapter(
    private val items: List<String>,
    private val statusList: List<Boolean>,
    private val onLocationBubbleClick: (position: Int) -> Unit
) : RecyclerView.Adapter<NestedCommitedAdapter.NestedViewHolder>() { // ✅ 1. Inherit from ITS OWN ViewHolder

    // ✅ 2. Define the ViewHolder as an inner class
    inner class NestedViewHolder(val binding: InventoryFragmentItemBubbleBinding) :
        RecyclerView.ViewHolder(binding.root) {

        // ✅ 3. Reference the TextView inside your bubble layout
        //private val bubbleTextView: TextView = binding.bubbleText

        init {
            // Set a single, efficient click listener for the item
            binding.root.setOnClickListener {
                // Ensure the position is valid before calling the callback
                if (adapterPosition != RecyclerView.NO_POSITION) {
                    // ✅ 4. Call the CORRECT function passed in the constructor
                    onLocationBubbleClick(adapterPosition)
                }
            }
        }

        // This function binds the data to the views
        fun bind(text: String, isActive: Boolean) {
            // ✅ 5. Set the location text on the TextView
           // bubbleTextView.text = text

            // Set the background color based on the status
            val backgroundColor = if (isActive) {
                // Use a color resource for 'active'
                binding.root.context.getColor(R.color.variance_positive)
            } else {
                // Use a color resource for 'inactive'
                binding.root.context.getColor(R.color.variance_negative)
            }
            binding.bubbleSelector.setCardBackgroundColor(backgroundColor)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NestedViewHolder {
        val binding = InventoryFragmentItemBubbleBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return NestedViewHolder(binding)
    }

    override fun onBindViewHolder(holder: NestedViewHolder, position: Int) {
        // Get the data for the current item and call the bind function
        holder.bind(items[position], statusList[position])
    }

    override fun getItemCount(): Int = items.size
}
