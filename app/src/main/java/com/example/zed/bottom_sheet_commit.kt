package com.example.zed

import android.app.ProgressDialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.zed.databinding.BottomSheetCommitBinding // Use the binding for your layout
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.SheetsScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// This class holds the data for a single item in your RecyclerView.
data class CommitItem(
    val productName: String,
    val barcode: String,
    val imageUrl: String?,
    val variance: Double,
    val locations: List<Location>
)

// The constructor now correctly takes the user details, as you had it.
class bottom_sheet_commit(
    private val userEmail: String,
    private val parentEmail: String?,
    private val onStockAdded: () -> Unit
) : BottomSheetDialogFragment() {

    // Use the correct ViewBinding class for your layout
    private var _binding: BottomSheetCommitBinding? = null
    private val binding get() = _binding!!

    // This list will hold the items that have a non-zero variance, ready for the final commit action.
    private val itemsToCommit = mutableListOf<CommitItem>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Inflate the correct layout using its binding class
        _binding = BottomSheetCommitBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Setup the RecyclerView and the "COMMIT" button
        binding.commitStockRecyclerView.layoutManager = LinearLayoutManager(requireContext())

        // The main data fetching operation is triggered here
        fetchInventoryData()

        binding.cardView4.setOnClickListener {
            if (itemsToCommit.isNotEmpty()) {
                Toast.makeText(requireContext(), "Committing ${itemsToCommit.size} items with variance...", Toast.LENGTH_LONG).show()
                // You would call a function here to write 'itemsToCommit' back to Google Sheets.
            } else {
                Toast.makeText(requireContext(), "No items with variance to commit.", Toast.LENGTH_SHORT).show()
            }
            dismiss()
        }
    }

    // This function fetches all data and prepares it for the adapter
    private fun fetchInventoryData() {
        val progressDialog = ProgressDialog(requireContext()).apply {
            setMessage("Fetching inventory...")
            setCancelable(false)
            show()
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val logTag = "CommitSheetDataFlow"
                Log.d(logTag, "====== STARTING INVENTORY FETCH (Commit Sheet) ======")

                val account = GoogleSignIn.getLastSignedInAccount(requireContext())
                    ?: throw IllegalStateException("User is not signed in. Cannot fetch data.")

                val sheetsService = getSheetsService(account)
                val driveService = getDriveService(account)
                val spreadsheetId = findSheetIdByName(driveService, "nia-bridge data")
                    ?: throw IllegalStateException("Spreadsheet 'nia-bridge data' not found.")

                // --- 1. Fetching from Products sheet ---
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
                        locationIds = row.getOrNull(9)?.toString()?.removeSurrounding("['", "']")?.split("', '")?.filter { it.isNotBlank() } ?: emptyList()
                    )
                } ?: emptyList()

                // --- 2. Fetching from Locations sheet ---
                val locationsRange = "product_location!A2:D"
                val locationsResponse = sheetsService.spreadsheets().values().get(spreadsheetId, locationsRange).execute()
                val locationMap = locationsResponse.getValues()?.associate { row ->
                    val id = row.getOrNull(0)?.toString() ?: ""
                    id to Location(id = id, aisle = row.getOrNull(1)?.toString() ?: "N/A", rack = row.getOrNull(2)?.toString() ?: "N/A", shelf = row.getOrNull(3)?.toString() ?: "N/A")
                } ?: emptyMap()

                // --- 3. Fetching from Units sheet ---
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

                // --- 4. Fetch Count Data and Aggregating Totals ---
                val countedQuantitiesMap = mutableMapOf<String, Int>()
                val countDataRange = "countData!B2:E"
                val countDataResponse = sheetsService.spreadsheets().values().get(spreadsheetId, countDataRange).execute()
                countDataResponse.getValues()?.forEach { row ->
                    val barcode = row.getOrNull(0)?.toString()
                    val quantity = row.getOrNull(3)?.toString()?.toIntOrNull() ?: 0
                    if (!barcode.isNullOrBlank()) {
                        val currentTotal = countedQuantitiesMap.getOrDefault(barcode, 0)
                        countedQuantitiesMap[barcode] = currentTotal + quantity
                    }
                }

                // --- 5. Prepare data for Adapter (NO FILTERING) ---
                val reviewItems = mutableListOf<CommitItem>()
                itemsToCommit.clear() // Clear the list for the commit button

                for (product in productList) {
                    val locationsForThisProduct = product.locationIds.mapNotNull { locationId -> locationMap[locationId] }
                    val unitsForThisProduct = unitsMap[product.barcode] ?: emptyList()
                    val stockInCases = product.caseQty.toDoubleOrNull() ?: 0.0
                    val totalStockInUnits = if (unitsForThisProduct.isNotEmpty()) {
                        val highestUnit = unitsForThisProduct.mapNotNull { it.caseUnits.toIntOrNull() }.maxOrNull() ?: 1
                        stockInCases * highestUnit
                    } else {
                        stockInCases
                    }
                    val countedQty = countedQuantitiesMap.getOrDefault(product.barcode, 0)
                    val variance = totalStockInUnits - countedQty

                    val commitItem = CommitItem(
                        productName = product.name,
                        barcode = product.barcode,
                        imageUrl = product.imageUrl,
                        variance = variance,
                        locations = locationsForThisProduct
                    )
                    reviewItems.add(commitItem)

                    if (variance != 0.0) {
                        itemsToCommit.add(commitItem)
                    }
                }

                Log.d(logTag, "Final products for review: ${reviewItems.joinToString(separator = "\n")}")

                // --- 6. Set Adapter on Main Thread ---
                // ✅ THIS IS THE FIX: This block only runs AFTER all the data above is ready.
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    // Create the adapter with the NOW-POPULATED reviewItems list
                    val adapter = CommitReviewAdapter(reviewItems)
                    // Set the adapter on the RecyclerView
                    binding.commitStockRecyclerView.adapter = adapter
                    Log.d(logTag, "====== INVENTORY FETCH AND DISPLAY COMPLETE ======")
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Log.e("BottomSheetCommit", "Error during fetchInventoryData", e)
                    Toast.makeText(context, "Failed to fetch inventory: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun getDriveService(account: GoogleSignInAccount): Drive {
        val credential = GoogleAccountCredential.usingOAuth2(requireContext(), listOf(DriveScopes.DRIVE))
            .apply { selectedAccountName = account.email }
        return Drive.Builder(GoogleNetHttpTransport.newTrustedTransport(), GsonFactory.getDefaultInstance(), credential)
            .setApplicationName("Nia Bridge App").build()
    }

    private fun getSheetsService(account: GoogleSignInAccount): Sheets {
        val credential = GoogleAccountCredential.usingOAuth2(requireContext(), listOf(SheetsScopes.SPREADSHEETS))
            .apply { selectedAccountName = account.email }
        return Sheets.Builder(GoogleNetHttpTransport.newTrustedTransport(), GsonFactory.getDefaultInstance(), credential)
            .setApplicationName("Nia Bridge App").build()
    }

    private suspend fun findSheetIdByName(driveService: Drive, name: String): String? = withContext(Dispatchers.IO) {
        val query = "name='$name' and mimeType='application/vnd.google-apps.spreadsheet' and trashed=false"
        val result = driveService.files().list().setQ(query).setSpaces("drive").setCorpus("user")
            .setFields("files(id, owners, shared)").execute()
        return@withContext result.files.firstOrNull { file -> (file.owners?.any { it.me == true } == true) || (file.shared == true) }?.id
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "CommitBottomSheet"
    }
}
