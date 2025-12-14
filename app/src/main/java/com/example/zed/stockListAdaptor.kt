package com.example.zed

import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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

        fun bind(position: Int) {
            binding.apply {
                // Define a master log tag
                val logTag = "DataFlow"

                val currentBarcode = inventoryBarcodes[position]
                val currentProductName = productNameInventorys[position]
                val baseQty = inventoryQuantitys.getOrNull(position)?.toDoubleOrNull() ?: 0.0

                inventoryBarcode.text = currentBarcode
                productNameInventory.text = currentProductName
                ProductPriceInventory.text = "ZMW ${productPriceInventory[position]}"

                // --- THIS IS THE PRIMARY LOGIC ---

                // 1. Get all units of measure for the current product.
                val unitsForThisProduct = inventoryUnits.getOrNull(position) ?: emptyList()

                if (unitsForThisProduct.isNotEmpty()) {
                    // 2. Find the unit with the highest 'caseUnits' value to be the DEFAULT display.
                    val largestUnit = unitsForThisProduct.maxByOrNull { it.caseUnits.toDoubleOrNull() ?: 1.0 }

                    if (largestUnit != null) {
                        // 3. Set the default text based on the found unit.
                        productQtyDescription.text = largestUnit.quantityDescription

                        // --- THIS IS THE DEFAULT CALCULATION ---
                        val unitCaseQty = largestUnit.caseUnits.toDoubleOrNull() ?: 1.0

                        val calculatedQty = if (unitCaseQty > 0) {
                            baseQty / unitCaseQty
                        } else {
                            0.0
                        }
                        inventoryVariance.text = String.format("%.0f", calculatedQty)
                        // --- END OF DEFAULT CALCULATION ---

                    } else {
                        // Fallback if no valid largest unit is found
                        productQtyDescription.text = "cases"
                        inventoryVariance.text = String.format("%.0f", baseQty)
                    }
                } else {
                    // 4. Fallback for products with no defined units.
                    productQtyDescription.text = "cases"
                    inventoryVariance.text = String.format("%.0f", baseQty)
                }

                // --- END OF PRIMARY LOGIC ---

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


                // --- Bubble Logic (unchanged)---
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


                // --- Unit of Measure Recycler View Logic (WITH NEW CALCULATION) ---
                if (unitsForThisProduct.isEmpty()) {
                    productQtyRecyclerView.visibility = View.GONE
                } else {
                    productQtyRecyclerView.visibility = View.VISIBLE
                    // The click listener now contains the new calculation logic
                    val unitAdapter = UnitOfMeasureAdapter(unitsForThisProduct) { selectedUnit ->
                        // Update price and description as before
                        ProductPriceInventory.text = "ZMW ${selectedUnit.sellingPrice}"
                        productQtyDescription.text = selectedUnit.quantityDescription

                        // --- NEW DYNAMIC CALCULATION ON CLICK ---
                        val selectedUnitCaseQty = selectedUnit.caseUnits.toDoubleOrNull() ?: 1.0
                        val calculatedQtyOnClick = if (selectedUnitCaseQty > 0) {
                            baseQty / selectedUnitCaseQty
                        } else {
                            0.0
                        }
                        inventoryVariance.text = String.format("%.0f", calculatedQtyOnClick)
                        // --- END OF NEW DYNAMIC CALCULATION ---
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
