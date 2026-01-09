package com.example.zed

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.zed.databinding.GoodsReceivedNoteItemBinding // Ensure this matches your file name
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Data class to hold all necessary information for one item
data class GoodsReceivedItem(
    val requisitionCode: String,
    val user: String,
    val timestamp: Date?,
    val itemCount: Int,
    val totalValue: Double,
    val firstProductName: String,
    val imageUrl: String? // Added to hold the image URL
)

class GoodsReceivedAdapter(
    private val items: List<GoodsReceivedItem>
) : RecyclerView.Adapter<GoodsReceivedAdapter.ViewHolder>() {

    class ViewHolder(val binding: GoodsReceivedNoteItemBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = GoodsReceivedNoteItemBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]

        // --- Bind data to the correct views from your goods_received_note_item.xml ---

        // 1. Set the text fields
        holder.binding.goodsReceivedNoteCode.text = item.requisitionCode

        // 2. Format the date and set it
        item.timestamp?.let {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            holder.binding.goodsReceivedNoteDate.text = sdf.format(it)
        } ?: run {
            holder.binding.goodsReceivedNoteDate.text = "No Date"
        }


        // The "GRN" status card is static, so no dynamic binding is needed for it.
    }
}
