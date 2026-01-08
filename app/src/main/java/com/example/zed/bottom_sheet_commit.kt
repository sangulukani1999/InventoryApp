package com.example.zed

import android.app.ProgressDialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.graphics.values
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.zed.databinding.BottomSheetCommitBinding
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
import com.google.api.services.sheets.v4.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.*

// Data class for items shown in the RecyclerView
data class CommitItem(
    val productName: String,
    val barcode: String,
    val imageUrl: String?,
    val variance: Double,
    val locations: List<Location>
)

// Data class to hold detailed info needed for the commit process
private data class ProductCommitDetails(
    val stockInUnits: Double,
    val costPrice: Double,
    val sellingPrice: Double,
    val highestUnitValue: Int,
    val productRowIndex: Int // Row number in the "Products" sheet
)

class bottom_sheet_commit(
    private val userEmail: String,
    private val parentEmail: String?,
    private val onStockAdded: () -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetCommitBinding? = null
    private val binding get() = _binding!!

    private val itemsToCommit = mutableListOf<CommitItem>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetCommitBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.commitStockRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        fetchInventoryData()

        binding.cardView4.setOnClickListener {
            // We only commit items that actually have a variance.
            val itemsWithVariance = itemsToCommit.filter { it.variance != 0.0 }
            if (itemsWithVariance.isNotEmpty()) {
                commitStockToSheet(itemsWithVariance)
            } else {
                Toast.makeText(requireContext(), "No items with variance to commit.", Toast.LENGTH_SHORT).show()
                dismiss()
            }
        }
    }

    private fun fetchInventoryData() {
        val progressDialog = ProgressDialog(requireContext()).apply {
            setMessage("Analyzing variances...")
            setCancelable(false)
            show()
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val account = GoogleSignIn.getLastSignedInAccount(requireContext())
                    ?: throw IllegalStateException("User is not signed in.")
                val sheetsService = getSheetsService(account)
                val driveService = getDriveService(account)
                val spreadsheetId = findSheetIdByName(driveService, "nia-bridge data")
                    ?: throw IllegalStateException("Spreadsheet 'nia-bridge data' not found.")

                val products = fetchProducts(sheetsService, spreadsheetId)
                val locationMap = fetchLocations(sheetsService, spreadsheetId)
                val unitsMap = fetchUnits(sheetsService, spreadsheetId)
                val countedQuantities = fetchCountedQuantities(sheetsService, spreadsheetId)

                val allReviewItems = mutableListOf<CommitItem>()
                itemsToCommit.clear()

                for (product in products) {
                    val unitsForThisProduct = unitsMap[product.barcode] ?: emptyList()
                    val stockInCases = product.caseQty.toDoubleOrNull() ?: 0.0

                    val totalStockInUnits = if (unitsForThisProduct.isNotEmpty()) {
                        val highestUnit = unitsForThisProduct.mapNotNull { it.caseUnits.toIntOrNull() }.maxOrNull() ?: 1
                        stockInCases * highestUnit
                    } else {
                        stockInCases
                    }

                    val countedQty = countedQuantities.getOrDefault(product.barcode, 0)
                    val variance = totalStockInUnits - countedQty.toDouble()

                    val commitItem = CommitItem(
                        productName = product.name,
                        barcode = product.barcode,
                        imageUrl = product.imageUrl,
                        variance = variance,
                        locations = product.locationIds.mapNotNull { locationId -> locationMap[locationId] }
                    )
                    allReviewItems.add(commitItem)
                    itemsToCommit.add(commitItem)
                }

                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    val adapter = CommitReviewAdapter(allReviewItems)
                    binding.commitStockRecyclerView.adapter = adapter
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Log.e("CommitSheet", "Error during fetchInventoryData", e)
                    Toast.makeText(context, "Error analyzing inventory: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun commitStockToSheet(itemsToCommit: List<CommitItem>) {
        val progressDialog = ProgressDialog(requireContext()).apply {
            setMessage("Committing stock data...")
            setCancelable(false)
            show()
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val account = GoogleSignIn.getLastSignedInAccount(requireContext()) ?: throw IllegalStateException("User not signed in.")
                val sheetsService = getSheetsService(account)
                val driveService = getDriveService(account)
                val spreadsheetId = findSheetIdByName(driveService, "nia-bridge data") ?: throw IllegalStateException("Spreadsheet not found.")

                val newSheetName = "stock_taking_sheet"
                val productsSheetName = "Products"

                ensureStockTakingSheetExists(sheetsService, spreadsheetId, newSheetName)

                val newRowsForStockTaking = mutableListOf<List<Any>>()
                val updateRequestsForProducts = mutableListOf<Request>()
                val currentDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

                for (item in itemsToCommit) {
                    val details = getProductDetailsForCommit(sheetsService, spreadsheetId, item.barcode)

                    // The new total quantity is the old stock MINUS the variance
                    val newTotalStock = (details.stockInUnits - item.variance).toInt()
                    val valueOfShop = details.costPrice * newTotalStock

                    newRowsForStockTaking.add(
                        listOf(currentDate, item.productName, details.costPrice, details.sellingPrice, newTotalStock, valueOfShop, userEmail)
                    )

                    val newCaseQty = if (details.highestUnitValue > 0) newTotalStock.toDouble() / details.highestUnitValue else 0.0
                    val range = GridRange()
                        .setSheetId(getSheetId(sheetsService, spreadsheetId, productsSheetName))
                        .setStartRowIndex(details.productRowIndex - 1)
                        .setEndRowIndex(details.productRowIndex)
                        .setStartColumnIndex(6)
                        .setEndColumnIndex(7)

                    updateRequestsForProducts.add(
                        Request().setUpdateCells(
                            UpdateCellsRequest()
                                .setRange(range)
                                .setRows(listOf(RowData().setValues(listOf(CellData().setUserEnteredValue(ExtendedValue().setNumberValue(newCaseQty))))))
                                .setFields("userEnteredValue")
                        )
                    )
                }

                if (newRowsForStockTaking.isNotEmpty()) {
                    val appendBody = ValueRange().setValues(newRowsForStockTaking)
                    sheetsService.spreadsheets().values()
                        .append(spreadsheetId, "$newSheetName!A1", appendBody)
                        .setValueInputOption("USER_ENTERED")
                        .execute()
                }

                if (updateRequestsForProducts.isNotEmpty()) {
                    val batchUpdateRequest = BatchUpdateSpreadsheetRequest().setRequests(updateRequestsForProducts)
                    sheetsService.spreadsheets().batchUpdate(spreadsheetId, batchUpdateRequest).execute()
                }

                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(requireContext(), "Commit successful!", Toast.LENGTH_LONG).show()
                    onStockAdded()
                    dismiss()
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Log.e("CommitStockError", "Failed to commit stock", e)
                    Toast.makeText(requireContext(), "Error during commit: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    //<editor-fold desc="Helper Functions for Sheets API">

    private suspend fun ensureStockTakingSheetExists(sheetsService: Sheets, spreadsheetId: String, sheetName: String) {
        val spreadsheet = sheetsService.spreadsheets().get(spreadsheetId).execute()
        val sheetExists = spreadsheet.sheets.any { it.properties.title.equals(sheetName, ignoreCase = true) }

        if (!sheetExists) {
            val addSheetRequest = AddSheetRequest().setProperties(SheetProperties().setTitle(sheetName))
            sheetsService.spreadsheets().batchUpdate(spreadsheetId, BatchUpdateSpreadsheetRequest().setRequests(listOf(Request().setAddSheet(addSheetRequest)))).execute()

            val headers = listOf(listOf("date", "product name", "cost price","Unit Of Measure" ,"(Unit Of Measure) Quantity","(Unit Of Measure) selling price", "stock taking units", "value of the shop", "generated by"))
            val headerBody = ValueRange().setValues(headers)
            sheetsService.spreadsheets().values()
                .update(spreadsheetId, "$sheetName!A1", headerBody)
                .setValueInputOption("USER_ENTERED")
                .execute()
        }
    }

    private suspend fun getProductDetailsForCommit(sheetsService: Sheets, spreadsheetId: String, barcode: String): ProductCommitDetails {
        val productsRange = "Products!A:I"
        val productsResponse = sheetsService.spreadsheets().values().get(spreadsheetId, productsRange).execute()
        val allProductRows = productsResponse.getValues()
        if (allProductRows.isNullOrEmpty()) {
            throw IllegalStateException("The 'Products' sheet is empty or could not be read.")
        }

        val headerAndDataRows = allProductRows.drop(1)
        val productRowIndexInFilteredList = headerAndDataRows.indexOfFirst { row ->
            (row as? List<*>)?.getOrNull(3)?.toString()?.trim() == barcode
        }

        if (productRowIndexInFilteredList == -1) {
            throw IllegalStateException("Product with barcode '$barcode' not found in Products sheet for commit.")
        }

        val productData = headerAndDataRows[productRowIndexInFilteredList]
        val stockInCases = productData.getOrNull(6)?.toString()?.toDoubleOrNull() ?: 0.0
        val costPrice = productData.getOrNull(8)?.toString()?.toDoubleOrNull() ?: 0.0

        val unitsRange = "unit_measure!A:D"
        val unitsResponse = sheetsService.spreadsheets().values().get(spreadsheetId, unitsRange).execute()
        val allUnitRows = unitsResponse.values
        val unitsForProduct = if (allUnitRows.isNullOrEmpty()) {
            emptyList()
        } else {
            allUnitRows.filter { row ->
                (row as? List<*>)?.getOrNull(0)?.toString()?.trim() == barcode
            }
        }

        val highestUnitValue = unitsForProduct.mapNotNull {
            val row = it as? List<Any>
            row?.getOrNull(3)?.toString()?.toIntOrNull()
        }.maxOrNull() ?: 1

        val sellingPrice = unitsForProduct.firstOrNull()?.let {
            (it as? List<*>)?.getOrNull(2)?.toString()?.toDoubleOrNull()
        } ?: 0.0

        val sheetRowNumber = productRowIndexInFilteredList + 2

        return ProductCommitDetails(
            stockInUnits = stockInCases * highestUnitValue,
            costPrice = costPrice,
            sellingPrice = sellingPrice,
            highestUnitValue = highestUnitValue,
            productRowIndex = sheetRowNumber
        )
    }

    private fun getSheetId(sheetsService: Sheets, spreadsheetId: String, sheetName: String): Int? {
        val spreadsheet = sheetsService.spreadsheets().get(spreadsheetId).execute()
        return spreadsheet.sheets.firstOrNull { it.properties.title == sheetName }?.properties?.sheetId
    }

    private suspend fun fetchProducts(sheetsService: Sheets, spreadsheetId: String): List<Product> {
        val productsRange = "Products!A2:K"
        val response = sheetsService.spreadsheets().values().get(spreadsheetId, productsRange).execute()
        val rawValues = response.values

        if (rawValues.isNullOrEmpty()) {
            Log.e("FetchProducts", "API returned no rows at all for range $productsRange.")
            return emptyList()
        }

        val productList = rawValues.flatMap { it as? List<List<Any>> ?: listOf(it) }.mapNotNull { potentialRow ->
            val row = potentialRow as? List<*> ?: return@mapNotNull null

            // ✅ --- FIX FOR BARCODE PARSING ---
            val barcodeValue = row.getOrNull(3)?.toString()?.trim()
            if (barcodeValue.isNullOrBlank()) {
                return@mapNotNull null
            }
            val barcode = try {
                BigDecimal(barcodeValue).toPlainString()
            } catch (e: NumberFormatException) {
                barcodeValue
            }

            val locationIdsString = row.getOrNull(9)?.toString() ?: ""
            val locationIdsList = if (locationIdsString.startsWith("['") && locationIdsString.endsWith("']")) {
                locationIdsString.removeSurrounding("['", "']").split("', '").filter { it.isNotBlank() }
            } else if (locationIdsString.startsWith("[") && locationIdsString.endsWith("]")) {
                locationIdsString.removeSurrounding("[", "]").split(",").map { it.trim() }.filter { it.isNotBlank() }
            } else {
                listOf(locationIdsString).filter { it.isNotBlank() }
            }

            Product(
                id = row.getOrNull(0)?.toString() ?: "",
                name = row.getOrNull(1)?.toString() ?: "",
                imageUrl = row.getOrNull(2)?.toString(),
                barcode = barcode, // Use cleaned barcode
                categoryId = row.getOrNull(4)?.toString() ?: "",
                unit = row.getOrNull(5)?.toString() ?: "",
                caseQty = row.getOrNull(6)?.toString() ?: "0",
                minOrder = row.getOrNull(7)?.toString() ?: "0",
                unitCost = row.getOrNull(8)?.toString() ?: "0.00",
                locationIds = locationIdsList,
                expiryDate = row.getOrNull(10)?.toString() ?: ""
            )
        }

        if (productList.isEmpty()) {
            Log.d("FetchProducts", "No products were parsed from the sheet after filtering.")
        } else {
            Log.d("FetchProducts", "Successfully parsed ${productList.size} products.")
        }

        return productList
    }

    private suspend fun fetchLocations(sheetsService: Sheets, spreadsheetId: String): Map<String, Location> {
        val locationsRange = "product_location!A2:D"
        val response = sheetsService.spreadsheets().values().get(spreadsheetId, locationsRange).execute()
        return response.values?.mapNotNull { rowList ->
            val row = rowList as? List<*>
            if (row != null && row.size >= 1) {
                val id = row.getOrNull(0)?.toString()
                if (id.isNullOrBlank()) return@mapNotNull null
                id to Location(
                    id = id,
                    aisle = row.getOrNull(1)?.toString() ?: "N/A",
                    rack = row.getOrNull(2)?.toString() ?: "N/A",
                    shelf = row.getOrNull(3)?.toString() ?: "N/A"
                )
            } else {
                null
            }
        }?.toMap() ?: emptyMap()
    }

    private suspend fun fetchUnits(sheetsService: Sheets, spreadsheetId: String): Map<String, List<UnitOfMeasure>> {
        val unitsRange = "unit_measure!A2:H"
        val response = sheetsService.spreadsheets().values().get(spreadsheetId, unitsRange).execute()
        val allUnitRows = response.values ?: return emptyMap()

        val allUnits = allUnitRows.mapNotNull { row ->
            val listRow = row as? List<*> ?: return@mapNotNull null

            // ✅ --- FIX FOR PRODUCT ID PARSING ---
            val productIdValue = listRow.getOrNull(0)?.toString()?.trim()
            if (productIdValue.isNullOrBlank()) {
                return@mapNotNull null
            }
            val productId = try {
                BigDecimal(productIdValue).toPlainString()
            } catch (e: NumberFormatException) {
                productIdValue
            }

            // ✅ --- ADDED LOGGING FOR COLUMN D ---
            val caseUnitsValue = listRow.getOrNull(3)?.toString() ?: "NULL or BLANK"
            Log.d("FetchUnitsDebug", "Processing row for productId: $productId. Raw caseUnits (Col D): '$caseUnitsValue'")


            UnitOfMeasure(
                productId = productId, // Use cleaned product ID
                unitBarcode = listRow.getOrNull(1)?.toString() ?: "",
                sellingPrice = listRow.getOrNull(2)?.toString() ?: "0.00",
                caseUnits = listRow.getOrNull(3)?.toString() ?: "1", // This correctly reads Column D
                quantityDescription = listRow.getOrNull(4)?.toString() ?: "Unit",
                cost = listRow.getOrNull(5)?.toString(),
                updatedBy = listRow.getOrNull(6)?.toString(),
                timestamp = listRow.getOrNull(7)?.toString()
            )
        }
        val unitsMap = allUnits.groupBy { it.productId!! }
        Log.d("FetchUnits", "Created unitsMap with keys: ${unitsMap.keys}")
        return unitsMap
    }

    private suspend fun fetchCountedQuantities(sheetsService: Sheets, spreadsheetId: String): Map<String, Int> {
        val map = mutableMapOf<String, Int>()
        val countDataRange = "countData!B2:E"
        val response = sheetsService.spreadsheets().values().get(spreadsheetId, countDataRange).execute()
        response.values?.forEach { rowList ->
            val row = rowList as? List<*> ?: return@forEach

            // ✅ --- FIX FOR BARCODE PARSING IN COUNT DATA ---
            val barcodeValue = row.getOrNull(0)?.toString()?.trim()
            if (barcodeValue.isNullOrBlank()) {
                return@forEach
            }
            val barcode = try {
                BigDecimal(barcodeValue).toPlainString()
            } catch (e: NumberFormatException) {
                barcodeValue
            }

            val quantity = row.getOrNull(3)?.toString()?.toIntOrNull() ?: 0
            map[barcode] = map.getOrDefault(barcode, 0) + quantity
        }
        return map
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
    //</editor-fold>

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "CommitBottomSheet"
    }
}
