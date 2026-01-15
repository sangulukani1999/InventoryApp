package com.example.zed

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.zed.databinding.ItemStockVarianceBinding // We will create this layout next
import java.text.SimpleDateFormat
import java.util.Locale

class StockVarianceAdapter(
    private var items: List<StockVarianceItem>
) : RecyclerView.Adapter<StockVarianceAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemStockVarianceBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemStockVarianceBinding.inflate(
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

        holder.binding.productName.text = item.productName
        holder.binding.varianceQuantity.text = "Variance: ${item.quantityVariance}"
        holder.binding.countedQuantity.text = "Counted: ${item.countedQuantity}"

        // Set the text color based on the variance
        val color = when {
            item.quantityVariance < 0 -> ContextCompat.getColor(context, R.color.variance_negative) // Red
            item.quantityVariance > 0 -> ContextCompat.getColor(context, R.color.variance_positive) // Green
            else -> ContextCompat.getColor(context, R.color.black) // Black or default
        }
        holder.binding.varianceQuantity.setTextColor(color)

        // Format and display the timestamp
        item.timestamp?.let {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            holder.binding.timestamp.text = sdf.format(it)
        } ?: run {
            holder.binding.timestamp.text = "No Date"
        }
    }

    fun updateData(newItems: List<StockVarianceItem>) {
        this.items = newItems
        notifyDataSetChanged()
    }
}
