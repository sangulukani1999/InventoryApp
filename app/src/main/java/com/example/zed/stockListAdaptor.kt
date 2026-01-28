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

    // ✅ THIS IS THE FIX: A public property for external highlighting (from search).
    // The fragment will set this value.
    var highlightedPosition: Int = RecyclerView.NO_POSITION

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
                    // When an item is clicked, update the highlighted position
                    val previouslyHighlighted = highlightedPosition
                    highlightedPosition = position

                    // Notify to un-highlight the old item and highlight the new one
                    if (previouslyHighlighted != RecyclerView.NO_POSITION) {
                        notifyItemChanged(previouslyHighlighted)
                    }
                    notifyItemChanged(highlightedPosition)

                    // Perform the original click action
                    onItemClick(position)
                }
            }
        }

        fun bind(position: Int) {
            // Change the background color based on the public highlightedPosition property
            if (position == highlightedPosition) {
                // Use a color from your colors.xml for consistency
                binding.inventoryCardView.setCardBackgroundColor(ContextCompat.getColor(context, R.color.item_selected_background))
            } else {
                // Set it back to the default color
                binding.inventoryCardView.setCardBackgroundColor(Color.WHITE)
            }

            binding.apply {
                val logTag = "DataFlow"

                val currentBarcode = inventoryBarcodes[position]
                val currentProductName = productNameInventorys[position]
                val baseQty = inventoryQuantitys.getOrNull(position)?.toDoubleOrNull() ?: 0.0

                inventoryBarcode.text = currentBarcode
                productNameInventory.text = currentProductName
                ProductPriceInventory.text = "ZMW ${productPriceInventory[position]}"

                // --- Primary unit/quantity logic ---
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

                // --- Image Loading Logic ---
                val imageUrl = ProductImageUrl.getOrNull(position)

                if (!imageUrl.isNullOrBlank()) {
                    productImage.load(imageUrl) {
                        crossfade(true)
                        placeholder(R.drawable.ic_placeholder)
                        error(R.drawable.ic_error_loading)
                    }
                } else {
                    productImage.setImageResource(R.drawable.ic_placeholder)
                }

                // --- Bubble Logic ---
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

                // --- Unit of Measure Recycler View Logic ---
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
