package com.example.zed

import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.zed.databinding.StockItemListFragmentBinding
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

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

    private val driveService: Drive by lazy {
        val credential = GoogleAccountCredential.usingOAuth2(context, listOf(DriveScopes.DRIVE_READONLY))
            .apply { selectedAccount = account.account }
        Drive.Builder(
            GoogleNetHttpTransport.newTrustedTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        ).setApplicationName("Nia Bridge App").build()
    }

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

        // In NotFoundAdapter.kt -> MenuViewHolder

        fun bind(position: Int) {
            binding.apply {
                // Define a master log tag
                val logTag = "DataFlow"

                val currentBarcode = inventoryBarcodes[position]
                val currentProductName = productNameInventorys[position]

                inventoryBarcode.text = currentBarcode
                productNameInventory.text = currentProductName
                ProductPriceInventory.text = "ZMW ${productPriceInventory[position]}"
                uncountedLocationsRecyclerView.text = "Variance" // Set static label

                // --- START: VARIANCE CALCULATION LOGIC ---

                // Get theoretical stock in cases from the 'Products' sheet.
                val stockInCases = inventoryQuantitys[position].toDoubleOrNull() ?: 0.0

                // Get all units of measure for the current product.
                val unitsForThisProduct = inventoryUnits.getOrNull(position) ?: emptyList()

                var totalStockInUnits = 0.0
                if (unitsForThisProduct.isNotEmpty()) {
                    // Find the master case size (highest unit).
                    val highestUnit = unitsForThisProduct.mapNotNull { it.caseUnits.toIntOrNull() }.maxOrNull() ?: 1
                    // Calculate the total theoretical stock in single units.
                    totalStockInUnits = stockInCases * highestUnit

                    // Set description to the largest unit's description by default.
                    val largestUnitDescription = unitsForThisProduct.maxByOrNull { it.caseUnits.toIntOrNull() ?: 1 }
                    productQtyDescription.text = largestUnitDescription?.quantityDescription ?: "units"
                } else {
                    // Fallback for products with no defined units.
                    totalStockInUnits = stockInCases // Treat cases as single units
                    productQtyDescription.text = "units"
                }

                // Get the physically counted quantity for this barcode from the new map.
                val countedQty = countedQuantities.getOrDefault(currentBarcode, 0)

                // Calculate the final variance.
                val variance = totalStockInUnits - countedQty

                // Display the variance, formatted as a whole number.
                inventoryVariance.text = String.format("%.0f", variance)

                // --- ✅ START: NEW COLOR LOGIC ---
                val varianceColor = when {
                    variance > 0 -> ContextCompat.getColor(context, R.color.variance_positive) // Green
                    variance < 0 -> ContextCompat.getColor(context, R.color.variance_negative) // Red
                    else -> ContextCompat.getColor(context, R.color.variance_zero)         // Grey
                }
                inventoryVariance.setTextColor(varianceColor)
                // --- ✅ END: NEW COLOR LOGIC ---

                // --- END: VARIANCE CALCULATION LOGIC ---


                // --- Image Loading Logic (unchanged) ---
                val originalUrl = ProductImageUrl.getOrNull(position)
                if (!originalUrl.isNullOrBlank() && originalUrl.contains("drive.google.com")) {
                    val fileId = extractIdFromUrl(originalUrl)
                    if (fileId.isNotBlank()) {
                        productImage.setImageResource(R.drawable.ic_placeholder)
                        itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch(Dispatchers.IO) {
                            try {
                                val outputStream = ByteArrayOutputStream()
                                driveService.files().get(fileId).executeMediaAndDownloadTo(outputStream)
                                withContext(Dispatchers.Main) {
                                    productImage.load(outputStream.toByteArray()) {
                                        crossfade(true).error(R.drawable.ic_error_loading)
                                    }
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    productImage.setImageResource(R.drawable.ic_error_loading)
                                }
                            }
                        }
                    } else { productImage.setImageResource(R.drawable.ic_error_loading) }
                } else if (!originalUrl.isNullOrBlank()) {
                    productImage.load(originalUrl) { placeholder(R.drawable.ic_placeholder).error(R.drawable.ic_error_loading) }
                } else { productImage.setImageResource(R.drawable.ic_placeholder) }


                // --- Bubble Logic (unchanged) ---
                Log.d(logTag, "--- Step 7: Binding Bubbles for '${currentProductName}' (Barcode: $currentBarcode) ---")
                val locationsForItem = inventoryLocations.getOrNull(position) ?: emptyList()
                Log.d(logTag, "   - Received ${locationsForItem.size} location objects for this product.")

                if (locationsForItem.isEmpty()) {
                    Log.d(logTag, "   - Result: HIDING bubble view because location list is empty.")
                    recyclerViewBubble.visibility = View.GONE
                } else {
                    Log.d(logTag, "   - Result: SHOWING bubble view. Now checking status for each bubble...")
                    Log.d(logTag, "   - The 'countedItemsSet' contains: $countedItems") // Log the entire set for reference
                    recyclerViewBubble.visibility = View.VISIBLE

                    val bubbleStatusList = locationsForItem.map { location ->
                        val locationIdFromProduct = location.id
                        val uniqueKeyToSearchFor = "$currentBarcode-$locationIdFromProduct"
                        val isCounted = countedItems.contains(uniqueKeyToSearchFor)
                        Log.d(logTag, "      -> CHECKING bubble for Location ID: '$locationIdFromProduct'")
                        Log.d(logTag, "         - Does countedItemsSet contain the key '$uniqueKeyToSearchFor'? -> $isCounted")
                        isCounted
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
                        // Also update the main text when a different unit is clicked
                        productQtyDescription.text = selectedUnit.quantityDescription
                        // When a smaller unit is clicked, we no longer change the main variance text
                        // inventoryVariance.text = selectedUnit.caseUnits
                    }
                    productQtyRecyclerView.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
                    productQtyRecyclerView.adapter = unitAdapter
                }
            }
        }




    }

    private fun extractIdFromUrl(url: String): String {
        val idFromParam = url.substringAfter("id=", "").substringBefore("&")
        if (idFromParam.isNotBlank()) return idFromParam
        val idFromPath = url.substringAfter("/d/").substringBefore("/")
        if (idFromPath.isNotBlank()) return idFromPath
        return ""
    }
}
