package com.example.zed

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.zed.databinding.ItemCountingLocationBinding

class CountingLocationAdapter(
    private val onLocationClicked: (
        CountingLocationItem
    ) -> Unit
) : ListAdapter<
        CountingLocationItem,
        CountingLocationAdapter.LocationViewHolder
        >(LocationDiffCallback()) {

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): LocationViewHolder {
        val binding =
            ItemCountingLocationBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )

        return LocationViewHolder(binding)
    }

    override fun onBindViewHolder(
        holder: LocationViewHolder,
        position: Int
    ) {
        holder.bind(getItem(position))
    }

    inner class LocationViewHolder(
        private val binding: ItemCountingLocationBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: CountingLocationItem) {
            binding.locationNameText.text =
                item.name.ifBlank {
                    "Unnamed location"
                }

            binding.locationSummaryText.text =
                buildSummary(item)

            renderStatus(item.status)

            binding.locationCard.setOnClickListener {
                onLocationClicked(item)
            }
        }

        private fun buildSummary(
            item: CountingLocationItem
        ): String {
            val productText =
                when (item.totalProducts) {
                    1 -> "1 product"
                    else -> "${item.totalProducts} products"
                }

            val remainingText =
                when (item.remainingProducts) {
                    1 -> "1 remaining"
                    else -> "${item.remainingProducts} remaining"
                }

            return "$productText • $remainingText"
        }

        private fun renderStatus(
            status: LocationCountStatus
        ) {
            when (status) {
                LocationCountStatus.NOT_STARTED -> {
                    binding.locationStatusText.text =
                        "NOT STARTED"

                    binding.locationStatusText
                        .setTextColor(
                            Color.parseColor("#697386")
                        )

                    binding.locationStatusText
                        .setBackgroundResource(
                            R.drawable.status_not_started_background
                        )
                }

                LocationCountStatus.IN_PROGRESS -> {
                    binding.locationStatusText.text =
                        "IN PROGRESS"

                    binding.locationStatusText
                        .setTextColor(
                            Color.parseColor("#B76A00")
                        )

                    binding.locationStatusText
                        .setBackgroundResource(
                            R.drawable.status_in_progress_background
                        )
                }

                LocationCountStatus.COMPLETED -> {
                    binding.locationStatusText.text =
                        "COMPLETED"

                    binding.locationStatusText
                        .setTextColor(
                            Color.parseColor("#148147")
                        )

                    binding.locationStatusText
                        .setBackgroundResource(
                            R.drawable.status_completed_background
                        )
                }
            }
        }
    }

    private class LocationDiffCallback :
        DiffUtil.ItemCallback<CountingLocationItem>() {

        override fun areItemsTheSame(
            oldItem: CountingLocationItem,
            newItem: CountingLocationItem
        ): Boolean {
            return oldItem.id == newItem.id &&
                    oldItem.level == newItem.level
        }

        override fun areContentsTheSame(
            oldItem: CountingLocationItem,
            newItem: CountingLocationItem
        ): Boolean {
            return oldItem == newItem
        }
    }
}