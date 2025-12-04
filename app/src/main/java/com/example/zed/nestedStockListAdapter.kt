package com.example.zed

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.zed.databinding.InventoryFragmentItemBubbleBinding

class NestedStockListAdapter(
    private val items: List<String>,
    private val statusList: List<Boolean>,
    private val onBubbleClick: (position: Int) -> Unit
) : RecyclerView.Adapter<NestedStockListAdapter.NestedViewHolder>() {

    inner class NestedViewHolder(val binding: InventoryFragmentItemBubbleBinding) :
        RecyclerView.ViewHolder(binding.root) {

        // ✅ Correctly reference the CardView (for background/clicks) and the TextView (for text)
        val bubbleCard: CardView = binding.bubbleSelector
       // val bubbleTextView: TextView = binding.bubbleText // Assumes ID 'bubbleText' from the XML above

        init {
            // Set the click listener on the entire bubble (the CardView root)
            binding.root.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    // Trigger the callback that was passed into the constructor
                    onBubbleClick(position)
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NestedViewHolder {
        val binding = InventoryFragmentItemBubbleBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return NestedViewHolder(binding)
    }

    override fun onBindViewHolder(holder: NestedViewHolder, position: Int) {
        val item = items[position]
        val isActive = statusList[position]

        // ✅ Set the text on the TextView, not as a tooltip
        //holder.bubbleTextView.text = item

        val context = holder.itemView.context
        val bubbleBackground = if (isActive) {
            R.drawable.bubble_green
        } else {
            R.drawable.bubble_gray
        }
        holder.binding.bubbleSelector.background = ContextCompat.getDrawable(context, bubbleBackground)

        holder.binding.bubbleSelector.setOnClickListener {
            Toast.makeText(context, "Location: $item", Toast.LENGTH_SHORT).show()
        }
    }

    override fun getItemCount(): Int = items.size
}
