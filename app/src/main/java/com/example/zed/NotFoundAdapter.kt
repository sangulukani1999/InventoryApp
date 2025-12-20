package com.example.zed

import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.zed.databinding.StockItemListFragmentBinding
import com.google.android.gms.auth.api.signin.GoogleSignInAccount

class NotFoundAdapter(
    private val account: GoogleSignInAccount,
    private val onItemClick: (position: Int) -> Unit,
    private val onLocationBubbleClick: (location: Location) -> Unit,
    private val productNameInventorys: List<String>,
    private val productPriceInventory: List<String>,
    private val ProductImageUrl: List<String?>,
    private val inventoryBarcodes: List<String>,
    private val inventoryQuantitys: List<String>,
    private val inventoryLocations: List<List<Location>>,
    private val inventoryUnits: List<List<UnitOfMeasure>>,
    private val countedItems: Set<String>, // The "evidence" set
    private val countedQuantities: Map<String, Int>,
    private val context: Context
) : RecyclerView.Adapter<NotFoundAdapter.MenuViewHolder>() {

    // ✅ REMOVED: The manual driveService is no longer needed here.
    // Coil will handle loading images directly from the URL.

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MenuViewHolder {
        val binding = StockItemListFragmentBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return MenuViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MenuViewHolder, position: Int) {
        holder.bind(position)
    }

    override fun getItemCount(): Int = inventoryBarcodes.size

    inner class MenuViewHolder(private val binding: StockItemListFragmentBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.inventoryCardView.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(position)
                }
            }
        }

        fun bind(position: Int) {
            binding.apply {
                val logTag = "DataFlow"
                val currentBarcode = inventoryBarcodes[position]
                val currentProductName = productNameInventorys[position]

                inventoryBarcode.text = currentBarcode
                productNameInventory.text = currentProductName
                ProductPriceInventory.text = "ZMW ${productPriceInventory[position]}"
                uncountedLocationsRecyclerView.text = "Variance"

                // --- START: VARIANCE CALCULATION LOGIC (unchanged) ---
                val stockInCases = inventoryQuantitys[position].toDoubleOrNull() ?: 0.0
                val unitsForThisProduct = inventoryUnits.getOrNull(position) ?: emptyList()
                var totalStockInUnits = 0.0

                if (unitsForThisProduct.isNotEmpty()) {
                    val highestUnit = unitsForThisProduct.mapNotNull { it.caseUnits.toIntOrNull() }.maxOrNull() ?: 1
                    totalStockInUnits = stockInCases * highestUnit
                    val largestUnitDescription = unitsForThisProduct.maxByOrNull { it.caseUnits.toIntOrNull() ?: 1 }
                    productQtyDescription.text = largestUnitDescription?.quantityDescription ?: "units"
                } else {
                    totalStockInUnits = stockInCases
                    productQtyDescription.text = "units"
                }

                val countedQty = countedQuantities.getOrDefault(currentBarcode, 0)
                val variance = totalStockInUnits - countedQty
                inventoryVariance.text = String.format("%.0f", variance)

                val varianceColor = when {
                    variance > 0 -> ContextCompat.getColor(context, R.color.variance_positive)
                    variance < 0 -> ContextCompat.getColor(context, R.color.variance_negative)
                    else -> ContextCompat.getColor(context, R.color.variance_zero)
                }
                inventoryVariance.setTextColor(varianceColor)
                // --- END: VARIANCE CALCULATION LOGIC ---

                // ✅ --- START OF CORRECTED IMAGE LOADING LOGIC ---
                // The entire complex block is replaced with this simple, efficient code.

                val imageUrl = ProductImageUrl.getOrNull(position)

                if (!imageUrl.isNullOrBlank()) {
                    // Let Coil handle the Google Drive URL directly.
                    productImage.load(imageUrl) {
                        crossfade(true)
                        placeholder(R.drawable.ic_placeholder)
                        error(R.drawable.ic_placeholder)
                    }
                } else {
                    // If no URL is available, show the placeholder.
                    productImage.setImageResource(R.drawable.ic_placeholder)
                }
                // --- END OF CORRECTED IMAGE LOADING LOGIC ---

                // --- Bubble Logic (unchanged) ---
                val locationsForItem = inventoryLocations.getOrNull(position) ?: emptyList()
                if (locationsForItem.isEmpty()) {
                    recyclerViewBubble.visibility = View.GONE
                } else {
                    recyclerViewBubble.visibility = View.VISIBLE
                    val bubbleStatusList = locationsForItem.map { location ->
                        val uniqueKeyToSearchFor = "$currentBarcode-${location.id}"
                        countedItems.contains(uniqueKeyToSearchFor)
                    }
                    val bubbleAdapter = NestedStockListAdapter(
                        items = locationsForItem.map { "A${it.aisle} R${it.rack} S${it.shelf}" },
                        statusList = bubbleStatusList
                    ) { bubblePosition ->
                        onLocationBubbleClick(locationsForItem[bubblePosition])
                    }
                    recyclerViewBubble.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
                    recyclerViewBubble.adapter = bubbleAdapter
                }

                // --- Unit of Measure Recycler View Logic (unchanged) ---
                if (unitsForThisProduct.isEmpty()) {
                    productQtyRecyclerView.visibility = View.GONE
                } else {
                    productQtyRecyclerView.visibility = View.VISIBLE
                    val unitAdapter = UnitOfMeasureAdapter(unitsForThisProduct) { selectedUnit ->
                        ProductPriceInventory.text = "ZMW ${selectedUnit.sellingPrice}"
                        productQtyDescription.text = selectedUnit.quantityDescription
                    }
                    productQtyRecyclerView.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
                    productQtyRecyclerView.adapter = unitAdapter
                }
            }
        }
    }
    // ✅ REMOVED: The extractIdFromUrl function is no longer needed.
}
