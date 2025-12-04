package com.example.zed

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.zed.databinding.CommitItemReviewBeforeCommitBinding

class CommitReviewAdapter(private val items: List<CommitItem>) :
    RecyclerView.Adapter<CommitReviewAdapter.CommitViewHolder>() {

    // This ViewHolder holds the views for a single item in your list.
    // It uses the auto-generated binding class for 'commit_item_review_before_commit.xml'.
    inner class CommitViewHolder(val binding: CommitItemReviewBeforeCommitBinding) :
        RecyclerView.ViewHolder(binding.root) {

        // This function binds the data from a single CommitItem to the views.
        fun bind(item: CommitItem) {
            // Set the product name and barcode from the data item
            binding.productName.text = item.productName
            binding.barcode.text = item.barcode

            // Display and format the variance
            binding.variance.text = "Variance: ${item.variance.toInt()}"

            // Load the product image using the Coil library
            binding.imageView32.load(item.imageUrl) {
                placeholder(R.drawable.ic_placeholder) // A placeholder image while loading
                error(R.drawable.ic_error_loading)     // An image to show if loading fails
            }

            // --- Setup the inner RecyclerView for Location Bubbles ---
            if (item.locations.isNotEmpty()) {
                // If the product has locations, make the bubble area visible
                binding.locationBubble.visibility = View.VISIBLE

                // Create the adapter for the bubbles.
                // ✅ This is the correct adapter to use for the location bubbles.
                val bubbleAdapter = NestedCommitedAdapter(
                    items = item.locations.map { "A${it.aisle} R${it.rack} S${it.shelf}" },
                    statusList = List(item.locations.size) { true }, // All bubbles are 'active' (blue)
                    onLocationBubbleClick = {} // No click action is needed on this review screen
                )

                // Set up the horizontal layout and attach the adapter for the bubbles
                binding.locationBubble.layoutManager =
                    LinearLayoutManager(binding.root.context, LinearLayoutManager.HORIZONTAL, false)
                binding.locationBubble.adapter = bubbleAdapter
            } else {
                // If there are no locations for this product, hide the bubble area
                binding.locationBubble.visibility = View.GONE
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CommitViewHolder {
        // Inflates the layout for a single item (commit_item_review_before_commit.xml)
        val binding = CommitItemReviewBeforeCommitBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return CommitViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CommitViewHolder, position: Int) {
        // Gets the data for the current position and calls the bind function to display it
        holder.bind(items[position])
    }

    // Returns the total number of items in the list
    override fun getItemCount(): Int = items.size
}
