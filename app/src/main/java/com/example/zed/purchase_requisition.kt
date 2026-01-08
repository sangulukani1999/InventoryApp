package com.example.zed

import android.app.ProgressDialog
import android.graphics.Color
import android.database.Cursor
import android.database.MatrixCursor
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.contains
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

    // Master list that never changes after being fetched
    private val requisitionItems = mutableListOf<RequisitionItem>()
    // Filtered list that is displayed in the RecyclerView
    private val filteredRequisitionItems = mutableListOf<RequisitionItem>()

    private var isAdmin = false
    private lateinit var currentUserEmail: String
    private lateinit var googleAccount: GoogleSignInAccount

    private val REQUISITION_SHEET_NAME = "purchase_requisition_sheet"

    private enum class FilterType {
        ALL, DEPLETED, MIN_ORDER, EXPIRING_SOON
    }

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
        setupFilterButtons() // Set up click listeners for filter buttons

        binding.barcodeScanner.setOnClickListener {
            val scannerDialog = BarcodeScannerDialogFragment { scannedBarcode ->
                findAndHighlightItem(scannedBarcode)
            }
            scannerDialog.show(supportFragmentManager, "PurchaseRequisitionScanner")
        }

        val account = GoogleSignIn.getLastSignedInAccount(this)
        if (account == null) {
            Toast.makeText(this, "User not signed in!", Toast.LENGTH_LONG).show()
            finish(); return
        }

        val email = account.email
        if (email == null) {
            Toast.makeText(this, "Could not retrieve user email.", Toast.LENGTH_LONG).show()
            finish(); return
        }

        googleAccount = account
        currentUserEmail = email

        val progressDialog = ProgressDialog(this).apply {
            setMessage("Verifying user..."); setCancelable(false); show()
        }

        checkUserRole(currentUserEmail) { isAdminResult, _, _ ->
            progressDialog.dismiss()
            this.isAdmin = isAdminResult
            Log.d("PurchaseRequisition", "Role check complete. User is admin: $isAdmin")
            setupRecyclerView()
            fetchProductsFromSheet()
        }
    }

    private fun setupFilterButtons() {
        binding.AllBtn.setOnClickListener { applyFilter(FilterType.ALL) }
        binding.DepletedItem.setOnClickListener { applyFilter(FilterType.DEPLETED) }
        binding.MinimumOrder.setOnClickListener { applyFilter(FilterType.MIN_ORDER) }
        binding.withinMonthExpirely.setOnClickListener { applyFilter(FilterType.EXPIRING_SOON) }

        // Set the initial active button
        updateActiveButton(binding.AllBtn)
    }

    private fun applyFilter(filterType: FilterType) {
        val filteredList = when (filterType) {
            FilterType.ALL -> {
                updateActiveButton(binding.AllBtn)
                requisitionItems
            }
            FilterType.DEPLETED -> {
                updateActiveButton(binding.DepletedItem)
                requisitionItems.filter { (it.product.caseQty.toDoubleOrNull() ?: 0.0) == 0.0 }
            }
            FilterType.MIN_ORDER -> {
                updateActiveButton(binding.MinimumOrder)
                requisitionItems.filter {
                    val caseQty = it.product.caseQty.toDoubleOrNull() ?: 0.0
                    val minOrder = it.product.minOrder.toDoubleOrNull() ?: 0.0
                    caseQty <= minOrder && minOrder > 0
                }
            }
            FilterType.EXPIRING_SOON -> {
                updateActiveButton(binding.withinMonthExpirely)
                val calendar = Calendar.getInstance()
                calendar.add(Calendar.MONTH, 1)
                val oneMonthFromNow = calendar.time

                requisitionItems.filter {
                    try {
                        val expiryDateStr = it.product.expiryDate
                        if (expiryDateStr.isNullOrBlank()) return@filter false

                        // Handle different date formats gracefully
                        val sdf = if (expiryDateStr.contains("-")) SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) else SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                        val expiryDate = sdf.parse(expiryDateStr)

                        expiryDate != null && expiryDate.before(oneMonthFromNow) && expiryDate.after(Date())
                    } catch (e: Exception) {
                        Log.e("Filter", "Could not parse date: ${it.product.expiryDate}", e)
                        false
                    }
                }
            }
        }

        filteredRequisitionItems.clear()
        filteredRequisitionItems.addAll(filteredList)
        adapter.notifyDataSetChanged()

        // Update total budget based on the newly filtered and visible items
        updateTotalBudget()
    }

    private fun updateActiveButton(activeButton: CardView) {
        val activeColor = ContextCompat.getColor(this, R.color.active_filter_color)
        val inactiveColor = ContextCompat.getColor(this, R.color.inactive_filter_color)

        binding.AllBtn.setCardBackgroundColor(if (activeButton.id == R.id.AllBtn) activeColor else inactiveColor)
        binding.DepletedItem.setCardBackgroundColor(if (activeButton.id == R.id.DepletedItem) activeColor else inactiveColor)
        binding.MinimumOrder.setCardBackgroundColor(if (activeButton.id == R.id.MinimumOrder) activeColor else inactiveColor)
        binding.withinMonthExpirely.setCardBackgroundColor(if (activeButton.id == R.id.withinMonthExpirely) activeColor else inactiveColor)
    }

    private fun setupSearchView() {
        suggestionAdapter = SuggestionAdapter(this, MatrixCursor(arrayOf("_id", "productName", "productBarcode")))
        binding.searchView.suggestionsAdapter = suggestionAdapter

        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                val firstMatch = allProductsForSearch.firstOrNull { it.name.contains(query ?: "", ignoreCase = true) }
                if (firstMatch != null) {
                    findAndHighlightItem(firstMatch.barcode)
                } else {
                    Toast.makeText(this@purchase_requisition, "No product found for '$query'", Toast.LENGTH_SHORT).show()
                }
                binding.searchView.clearFocus()
                binding.searchView.setQuery("", false)
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                updateSearchSuggestions(newText)
                return true
            }
        })

        binding.searchView.setOnSuggestionListener(object : SearchView.OnSuggestionListener {
            override fun onSuggestionSelect(position: Int): Boolean = true
            override fun onSuggestionClick(position: Int): Boolean {
                (suggestionAdapter.getItem(position) as? Cursor)?.let {
                    val barcodeIndex = it.getColumnIndex("productBarcode")
                    if (barcodeIndex != -1) {
                        findAndHighlightItem(it.getString(barcodeIndex))
                    }
                }
                binding.searchView.setQuery("", false)
                binding.searchView.clearFocus()
                return true
            }
        })
    }

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
            setMessage("Finding item..."); setCancelable(false); show()
        }

        lifecycleScope.launch {
            delay(50)

            // Ensure "All" filter is active to find the item in the full list
            if (binding.AllBtn.cardBackgroundColor.defaultColor != ContextCompat.getColor(this@purchase_requisition, R.color.active_filter_color)) {
                applyFilter(FilterType.ALL)
            }

            val itemIndex = filteredRequisitionItems.indexOfFirst { it.product.barcode == barcode }

            if (itemIndex != -1) {
                val previousHighlightedPosition = adapter.highlightedPosition
                adapter.highlightedPosition = itemIndex

                val layoutManager = binding.purchaseRequisitionRecyclerView.layoutManager as LinearLayoutManager
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
        // The adapter now points to the filtered list
        adapter = PurchaseRequisitionAdapter(
            filteredRequisitionItems, // Use the filtered list
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
            setMessage("Fetching products..."); setCancelable(false); show()
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val sheetsService = getSheetsService(googleAccount)
                val spreadsheetId = findSheetIdByName(getDriveService(googleAccount), "nia-bridge data")
                    ?: throw Exception("Spreadsheet 'nia-bridge data' not found.")

                // Make sure the "expiry date" header exists
                ensureExpiryDateHeaderExists(sheetsService, spreadsheetId)

                // Fetch up to column M
                val productsRange = "Products!A2:M"
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
                            locationIds = row.getOrNull(9)?.toString()?.removeSurrounding("['", "']")?.split("', '")?.filter { it.isNotBlank() } ?: emptyList(),
                            expiryDate = row.getOrNull(12)?.toString() // Column M is index 12
                        )
                    }
                }

                val items = productList.map {
                    RequisitionItem(product = it, uniqueSheetId = it.barcode)
                }

                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()

                    allProductsForSearch.clear()
                    allProductsForSearch.addAll(productList)

                    requisitionItems.clear()
                    requisitionItems.addAll(items)

                    // Apply the default "ALL" filter on initial load
                    applyFilter(FilterType.ALL)
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

    private suspend fun ensureExpiryDateHeaderExists(sheetsService: Sheets, spreadsheetId: String) {
        val range = "Products!M1"
        val response = sheetsService.spreadsheets().values().get(spreadsheetId, range).execute()
        if (response.getValues().isNullOrEmpty() || response.getValues()[0].isEmpty()) {
            val values = listOf(listOf("expiry date"))
            val body = ValueRange().setValues(values)
            sheetsService.spreadsheets().values()
                .update(spreadsheetId, range, body)
                .setValueInputOption("USER_ENTERED")
                .execute()
            Log.i("SheetSetup", "Added 'expiry date' header to column M.")
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
