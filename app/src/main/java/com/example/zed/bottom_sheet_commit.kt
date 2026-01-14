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
import java.text.SimpleDateFormat
import java.util.*

// Data classes are still needed for this file's internal logic.
// Make sure these definitions match any defined in other files if needed.
data class CommitItem(
    val productName: String,
    val barcode: String,
    val imageUrl: String?,
    val variance: Double,
    val countedQty: Int,
    val unitCost: Double,
    val locations: List<Location>,
    val countedBy: String
)

private data class ProductCommitDetails(
    val productRowIndex: Int
)

// ✅ 1. CONSTRUCTOR IS UPDATED TO RECEIVE DATA
class bottom_sheet_commit(
    private val userEmail: String,
    private val parentEmail: String? = null,
    private val initialItems: List<CommitItem>, // Receives pre-fetched data
    private val onStockAdded: () -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetCommitBinding? = null
    private val binding get() = _binding!!
    private val logTag = "CommitSheet"

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetCommitBinding.inflate(inflater, container, false)
        Log.d(logTag, "onCreateView: View created.")
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(logTag, "onViewCreated: UI is ready.")

        binding.commitStockRecyclerView.layoutManager = LinearLayoutManager(requireContext())

        // ✅ 2. IMMEDIATELY DISPLAY THE DATA THAT WAS PASSED IN
        Log.d(logTag, "Displaying ${initialItems.size} pre-fetched items.")
        val adapter = CommitReviewAdapter(initialItems) // Assuming you have this adapter
        binding.commitStockRecyclerView.adapter = adapter

        binding.cardView4.setOnClickListener {
            Log.d(logTag, "Commit button clicked.")
            if (initialItems.isNotEmpty()) {
                Log.d(logTag, "Found ${initialItems.size} items to commit for audit trail.")
                commitCountDataToSheet(initialItems)
            } else {
                Log.d(logTag, "No counted items were found to commit.")
                Toast.makeText(requireContext(), "No items to commit.", Toast.LENGTH_SHORT).show()
                dismiss()
            }
        }
    }

    // ❗️ ALL DATA FETCHING LOGIC HAS BEEN REMOVED FROM THIS FILE ❗️
    // The `commitCountDataToSheet` function and its helpers remain.

    private fun commitCountDataToSheet(itemsToProcess: List<CommitItem>) {
        Log.d(logTag, "commitCountDataToSheet: Starting commit process for ${itemsToProcess.size} items.")
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

                val stockTakingSheetName = "stock_taking"
                val productsSheetName = "Products"
                val countDataSheetName = "countData"

                ensureSheetExists(sheetsService, spreadsheetId, stockTakingSheetName)

                val newRowsForStockTaking = mutableListOf<List<Any>>()
                val updateRequestsForProducts = mutableListOf<Request>()
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

                val itemsWithVariance = itemsToProcess.filter { it.variance != 0.0 }

                for (item in itemsToProcess) {
                    val totalCost = item.variance * item.unitCost
                    newRowsForStockTaking.add(
                        listOf(timestamp, item.barcode, item.productName, item.countedQty, item.variance, item.unitCost, totalCost, item.countedBy)
                    )
                }

                for (item in itemsWithVariance) {
                    val details = getProductDetailsForCommit(sheetsService, spreadsheetId, item.barcode)
                    val range = GridRange()
                        .setSheetId(getSheetId(sheetsService, spreadsheetId, productsSheetName))
                        .setStartRowIndex(details.productRowIndex - 1)
                        .setEndRowIndex(details.productRowIndex)
                        .setStartColumnIndex(6) // Column G for 'caseQty'
                        .setEndColumnIndex(7)

                    updateRequestsForProducts.add(
                        Request().setUpdateCells(
                            UpdateCellsRequest()
                                .setRange(range)
                                .setRows(listOf(RowData().setValues(listOf(CellData().setUserEnteredValue(ExtendedValue().setNumberValue(item.countedQty.toDouble()))))))
                                .setFields("userEnteredValue")
                        )
                    )
                }

                if (newRowsForStockTaking.isNotEmpty()) {
                    val appendBody = ValueRange().setValues(newRowsForStockTaking)
                    sheetsService.spreadsheets().values()
                        .append(spreadsheetId, "$stockTakingSheetName!A1", appendBody)
                        .setValueInputOption("USER_ENTERED")
                        .execute()
                }

                if (updateRequestsForProducts.isNotEmpty()) {
                    val batchUpdateRequest = BatchUpdateSpreadsheetRequest().setRequests(updateRequestsForProducts)
                    sheetsService.spreadsheets().batchUpdate(spreadsheetId, batchUpdateRequest).execute()
                }

                val clearRequest = ClearValuesRequest()
                val rangeToClear = "'$countDataSheetName'!A2:Z"
                sheetsService.spreadsheets().values()
                    .clear(spreadsheetId, rangeToClear, clearRequest)
                    .execute()

                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(requireContext(), "Commit successful!", Toast.LENGTH_LONG).show()
                    onStockAdded()
                    dismiss()
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Log.e(logTag, "Failed to commit stock", e)
                    Toast.makeText(requireContext(), "Error during commit: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    //<editor-fold desc="Helper Functions for Sheets API">
    private suspend fun ensureSheetExists(sheetsService: Sheets, spreadsheetId: String, sheetName: String) {
        val spreadsheet = sheetsService.spreadsheets().get(spreadsheetId).execute()
        if (spreadsheet.sheets.none { it.properties.title.equals(sheetName, ignoreCase = true) }) {
            val addSheetRequest = AddSheetRequest().setProperties(SheetProperties().setTitle(sheetName))
            sheetsService.spreadsheets().batchUpdate(spreadsheetId, BatchUpdateSpreadsheetRequest().setRequests(listOf(Request().setAddSheet(addSheetRequest)))).execute()

            val headers = listOf(listOf("Timestamp", "Barcode", "Product name", "Counted quantity", "Quantity variance", "Unit cost", "Total cost", "Counted By"))
            val headerBody = ValueRange().setValues(headers)
            sheetsService.spreadsheets().values()
                .update(spreadsheetId, "$sheetName!A1", headerBody)
                .setValueInputOption("USER_ENTERED")
                .execute()
        }
    }

    private suspend fun getProductDetailsForCommit(sheetsService: Sheets, spreadsheetId: String, barcode: String): ProductCommitDetails {
        val productsRange = "Products!D:D"
        val productsResponse = sheetsService.spreadsheets().values().get(spreadsheetId, productsRange).execute()
        val allProductRows = productsResponse.getValues()
            ?: throw IllegalStateException("The 'Products' sheet is empty or could not be read.")

        val rowIndex = allProductRows.indexOfFirst { row -> row.getOrNull(0)?.toString()?.trim() == barcode }
        if (rowIndex == -1) {
            throw IllegalStateException("Product with barcode '$barcode' not found in Products sheet for commit.")
        }
        val sheetRowNumber = rowIndex + 1
        Log.d(logTag, "Found product with barcode '$barcode' at sheet row: $sheetRowNumber")
        return ProductCommitDetails(productRowIndex = sheetRowNumber)
    }

    private fun getSheetId(sheetsService: Sheets, spreadsheetId: String, sheetName: String): Int? {
        val spreadsheet = sheetsService.spreadsheets().get(spreadsheetId).execute()
        return spreadsheet.sheets.firstOrNull { it.properties.title == sheetName }?.properties?.sheetId
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
