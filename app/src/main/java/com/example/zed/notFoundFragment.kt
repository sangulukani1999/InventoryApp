package com.example.zed

import android.app.ProgressDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.zed.databinding.ActivityStockFragmentBinding
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.SheetsScopes
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONArray
import org.json.JSONException
import java.io.IOException

// Import the shared data classes from Models.kt
import com.example.zed.Product
import com.example.zed.Location
import com.example.zed.UnitOfMeasure

class notFoundFragment : Fragment() {
    private var _binding: ActivityStockFragmentBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ActivityStockFragmentBinding.inflate(inflater, container, false)
        binding.varianceAdd.visibility = View.GONE
        binding.StockListRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        setupClickListeners()
        fetchInventoryData()
        return binding.root
    }

    private fun setupClickListeners() {
        binding.varianceAdd.setOnClickListener {
            val currentUser = Firebase.auth.currentUser
            if (currentUser?.email == null) {
                Toast.makeText(requireContext(), "Cannot add product: User not signed in.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val userEmail = currentUser.email!!
            val progressDialog = ProgressDialog(requireContext()).apply {
                setMessage("Verifying user role...")
                setCancelable(false)
                show()
            }
            checkUserRole(userEmail) { exists, parentEmail ->
                progressDialog.dismiss()
                if (exists) {
                    MyBottomStockSheet(userEmail, parentEmail) {
                        fetchInventoryData() // Refresh callback
                    }.show(parentFragmentManager, "MyBottomSheet")
                } else {
                    Toast.makeText(requireContext(), "Access denied. User not found in registry.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun checkUserRole(email: String, callback: (exists: Boolean, parentEmail: String?) -> Unit) {
        val client = OkHttpClient()
        val request = Request.Builder()
            .url("https://opensheet.elk.sh/1W-LOkSgPPrfhZ_kqfycUvOcGviQplMng6xpc6KBQ9Ik/users")
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                activity?.runOnUiThread { callback(false, null) }
            }
            override fun onResponse(call: Call, response: Response) {
                var isFound = false
                if (response.isSuccessful) {
                    try {
                        val jsonArray = JSONArray(response.body?.string() ?: "")
                        for (i in 0 until jsonArray.length()) {
                            val obj = jsonArray.getJSONObject(i)
                            val mainEmail = obj.optString("email").trim()
                            val subEmail = obj.optString("email sub user").trim()
                            if (email.equals(mainEmail, ignoreCase = true)) {
                                activity?.runOnUiThread { callback(true, null) }; return
                            }
                            if (subEmail.isNotEmpty() && email.equals(subEmail, ignoreCase = true)) {
                                activity?.runOnUiThread { callback(true, mainEmail) }; return
                            }
                        }
                    } catch (e: JSONException) {
                        Log.e("UserRoleCheck", "JSON parsing error", e)
                    }
                }
                if (!isFound) {
                    activity?.runOnUiThread { callback(false, null) }
                }
            }
        })
    }

    private fun fetchInventoryData() {
        val progressDialog = ProgressDialog(requireContext()).apply {
            setMessage("Fetching inventory...")
            setCancelable(false)
            show()
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val logTag = "DataFlow"
                Log.d(logTag, "====== STARTING INVENTORY FETCH ======")

                val account = GoogleSignIn.getLastSignedInAccount(requireContext())
                    ?: throw IllegalStateException("User is not signed in. Cannot fetch data.")

                val sheetsService = getSheetsService(account)
                val driveService = getDriveService(account)
                val spreadsheetId = findSheetIdByName(driveService, "nia-bridge data")
                    ?: throw IllegalStateException("Spreadsheet 'nia-bridge data' not found.")
                Log.d(logTag, "Found Spreadsheet ID: $spreadsheetId")


                // --- 1. Fetching from Products sheet ---
                Log.d(logTag, "--- Step 1: Fetching Products ---")
                val productsRange = "Products!A2:K"
                val productsResponse = sheetsService.spreadsheets().values().get(spreadsheetId, productsRange).execute()
                val productList = productsResponse.getValues()?.mapNotNull { row ->
                    if (row.size < 4) return@mapNotNull null
                    Product(
                        id = row.getOrNull(0)?.toString() ?: "",
                        name = row.getOrNull(1)?.toString() ?: "",
                        imageUrl = row.getOrNull(2)?.toString() ?: "",
                        barcode = row.getOrNull(3)?.toString() ?: "",
                        categoryId = row.getOrNull(4)?.toString() ?: "",
                        unit = row.getOrNull(5)?.toString() ?: "",
                        caseQty = row.getOrNull(6)?.toString() ?: "0",
                        minOrder = row.getOrNull(7)?.toString() ?: "0",
                        unitCost = row.getOrNull(8)?.toString() ?: "0.00",
                        locationIds = row.getOrNull(9)?.toString()?.removeSurrounding("['", "']")?.split("', '")?.filter { it.isNotBlank() } ?: emptyList(),
                        expiryDate = row.getOrNull(10)?.toString() ?: ""
                    )
                } ?: emptyList()
                Log.d(logTag, "Found ${productList.size} total products initially.")


                // --- 2. Fetching from Locations sheet (As per your final schema) ---
                Log.d(logTag, "--- Step 2: Fetching Locations ---")
                val locationsRange = "product_location!A2:D" // LocationID, Aisle, Rack, Shelf
                val locationsResponse = sheetsService.spreadsheets().values().get(spreadsheetId, locationsRange).execute()
                val locationMap = locationsResponse.getValues()?.associate { row ->
                    val id = row.getOrNull(0)?.toString() ?: "" // ID is in Column A (index 0)
                    id to Location(
                        id = id,
                        aisle = row.getOrNull(1)?.toString() ?: "N/A",
                        rack = row.getOrNull(2)?.toString() ?: "N/A",
                        shelf = row.getOrNull(3)?.toString() ?: "N/A"
                    )
                } ?: emptyMap()
                Log.d(logTag, "Built locationMap with ${locationMap.size} entries.")


                // --- 3. Fetching from Units sheet ---
                Log.d(logTag, "--- Step 3: Fetching Units of Measure ---")
                val unitsRange = "unit_measure!A2:H"
                val unitsResponse = sheetsService.spreadsheets().values().get(spreadsheetId, unitsRange).execute()
                val allUnits = unitsResponse.getValues()?.mapNotNull { row ->
                    if (row.size < 8) return@mapNotNull null
                    UnitOfMeasure(
                        productId = row.getOrNull(0)?.toString() ?: "", unitBarcode = row.getOrNull(1)?.toString() ?: "",
                        sellingPrice = row.getOrNull(2)?.toString() ?: "0.00", caseUnits = row.getOrNull(3)?.toString() ?: "1",
                        quantityDescription = row.getOrNull(4)?.toString() ?: "Unit", cost = row.getOrNull(5)?.toString(),
                        updatedBy = row.getOrNull(6)?.toString(), timestamp = row.getOrNull(7)?.toString()
                    )
                } ?: emptyList()
                val unitsMap = allUnits.groupBy { it.productId }
                Log.d(logTag, "Built unitsMap with ${unitsMap.size} product groups.")


                // --- 4. Fetch Count Data and Aggregating Totals ---
                Log.d(logTag, "--- Step 4: Fetching Count Data and Aggregating Totals ---")
                val countedItemsSet = mutableSetOf<String>()
                val countedQuantitiesMap = mutableMapOf<String, Int>()
                val countDataRange = "countData!B2:E" // Barcode(B), Timestamp(C), LocationID(D), Qty(E)
                val countDataResponse = sheetsService.spreadsheets().values().get(spreadsheetId, countDataRange).execute()
                countDataResponse.getValues()?.forEach { row ->
                    val barcode = row.getOrNull(0)?.toString()
                    val locationId = row.getOrNull(2)?.toString()
                    val quantity = row.getOrNull(3)?.toString()?.toIntOrNull() ?: 0

                    if (!barcode.isNullOrBlank()) {
                        // For bubble status
                        if (!locationId.isNullOrBlank()) {
                            countedItemsSet.add("$barcode-$locationId")
                        }
                        // For variance calculation
                        val currentTotal = countedQuantitiesMap.getOrDefault(barcode, 0)
                        countedQuantitiesMap[barcode] = currentTotal + quantity
                    }
                }
                Log.d(logTag, "Final 'countedItemsSet' has ${countedItemsSet.size} items.")
                Log.d(logTag, "Final 'countedQuantitiesMap': $countedQuantitiesMap")


                // --- 5. Prepare data for Adapter WITH FILTERING ---
                Log.d(logTag, "--- Step 5: Preparing and Filtering Data for Adapter ---")
                val names = mutableListOf<String>()
                val prices = mutableListOf<String>()
                val images = mutableListOf<String?>()
                val barcodes = mutableListOf<String>()
                val quantities = mutableListOf<String>()
                val productLocationsList = mutableListOf<List<Location>>()
                val productUnitsList = mutableListOf<List<UnitOfMeasure>>()

                for (product in productList) {
                    // --- Prepare all necessary data for this one product ---
                    val locationsForThisProduct = product.locationIds.mapNotNull { locationId -> locationMap[locationId] }
                    val unitsForThisProduct = unitsMap[product.barcode] ?: emptyList()

                    // ✅ --- CORRECTED LOGIC ---
                    // Directly use caseQty from the Products sheet for the expected stock count.
                    val totalStockInUnits = product.caseQty.toDoubleOrNull() ?: 0.0
                    val countedQty = countedQuantitiesMap.getOrDefault(product.barcode, 0)
                    val variance = totalStockInUnits - countedQty
                    // ✅ --- END OF CORRECTION ---

                    // --- Determine if all its locations have been counted ---
                    val allLocationsCounted = if (locationsForThisProduct.isEmpty()) {
                        false // If no locations are assigned, it can't be "fully counted"
                    } else {
                        locationsForThisProduct.all { location ->
                            countedItemsSet.contains("${product.barcode}-${location.id}")
                        }
                    }

                    // --- The Filtering Decision ---
                    // We show the product if the filtering condition is MET.
                    // The condition to HIDE is (variance is 0 AND all locations are counted).
                    // So, we show if the opposite is true.
                    val shouldShowProduct = !(variance == 0.0 && allLocationsCounted)

                    if (shouldShowProduct) {
                        // If it should be shown, add it to the final lists
                        Log.d(logTag, "  -> ADDING product '${product.name}' (Variance: $variance, AllLocsCounted: $allLocationsCounted)")
                        names.add(product.name)
                        prices.add(product.unitCost)
                        images.add(product.imageUrl)
                        barcodes.add(product.barcode)
                        quantities.add(product.caseQty)
                        productLocationsList.add(locationsForThisProduct)
                        productUnitsList.add(unitsForThisProduct)
                    } else {
                        Log.d(logTag, "  -> SKIPPING product '${product.name}' (Variance: $variance, AllLocsCounted: $allLocationsCounted)")
                    }
                }
                Log.d(logTag, "Finished filtering. Passing ${names.size} products to the adapter.")


                // --- 6. Set Adapter on Main Thread ---
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Log.d(logTag, "--- Step 6: Setting Adapter on Main Thread ---")

                    val adapter = NotFoundAdapter(
                        account = account,
                        onItemClick = { position ->
                            val intent = Intent(requireContext(), InventoryItemDetails::class.java).apply {
                                putExtra("inventoryBarcodes", barcodes[position])
                            }
                            startActivity(intent)
                        },
                        onLocationBubbleClick = { location ->
                            Toast.makeText(
                                requireContext(),
                                "Location: A${location.aisle}-R${location.rack}-S${location.shelf}",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        productNameInventorys = names,
                        productPriceInventory = prices,
                        ProductImageUrl = images,
                        inventoryBarcodes = barcodes,
                        inventoryQuantitys = quantities,
                        inventoryLocations = productLocationsList,
                        inventoryUnits = productUnitsList,
                        context = requireContext(),
                        countedItems = countedItemsSet,
                        countedQuantities = countedQuantitiesMap // Pass the new map
                    )
                    binding.StockListRecyclerView.adapter = adapter
                    Log.d(logTag, "====== INVENTORY FETCH AND DISPLAY COMPLETE ======")
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Log.e("StockFragment", "Error during fetchInventoryData", e)
                    Toast.makeText(context, "Failed to fetch inventory: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun getDriveService(account: GoogleSignInAccount): Drive {
        val credential = GoogleAccountCredential.usingOAuth2(
            requireContext(),
            listOf(DriveScopes.DRIVE)
        ).apply { selectedAccountName = account.email }
        return Drive.Builder(
            GoogleNetHttpTransport.newTrustedTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        ).setApplicationName("Nia Bridge App").build()
    }

    private fun getSheetsService(account: GoogleSignInAccount): Sheets {
        val credential = GoogleAccountCredential.usingOAuth2(
            requireContext(),
            listOf(SheetsScopes.SPREADSHEETS)
        ).apply { selectedAccountName = account.email }
        return Sheets.Builder(
            GoogleNetHttpTransport.newTrustedTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        ).setApplicationName("Nia Bridge App").build()
    }

    private suspend fun findSheetIdByName(driveService: Drive, name: String): String? {
        val query = "name='$name' and mimeType='application/vnd.google-apps.spreadsheet' and trashed=false"
        val result = driveService.files().list()
            .setQ(query)
            .setSpaces("drive")
            .setCorpus("user")
            .setFields("files(id, owners, shared)")
            .execute()
        return result.files.firstOrNull { file ->
            (file.owners?.any { it.me == true } == true) || (file.shared == true)
        }?.id
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
