package com.example.zed

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.zed.databinding.InventoryFragmentItemBubbleBinding

class NestedAdapter(
    private val items: List<String>,
    private val statusList: List<Boolean> // <-- Added status list
) : RecyclerView.Adapter<NestedAdapter.NestedViewHolder>() {

    inner class NestedViewHolder(val binding: InventoryFragmentItemBubbleBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NestedViewHolder {
        val binding = InventoryFragmentItemBubbleBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return NestedViewHolder(binding)
    }

    override fun onBindViewHolder(holder: NestedViewHolder, position: Int) {
        val item = items[position]
        val isActive = statusList[position]

        //holder.binding.bubbleSelector.text = item

        // Set bubble color based on status
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
