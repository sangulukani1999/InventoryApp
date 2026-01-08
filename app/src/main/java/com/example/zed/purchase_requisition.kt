package com.example.zed

import android.app.ProgressDialog
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.zed.databinding.ActivityPurchaseRequisitionBinding
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.SheetsScopes
import com.google.api.services.sheets.v4.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONArray
import org.json.JSONException
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class purchase_requisition : AppCompatActivity() {

    private val allProductsForSearch = mutableListOf<Product>()
    private lateinit var suggestionAdapter: androidx.cursoradapter.widget.CursorAdapter

    private lateinit var binding: ActivityPurchaseRequisitionBinding
    private lateinit var adapter: PurchaseRequisitionAdapter
    private val requisitionItems = mutableListOf<RequisitionItem>()

    private var isAdmin = false // Default to false until role is confirmed
    private lateinit var currentUserEmail: String
    private lateinit var googleAccount: GoogleSignInAccount

    private val REQUISITION_SHEET_NAME = "purchase_requisition_sheet"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPurchaseRequisitionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        setupSearchView()
        binding.barcodeScanner.setOnClickListener {
            val scannerDialog = BarcodeScannerDialogFragment { scannedBarcode ->
                findAndHighlightItem(scannedBarcode)
            }
            scannerDialog.show(supportFragmentManager, "PurchaseRequisitionScanner")
        }

        val account = GoogleSignIn.getLastSignedInAccount(this)
        if (account == null) {
            Toast.makeText(this, "User not signed in!", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val email = account.email
        if (email == null) {
            Toast.makeText(this, "Could not retrieve user email. Please sign in again.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        googleAccount = account
        currentUserEmail = email

        val progressDialog = ProgressDialog(this).apply {
            setMessage("Verifying user...")
            setCancelable(false)
            show()
        }

        checkUserRole(currentUserEmail) { isAdminResult, _, _ ->
            progressDialog.dismiss()
            this.isAdmin = isAdminResult
            Log.d("PurchaseRequisition", "Role check complete. User is admin: $isAdmin")

            setupRecyclerView()
            fetchProductsFromSheet()
        }
    }

    private fun setupSearchView() {
        suggestionAdapter = SuggestionAdapter(this, MatrixCursor(arrayOf("_id", "productName", "productBarcode")))
        binding.searchView.suggestionsAdapter = suggestionAdapter

        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            // ✅ MODIFIED: When user submits search, find and highlight the item.
            override fun onQueryTextSubmit(query: String?): Boolean {
                val firstMatch = allProductsForSearch.firstOrNull { it.name.contains(query ?: "", ignoreCase = true) }
                if (firstMatch != null) {
                    // Use the existing highlight function
                    findAndHighlightItem(firstMatch.barcode)
                } else {
                    Toast.makeText(this@purchase_requisition, "No product found for '$query'", Toast.LENGTH_SHORT).show()
                }
                binding.searchView.clearFocus()
                binding.searchView.setQuery("", false)
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                // This filters the suggestion dropdown as the user types
                updateSearchSuggestions(newText)
                return true
            }
        })

        binding.searchView.setOnSuggestionListener(object : SearchView.OnSuggestionListener {
            override fun onSuggestionSelect(position: Int): Boolean = true

            // ✅ MODIFIED: When user clicks a suggestion, find and highlight the item.
            override fun onSuggestionClick(position: Int): Boolean {
                (suggestionAdapter.getItem(position) as? Cursor)?.let {
                    val barcodeIndex = it.getColumnIndex("productBarcode")
                    if (barcodeIndex != -1) {
                        // Use the existing highlight function
                        findAndHighlightItem(it.getString(barcodeIndex))
                    }
                }
                binding.searchView.setQuery("", false)
                binding.searchView.clearFocus()
                return true
            }
        })
    }

    // ✅ REMOVED: This function is no longer needed as we are not navigating away.
    // private fun validateBarcodeAndNavigate(barcode: String) { ... }

    private fun updateSearchSuggestions(query: String?) {
        val newCursor = MatrixCursor(arrayOf("_id", "productName", "productBarcode"))
        if (!query.isNullOrBlank()) {
            allProductsForSearch.filter {
                it.name.contains(query, ignoreCase = true) || it.barcode.contains(query, ignoreCase = true)
            }.take(5).forEachIndexed { index, product ->
                newCursor.addRow(arrayOf(index, product.name, product.barcode))
            }
        }
        suggestionAdapter.changeCursor(newCursor)
    }


    private fun findAndHighlightItem(barcode: String) {
        val progressDialog = ProgressDialog(this).apply {
            setMessage("Finding item...")
            setCancelable(false)
            show()
        }

        lifecycleScope.launch {
            delay(50) // Keep the delay to ensure the dialog shows

            val itemIndex = requisitionItems.indexOfFirst { it.product.barcode == barcode }

            if (itemIndex != -1) {
                // If found:
                val previousHighlightedPosition = adapter.highlightedPosition
                adapter.highlightedPosition = itemIndex

                val layoutManager = binding.purchaseRequisitionRecyclerView.layoutManager as LinearLayoutManager
                // This instantly moves the view so the top of the item is at the top of the RecyclerView (0px offset).
                layoutManager.scrollToPositionWithOffset(itemIndex, 0)

                if (previousHighlightedPosition != -1) {
                    adapter.notifyItemChanged(previousHighlightedPosition)
                }
                adapter.notifyItemChanged(itemIndex)

            } else {
                Toast.makeText(this@purchase_requisition, "Product with barcode '$barcode' not found.", Toast.LENGTH_SHORT).show()
            }
            progressDialog.dismiss()
        }
    }

    private fun setupRecyclerView() {
        adapter = PurchaseRequisitionAdapter(
            requisitionItems,
            currentUserEmail,
            isAdmin,
            onItemChanged = { item ->
                writeRequisitionToSheet(item)
            },
            onTotalChanged = {
                updateTotalBudget()
            }
        )
        binding.purchaseRequisitionRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.purchaseRequisitionRecyclerView.adapter = adapter
    }

    private fun updateTotalBudget() {
        val total = adapter.calculateTotalBudgetedAmount()
        binding.totalBudgetValue.text = "K${"%.2f".format(total)}"
    }

    private fun fetchProductsFromSheet() {
        val progressDialog = ProgressDialog(this).apply {
            setMessage("Fetching products...")
            setCancelable(false)
            show()
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val sheetsService = getSheetsService(googleAccount)
                val spreadsheetId = findSheetIdByName(getDriveService(googleAccount), "nia-bridge data")
                    ?: throw Exception("Spreadsheet 'nia-bridge data' not found.")

                val productsRange = "Products!A2:K"
                val productsResponse = sheetsService.spreadsheets().values().get(spreadsheetId, productsRange).execute()
                val productValues = productsResponse.getValues()

                val productList = if (productValues.isNullOrEmpty()) {
                    emptyList()
                } else {
                    productValues.mapNotNull { row ->
                        if (row.getOrNull(3)?.toString().isNullOrBlank()) return@mapNotNull null
                        Product(
                            id = row.getOrNull(0)?.toString() ?: "",
                            name = row.getOrNull(1)?.toString() ?: "No Name",
                            imageUrl = row.getOrNull(2)?.toString(),
                            barcode = row.getOrNull(3)?.toString()!!,
                            categoryId = row.getOrNull(4)?.toString() ?: "",
                            unit = row.getOrNull(5)?.toString() ?: "",
                            caseQty = row.getOrNull(6)?.toString() ?: "0",
                            minOrder = row.getOrNull(7)?.toString() ?: "0",
                            unitCost = row.getOrNull(8)?.toString() ?: "0.00",
                            locationIds = row.getOrNull(9)?.toString()?.removeSurrounding("['", "']")?.split("', '")?.filter { it.isNotBlank() } ?: emptyList()
                        )
                    }
                }

                // Convert Product to RequisitionItem
                val items = productList.map {
                    RequisitionItem(
                        product = it,
                        uniqueSheetId = it.barcode
                    )
                }

                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    // Populate both lists
                    allProductsForSearch.clear()
                    allProductsForSearch.addAll(productList)
                    requisitionItems.clear()
                    requisitionItems.addAll(items)
                    adapter.notifyDataSetChanged()
                    updateTotalBudget()
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Log.e("FetchProducts", "Error fetching products from sheet", e)
                    Toast.makeText(this@purchase_requisition, "Failed to fetch products: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun writeRequisitionToSheet(item: RequisitionItem) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val sheetsService = getSheetsService(googleAccount)
                val spreadsheetId = findSheetIdByName(getDriveService(googleAccount), "nia-bridge data")
                    ?: throw Exception("Spreadsheet not found.")

                ensureRequisitionSheetExists(sheetsService, spreadsheetId)

                val searchRange = "$REQUISITION_SHEET_NAME!D:D" // Column D for Unique ID
                val searchResponse = sheetsService.spreadsheets().values().get(spreadsheetId, searchRange).execute()
                val existingRowIndex = searchResponse.getValues()?.flatten()?.indexOf(item.uniqueSheetId)?.let { if (it != -1) it + 2 else null }

                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

                if (existingRowIndex != null) {
                    val shouldBeActive = item.isChecked && item.quantity > 0
                    val updateValues = listOf(
                        listOf(
                            timestamp,
                            currentUserEmail,
                            item.product.name,
                            item.uniqueSheetId,
                            item.product.unit,
                            item.quantity,
                            item.product.unitCost,
                            if (shouldBeActive) "ACTIVE" else "REMOVED"
                        )
                    )
                    val valueRange = ValueRange().setValues(updateValues)
                    sheetsService.spreadsheets().values()
                        .update(spreadsheetId, "$REQUISITION_SHEET_NAME!A$existingRowIndex", valueRange)
                        .setValueInputOption("USER_ENTERED")
                        .execute()
                } else if (item.isChecked && item.quantity > 0) {
                    val newRow = listOf(
                        listOf(timestamp, currentUserEmail, item.product.name, item.uniqueSheetId, item.product.unit, item.quantity, item.product.unitCost, "ACTIVE")
                    )
                    val appendBody = ValueRange().setValues(newRow)
                    sheetsService.spreadsheets().values()
                        .append(spreadsheetId, "$REQUISITION_SHEET_NAME!A1", appendBody)
                        .setValueInputOption("USER_ENTERED")
                        .execute()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e("WriteRequisition", "Failed to write to sheet", e)
                }
            }
        }
    }

    private suspend fun ensureRequisitionSheetExists(sheetsService: Sheets, spreadsheetId: String) {
        val spreadsheet = sheetsService.spreadsheets().get(spreadsheetId).execute()
        if (spreadsheet.sheets.none { it.properties.title == REQUISITION_SHEET_NAME }) {
            val addSheetRequest = com.google.api.services.sheets.v4.model.Request()
                .setAddSheet(AddSheetRequest().setProperties(SheetProperties().setTitle(REQUISITION_SHEET_NAME)))
            sheetsService.spreadsheets().batchUpdate(spreadsheetId, BatchUpdateSpreadsheetRequest().setRequests(listOf(addSheetRequest))).execute()

            val headers = listOf(listOf("Timestamp", "User", "Product Name", "Unique ID", "Unit Description", "Quantity", "Unit Price", "Status"))
            val headerBody = ValueRange().setValues(headers)
            sheetsService.spreadsheets().values()
                .update(spreadsheetId, "$REQUISITION_SHEET_NAME!A1", headerBody)
                .setValueInputOption("USER_ENTERED")
                .execute()
        }
    }

    //<editor-fold desc="Role Check and Google API Helpers">
    private fun checkUserRole(email: String, callback: (isAdmin: Boolean, exists: Boolean, parentEmail: String?) -> Unit) {
        val client = OkHttpClient()
        val request = okhttp3.Request.Builder()
            .url("https://opensheet.elk.sh/1W-LOkSgPPrfhZ_kqfycUvOcGviQplMng6xpc6KBQ9Ik/users")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { callback(false, false, null) }
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                var isFound = false
                if (response.isSuccessful) {
                    try {
                        val jsonArray = JSONArray(response.body?.string() ?: "")
                        for (i in 0 until jsonArray.length()) {
                            val obj = jsonArray.getJSONObject(i)
                            val mainEmail = obj.optString("email").trim()
                            val subEmail = obj.optString("email sub user").trim()
                            val adminFlag = obj.optString("admin") == "1"
                            if (email.equals(mainEmail, ignoreCase = true)) {
                                runOnUiThread { callback(adminFlag, true, null) }
                                isFound = true; break
                            }
                            if (subEmail.isNotEmpty() && email.equals(subEmail, ignoreCase = true)) {
                                runOnUiThread { callback(false, true, mainEmail) }
                                isFound = true; break
                            }
                        }
                    } catch (e: JSONException) {
                        Log.e("checkUserRole", "Error parsing JSON from user sheet", e)
                        runOnUiThread { callback(false, false, null) }
                    }
                }
                if (!isFound) {
                    runOnUiThread { callback(false, false, null) }
                }
            }
        })
    }

    private suspend fun findSheetIdByName(driveService: Drive, name: String): String? = withContext(Dispatchers.IO) {
        driveService.files().list().setQ("name='$name' and mimeType='application/vnd.google-apps.spreadsheet' and trashed=false").setFields("files(id)").execute().files.firstOrNull()?.id
    }

    private fun getDriveService(account: GoogleSignInAccount): Drive {
        val credential = GoogleAccountCredential.usingOAuth2(this, listOf(DriveScopes.DRIVE)).setBackOff(com.google.api.client.util.ExponentialBackOff()).apply { selectedAccount = account.account }
        return Drive.Builder(GoogleNetHttpTransport.newTrustedTransport(), GsonFactory.getDefaultInstance(), credential).setApplicationName("Nia Bridge App").build()
    }

    private fun getSheetsService(account: GoogleSignInAccount): Sheets {
        val credential = GoogleAccountCredential.usingOAuth2(this, listOf(SheetsScopes.SPREADSHEETS)).setBackOff(com.google.api.client.util.ExponentialBackOff()).apply { selectedAccount = account.account }
        return Sheets.Builder(GoogleNetHttpTransport.newTrustedTransport(), GsonFactory.getDefaultInstance(), credential).setApplicationName("Nia Bridge App").build()
    }
    //</editor-fold>
}
