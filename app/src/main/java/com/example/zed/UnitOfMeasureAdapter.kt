package com.example.zed

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.zed.databinding.InventoryFragmentItemBubbleBinding

class UnitOfMeasureAdapter(
    private val units: List<UnitOfMeasure>,
    private val onUnitClick: (unit: UnitOfMeasure) -> Unit
) : RecyclerView.Adapter<UnitOfMeasureAdapter.UnitViewHolder>() {

    // Variable to keep track of the selected item's position
    private var selectedPosition = 0 // Default to the first item

    inner class UnitViewHolder(val binding: InventoryFragmentItemBubbleBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            // Set the click listener on the root view of the item
            binding.root.setOnClickListener {
                val position = adapterPosition
                // Ensure the position is valid
                if (position != RecyclerView.NO_POSITION) {
                    // --- THIS IS THE CORE SELECTION LOGIC ---
                    // Only update and redraw if a *new* item is selected
                    if (selectedPosition != position) {
                        // 1. Tell the adapter to redraw the *previously* selected item.
                        //    This will make it revert to the unselected state.
                        notifyItemChanged(selectedPosition)

                        // 2. Update the new selected position.
                        selectedPosition = position

                        // 3. Tell the adapter to redraw the *newly* selected item.
                        //    This will make it adopt the selected state.
                        notifyItemChanged(selectedPosition)
                    }
                    // --- END OF SELECTION LOGIC ---

                    // 4. Always execute the original click logic passed from the activity.
                    onUnitClick(units[position])
                }
            }
        }

        /**
         * Binds data and updates the view based on whether it's the selected one.
         */
        fun bind(unit: UnitOfMeasure, isSelected: Boolean) {
           // binding.bubbleText.text = unit.quantityDescription

            // Change background and text color based on the 'isSelected' flag
            if (isSelected) {
                // --- ACTIVE STATE ---
                binding.bubbleSelector.setBackgroundResource(R.drawable.bubble_blue_selected)
                //binding.bubbleText.setTextColor(ContextCompat.getColor(itemView.context, R.color.selected_text_color))
            } else {
                // --- INACTIVE STATE ---
                binding.bubbleSelector.setBackgroundResource(R.drawable.bubble_blue)
               // binding.bubbleText.setTextColor(ContextCompat.getColor(itemView.context, R.color.unselected_text_color))
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UnitViewHolder {
        val binding = InventoryFragmentItemBubbleBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return UnitViewHolder(binding)
    }

    override fun onBindViewHolder(holder: UnitViewHolder, position: Int) {
        // When binding, check if the current position is the selected one and pass that state
        holder.bind(units[position], position == selectedPosition)
    }

    override fun getItemCount(): Int = units.size
}
