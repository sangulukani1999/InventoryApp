package com.example.zed

import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.zed.databinding.InventoryFragmentItemBinding

class InventoryAdapter(
    // --- SIMPLIFIED PARAMETERS ---
    private val inventoryBarcodes: List<String>,
    private val productNameInventorys: List<String>,
    private val productPriceInventory: List<String>,
    private val ProductImageUrl: List<String?>,
    private val inventoryQuantitys: List<String>,
    private val context: Context,
    private val allLocations: List<LocationItem>
) : RecyclerView.Adapter<InventoryAdapter.MenuViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MenuViewHolder {
        val binding = InventoryFragmentItemBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return MenuViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MenuViewHolder, position: Int) {
        holder.bind(position)
    }

    override fun getItemCount(): Int = inventoryBarcodes.size

    private fun convertDriveUrlToDirect(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val regexFile = Regex("/d/([a-zA-Z0-9_-]+)")
        val matchFile = regexFile.find(url)
        if (matchFile != null) {
            val fileId = matchFile.groupValues[1]
            return "https://drive.google.com/uc?export=download&id=$fileId"
        }
        val regexOpen = Regex("id=([a-zA-Z0-9_-]+)")
        val matchOpen = regexOpen.find(url)
        if (matchOpen != null) {
            val fileId = matchOpen.groupValues[1]
            return "https://drive.google.com/uc?export=download&id=$fileId"
        }
        return url
    }

    inner class MenuViewHolder(private val binding: InventoryFragmentItemBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.inventoryCardView.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    // ✅ THIS IS THE FIX: The Intent now only sends the barcode.
                    // The InventoryItemDetails activity is responsible for fetching its own data.
                    val intent = Intent(context, InventoryItemDetails::class.java).apply {
                        putExtra("inventoryBarcodes", inventoryBarcodes[position])
                    }
                    context.startActivity(intent)
                }
            }
        }

        fun bind(position: Int) {
            binding.apply {
                inventoryBarcode.text = inventoryBarcodes[position]
                productNameInventory.text = productNameInventorys[position]
                ProductPriceInventory.text = productPriceInventory[position]
                inventoryVariance.text = inventoryQuantitys[position]

                val imageUrl = ProductImageUrl.getOrNull(position)
                if (!imageUrl.isNullOrBlank()) {
                    val directUrl = convertDriveUrlToDirect(imageUrl)
                    binding.productImage.load(directUrl) {
                        placeholder(R.drawable.ic_placeholder)
                        error(R.drawable.ic_placeholder)
                    }
                } else {
                    binding.productImage.setImageResource(R.drawable.ic_placeholder)
                }

                val productBarcode = inventoryBarcodes[position]
                val matchingLocations = allLocations.filter {
                    it.barcode?.trim() == productBarcode
                }

                val bubbleItems = mutableListOf<String>()
                val statusList = mutableListOf<Boolean>()

                if (matchingLocations.isEmpty()) {
                    bubbleItems.add("No location found")
                    statusList.add(false)
                } else {
                    for (location in matchingLocations) {
                        val aisle = location.aisle ?: "?"
                        val rack = location.rack ?: "?"
                        val shelf = location.shelf ?: "?"
                        bubbleItems.add("Aisle $aisle, Rack $rack, Shelf $shelf")
                        // ✅ THIS IS THE FIX
                        // For every valid location found, add `true` to make the bubble green.
                        statusList.add(true)
                    }
                }

                recyclerViewBubble.layoutManager =
                    LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
                recyclerViewBubble.adapter = NestedAdapter(bubbleItems, statusList)
            }
        }
    }
}
