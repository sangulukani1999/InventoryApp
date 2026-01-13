package com.example.zed

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.zed.databinding.GoodsReceivedNoteItemBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Data class and enum are correct from the previous step.
data class GoodsReceivedItem(
    val requisitionCode: String,
    val user: String,
    val timestamp: Date?,
    val itemCount: Int,
    val totalValue: Double,
    val firstProductName: String,
    val imageUrl: String?,
    val status: RequisitionStatusSangu
)

class GoodsReceivedAdapter(
    private val items: List<GoodsReceivedItem>,
    private val isAdmin: Boolean, // ✅ 1. Add isAdmin flag
    private val onDeleteClicked: (requisitionCode: String) -> Unit // ✅ 2. Add delete callback
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

        // --- Set status color and handle delete button visibility ---
        val colorRes: Int
        var isDeletable = false // Flag to control delete button behavior

        when (item.status) {
            RequisitionStatusSangu.FULLY_RECEIVED -> {
                colorRes = R.color.variance_positive // Green
            }
            RequisitionStatusSangu.PARTIALLY_RECEIVED -> {
                colorRes = R.color.active_filter_color // Yellow
            }
            RequisitionStatusSangu.NOT_RECEIVED -> {
                colorRes = R.color.variance_negative // Red
                isDeletable = true // ✅ Set flag for red items
            }
        }
        holder.binding.grnStatus.setCardBackgroundColor(ContextCompat.getColor(context, colorRes))

        // --- Handle delete button visibility and click listener ---
        if (isDeletable && isAdmin) {
            holder.binding.deleteRequisition.visibility = View.VISIBLE
            holder.binding.deleteRequisition.setOnClickListener {
                onDeleteClicked(item.requisitionCode)
            }
        } else {
            // Hide the button if status is not red OR user is not admin
            holder.binding.deleteRequisition.visibility = View.GONE
            holder.binding.deleteRequisition.setOnClickListener(null) // Remove listener
        }


        // --- Set item click listener ---
        holder.itemView.setOnClickListener {
            val intent = Intent(context, detailed_goods_received_note::class.java).apply {
                putExtra("REQUISITION_CODE", item.requisitionCode)
            }
            context.startActivity(intent)
        }
    }
}
