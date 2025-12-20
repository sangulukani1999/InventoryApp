package com.example.zed

import android.content.Context
import android.graphics.Color
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

class stockListAdaptor(
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
    private val context: Context
) : RecyclerView.Adapter<stockListAdaptor.MenuViewHolder>() {

    // ✅ REMOVED: The manual driveService is no longer needed here.
    // Coil handles image loading directly from the URL.
    // ✅ 1. Add a variable to track the selected position
    private var selectedPosition = RecyclerView.NO_POSITION

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
                    notifyItemChanged(selectedPosition) // Un-highlight the old item
                    selectedPosition = position
                    notifyItemChanged(selectedPosition) // Highlight the new item


                    onItemClick(position)
                }
            }
        }

        fun bind(position: Int) {

            // ✅ 3. Change the background color based on selection state
            if (position == selectedPosition) {
                // Use a color from your colors.xml for consistency
                binding.inventoryCardView.setCardBackgroundColor(ContextCompat.getColor(context, R.color.item_selected_background))
            } else {
                // Set it back to the default color (assuming it's white or transparent)
                binding.inventoryCardView.setCardBackgroundColor(Color.WHITE)
            }

            binding.apply {
                // Define a master log tag
                val logTag = "DataFlow"

                val currentBarcode = inventoryBarcodes[position]
                val currentProductName = productNameInventorys[position]
                val baseQty = inventoryQuantitys.getOrNull(position)?.toDoubleOrNull() ?: 0.0

                inventoryBarcode.text = currentBarcode
                productNameInventory.text = currentProductName
                ProductPriceInventory.text = "ZMW ${productPriceInventory[position]}"

                // --- Primary unit/quantity logic (unchanged) ---
                val unitsForThisProduct = inventoryUnits.getOrNull(position) ?: emptyList()

                if (unitsForThisProduct.isNotEmpty()) {
                    val largestUnit = unitsForThisProduct.maxByOrNull { it.caseUnits.toDoubleOrNull() ?: 1.0 }
                    if (largestUnit != null) {
                        productQtyDescription.text = largestUnit.quantityDescription
                        val unitCaseQty = largestUnit.caseUnits.toDoubleOrNull() ?: 1.0
                        val calculatedQty = if (unitCaseQty > 0) baseQty / unitCaseQty else 0.0
                        inventoryVariance.text = String.format("%.0f", calculatedQty)
                    } else {
                        productQtyDescription.text = "cases"
                        inventoryVariance.text = String.format("%.0f", baseQty)
                    }
                } else {
                    productQtyDescription.text = "cases"
                    inventoryVariance.text = String.format("%.0f", baseQty)
                }

                // ✅ --- START OF CORRECTED IMAGE LOADING LOGIC ---
                // This entire block is simplified to let Coil do the work.

                val imageUrl = ProductImageUrl.getOrNull(position)

                if (!imageUrl.isNullOrBlank()) {
                    // Let Coil handle the Google Drive URL directly. It's smart enough
                    // to follow redirects and load the image efficiently.
                    productImage.load(imageUrl) {
                        crossfade(true)
                        placeholder(R.drawable.ic_placeholder) // Show a placeholder while loading
                        error(R.drawable.ic_error_loading)     // Show an error image if it fails
                    }
                } else {
                    // If the URL is empty or null, show the placeholder.
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
                        val selectedUnitCaseQty = selectedUnit.caseUnits.toDoubleOrNull() ?: 1.0
                        val calculatedQtyOnClick = if (selectedUnitCaseQty > 0) baseQty / selectedUnitCaseQty else 0.0
                        inventoryVariance.text = String.format("%.0f", calculatedQtyOnClick)
                    }
                    productQtyRecyclerView.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
                    productQtyRecyclerView.adapter = unitAdapter
                }
            }
        }
    }
}
