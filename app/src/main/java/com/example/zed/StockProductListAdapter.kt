package com.example.zed

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.zed.databinding.ItemStockProductStatusBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StockProductListAdapter(
    private val mode: StockProductListMode,
    private val onProductClicked: (StockTakeProduct) -> Unit
) : ListAdapter<
        StockTakeProduct,
        StockProductListAdapter.ProductViewHolder
        >(ProductDiffCallback()) {

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): ProductViewHolder {
        val binding =
            ItemStockProductStatusBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )

        return ProductViewHolder(binding)
    }

    override fun onBindViewHolder(
        holder: ProductViewHolder,
        position: Int
    ) {
        holder.bind(getItem(position))
    }

    inner class ProductViewHolder(
        private val binding: ItemStockProductStatusBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(product: StockTakeProduct) {
            binding.productNameText.text =
                product.productName.ifBlank {
                    "Unnamed product"
                }

            binding.barcodeText.text =
                "Barcode: ${product.barcode}"

            binding.locationText.text =
                product.primaryLocation
                    ?.displayName
                    ?.ifBlank {
                        "Location not assigned"
                    }
                    ?: "Location not assigned"

            binding.expectedText.text =
                formatQuantity(
                    product.expectedQuantity
                )

            binding.countedText.text =
                product.countedQuantity?.let {
                    formatQuantity(it)
                } ?: "—"

            renderVariance(product)
            renderMetadata(product)
            renderImage(product)

            binding.productCard.setOnClickListener {
                onProductClicked(product)
            }
        }

        private fun renderVariance(
            product: StockTakeProduct
        ) {
            if (!product.isCounted) {
                binding.varianceText.text = "—"
                binding.varianceText.setTextColor(
                    Color.parseColor("#7B8492")
                )
                return
            }

            val variance =
                product.variance

            when {
                variance < 0.0 -> {
                    binding.varianceText.text =
                        "-${formatQuantity(-variance)}"

                    binding.varianceText.setTextColor(
                        Color.parseColor("#D92525")
                    )
                }

                variance > 0.0 -> {
                    binding.varianceText.text =
                        "+${formatQuantity(variance)}"

                    binding.varianceText.setTextColor(
                        Color.parseColor("#F57C00")
                    )
                }

                else -> {
                    binding.varianceText.text =
                        "0"

                    binding.varianceText.setTextColor(
                        Color.parseColor("#19A65A")
                    )
                }
            }
        }

        private fun renderMetadata(
            product: StockTakeProduct
        ) {
            if (!product.isCounted) {
                binding.countMetaText.visibility =
                    View.GONE
                return
            }

            binding.countMetaText.visibility =
                View.VISIBLE

            val countedBy =
                product.countedBy
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?: "Unknown user"

            val dateText =
                product.countedAt?.let {
                    formatDateTime(it)
                } ?: "Unknown time"

            binding.countMetaText.text =
                "Counted by $countedBy • $dateText"
        }

        private fun renderImage(
            product: StockTakeProduct
        ) {
            val imageUrl =
                product.imageUrl.orEmpty()

            if (imageUrl.isBlank()) {
                binding.productImage.setImageResource(
                    R.drawable.barcode_icon
                )
                return
            }

            Glide.with(binding.root)
                .load(imageUrl)
                .placeholder(
                    R.drawable.barcode_icon
                )
                .error(
                    R.drawable.barcode_icon
                )
                .into(binding.productImage)
        }

        private fun formatQuantity(
            quantity: Double
        ): String {
            return if (
                quantity % 1.0 == 0.0
            ) {
                quantity.toLong().toString()
            } else {
                String.format(
                    Locale.getDefault(),
                    "%.2f",
                    quantity
                )
            }
        }

        private fun formatDateTime(
            millis: Long
        ): String {
            return SimpleDateFormat(
                "dd MMM yyyy HH:mm",
                Locale.getDefault()
            ).format(Date(millis))
        }
    }

    private class ProductDiffCallback :
        DiffUtil.ItemCallback<StockTakeProduct>() {

        override fun areItemsTheSame(
            oldItem: StockTakeProduct,
            newItem: StockTakeProduct
        ): Boolean {
            return oldItem.productId ==
                    newItem.productId &&
                    oldItem.barcode ==
                    newItem.barcode
        }

        override fun areContentsTheSame(
            oldItem: StockTakeProduct,
            newItem: StockTakeProduct
        ): Boolean {
            return oldItem == newItem
        }
    }
}