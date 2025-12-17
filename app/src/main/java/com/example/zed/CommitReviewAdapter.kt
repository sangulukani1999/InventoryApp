package com.example.zed

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.zed.databinding.CommitItemReviewBeforeCommitBinding // Make sure this import is correct
import java.text.DecimalFormat

class CommitReviewAdapter(private val items: List<CommitItem>) :
    RecyclerView.Adapter<CommitReviewAdapter.ViewHolder>() {

    // The ViewHolder holds a reference to the binding of the inflated XML layout.
    inner class ViewHolder(val binding: CommitItemReviewBeforeCommitBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        // Inflate the XML layout for a single row using ViewBinding.
        val binding = CommitItemReviewBeforeCommitBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun getItemCount(): Int {
        return items.size
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        // Get the data object for the current row.
        val item = items[position]
        val context = holder.itemView.context

        // Use the binding object to access the views in your XML layout.
        // The names here (e.g., binding.productName) MUST match the IDs in your XML.
        holder.binding.apply {
            // --- THIS IS THE FIX ---
            // Use the correct IDs from your XML file.
            // ViewBinding converts snake_case (product_name) to camelCase (productName).

            // Set product name using the ID "product_name"
            productName.text = item.productName

            // Set barcode text using the ID "barcode"
            barcode.text = item.barcode

            // Load the product image using the ID "imageView32"
            imageView32.load(item.imageUrl) {
                placeholder(R.drawable.ic_placeholder) // A placeholder drawable
                error(R.drawable.ic_placeholder)     // An error drawable
            }

            // Format and display the variance using the ID "variance"
            val varianceFormat = DecimalFormat("#,##0.##")
            val varianceString = "Variance: ${varianceFormat.format(item.variance)}"
            variance.text = varianceString

            // Set the color of the variance text based on its value
            when {
                item.variance > 0 -> {
                    // Positive variance (over-counted)
                    variance.setTextColor(context.getColor(R.color.variance_positive)) // e.g., blue or green
                }
                item.variance < 0 -> {
                    // Negative variance (under-counted)
                    variance.setTextColor(context.getColor(R.color.variance_negative)) // e.g., red
                }
                else -> {
                    // Zero variance
                    variance.setTextColor(context.getColor(R.color.variance_zero))     // e.g., gray or black
                }
            }

            // Note: You also have a RecyclerView with the ID "location_bubble".
            // If you need to display location bubbles, you would set up its adapter here.
            // For example:
            // val locationAdapter = LocationBubbleAdapter(item.locations)
            // locationBubble.adapter = locationAdapter
            // locationBubble.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        }
    }
}
