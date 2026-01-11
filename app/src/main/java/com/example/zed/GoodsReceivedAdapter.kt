package com.example.zed

import android.content.Intent
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.zed.databinding.GoodsReceivedNoteItemBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Data class remains the same
data class GoodsReceivedItem(
    val requisitionCode: String,
    val user: String,
    val timestamp: Date?,
    val itemCount: Int,
    val totalValue: Double,
    val firstProductName: String,
    val imageUrl: String?
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
        val context = holder.itemView.context

        // --- Bind data ---
        holder.binding.goodsReceivedNoteCode.text = item.requisitionCode

        item.timestamp?.let {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            holder.binding.goodsReceivedNoteDate.text = sdf.format(it)
        } ?: run {
            holder.binding.goodsReceivedNoteDate.text = "No Date"
        }

        // --- ✅ ADD CLICK LISTENER ---
        holder.itemView.setOnClickListener {
            val intent = Intent(context, detailed_goods_received_note::class.java).apply {
                // Pass the unique code to the next activity
                putExtra("REQUISITION_CODE", item.requisitionCode)
            }
            context.startActivity(intent)
        }
    }
}
