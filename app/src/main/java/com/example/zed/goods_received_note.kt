package com.example.zed

import android.app.ProgressDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.zed.databinding.ActivityGoodsReceivedNoteBinding
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.SheetsScopes
import com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetRequest
import com.google.api.services.sheets.v4.model.DeleteDimensionRequest
import com.google.api.services.sheets.v4.model.DimensionRange
import com.google.api.services.sheets.v4.model.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class goods_received_note : AppCompatActivity() {

    private lateinit var binding: ActivityGoodsReceivedNoteBinding
    private lateinit var googleAccount: GoogleSignInAccount
    private val allGoodsReceivedItems = mutableListOf<GoodsReceivedItem>()
    private var isAdminUser: Boolean = false // ✅ Flag to store user role

    private enum class FilterType { ALL, TODAY, LAST_7_DAYS, OTHERS }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        //binding.backBtnPhysicalInventory.setOnClickListener { finish() }
        binding = ActivityGoodsReceivedNoteBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSystemBars()

        val account = GoogleSignIn.getLastSignedInAccount(this)
        if (account == null) {
            Toast.makeText(this, "User not signed in!", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        googleAccount = account

        setupRecyclerView()
        setupFilterButtons()

        // First, check the user's role
        checkUserRole(googleAccount.email ?: "") { isAdmin, _, _ ->
            this.isAdminUser = isAdmin
            // After role is confirmed, fetch the data
            fetchGoodsReceivedData()
        }
    }

    private fun setupSystemBars() {
        window.statusBarColor = ContextCompat.getColor(this, R.color.selected_item_color)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        window.navigationBarColor = ContextCompat.getColor(this, R.color.white)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = true
    }

    private fun setupRecyclerView() {
        binding.goodsRecievedNoteRecyclerView.layoutManager = LinearLayoutManager(this)
    }

    private fun setupFilterButtons() {
        binding.AllBtn.setOnClickListener { applyFilter(FilterType.ALL) }
        binding.DepletedItem.setOnClickListener { applyFilter(FilterType.TODAY) }
        binding.MinimumOrder.setOnClickListener { applyFilter(FilterType.LAST_7_DAYS) }
        binding.withinMonthExpirely.setOnClickListener { applyFilter(FilterType.OTHERS) }
        updateActiveButton(binding.AllBtn)
    }

    // ✅ --- START: DELETION LOGIC ---
    private fun handleDeleteRequisition(requisitionCode: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete Requisition")
            .setMessage("Are you sure you want to permanently delete all items for requisition code '$requisitionCode'?")
            .setPositiveButton("Delete") { _, _ ->
                performDeleteOnSheet(requisitionCode)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performDeleteOnSheet(requisitionCode: String) {
        val progressDialog = ProgressDialog(this).apply {
            setMessage("Deleting requisition...")
            setCancelable(false)
            show()
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val sheetsService = getSheetsService(googleAccount)
                val spreadsheetId = findSheetIdByName(getDriveService(googleAccount), "nia-bridge data")
                    ?: throw Exception("Spreadsheet not found.")

                val sheetName = "purchase_requisition_sheet"
                val sheetId = getSheetIdByTitle(sheetsService, spreadsheetId, sheetName)
                    ?: throw Exception("Sheet '$sheetName' not found.")

                val range = "$sheetName!C:C"
                val response = sheetsService.spreadsheets().values().get(spreadsheetId, range).execute()
                val requests = mutableListOf<Request>()
                val rowsToDelete = response.getValues()
                    ?.mapIndexedNotNull { index, row ->
                        if (row.getOrNull(0)?.toString() == requisitionCode) index + 1 else null
                    }

                if (!rowsToDelete.isNullOrEmpty()) {
                    rowsToDelete.sortedDescending().forEach { rowIndex ->
                        val deleteRequest = DeleteDimensionRequest()
                            .setRange(
                                DimensionRange()
                                    .setSheetId(sheetId)
                                    .setDimension("ROWS")
                                    .setStartIndex(rowIndex -1) // Adjust for 0-based index in API request
                                    .setEndIndex(rowIndex)
                            )
                        requests.add(Request().setDeleteDimension(deleteRequest))
                    }
                    val batchUpdateRequest = BatchUpdateSpreadsheetRequest().setRequests(requests)
                    sheetsService.spreadsheets().batchUpdate(spreadsheetId, batchUpdateRequest).execute()
                }

                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(this@goods_received_note, "Requisition deleted.", Toast.LENGTH_SHORT).show()
                    fetchGoodsReceivedData()
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Log.e("DeleteRequisition", "Error deleting requisition", e)
                    Toast.makeText(this@goods_received_note, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun getSheetIdByTitle(sheetsService: Sheets, spreadsheetId: String, title: String): Int? {
        val spreadsheet = sheetsService.spreadsheets().get(spreadsheetId).setFields("sheets.properties").execute()
        return spreadsheet.sheets.firstOrNull { it.properties.title == title }?.properties?.sheetId
    }
    // ✅ --- END: DELETION LOGIC ---

    // In class goods_received_note

    private fun fetchGoodsReceivedData() {
        val progressDialog = ProgressDialog(this).apply {
            setMessage("Fetching goods...")
            setCancelable(false)
            show()
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val sheetsService = getSheetsService(googleAccount)
                val driveService = getDriveService(googleAccount)
                val spreadsheetId = findSheetIdByName(driveService, "nia-bridge data")
                    ?: throw Exception("Spreadsheet 'nia-bridge data' not found.")

                // ✅ START OF FIX: Ensure 'purchased goods sheet' exists before reading from it
                val purchaseSheetName = "purchased goods sheet"
                val spreadsheet = sheetsService.spreadsheets().get(spreadsheetId).setFields("sheets.properties").execute()
                val sheetExists = spreadsheet.sheets.any { it.properties.title == purchaseSheetName }

                if (!sheetExists) {
                    // If the sheet doesn't exist, create it with headers
                    val addSheetRequest = com.google.api.services.sheets.v4.model.AddSheetRequest()
                        .setProperties(com.google.api.services.sheets.v4.model.SheetProperties().setTitle(purchaseSheetName))
                    val batchUpdate = BatchUpdateSpreadsheetRequest().setRequests(listOf(Request().setAddSheet(addSheetRequest)))
                    sheetsService.spreadsheets().batchUpdate(spreadsheetId, batchUpdate).execute()

                    val headerValues = listOf(listOf(
                        "Barcode", "Product Name", "Quantity", "Unit Cost", "Total Cost",
                        "Purchased By", "Received By", "Timestamp", "Expiry Date", "Expiry Timestamp",
                        "Requisition Code"
                    ))
                    val headerBody = com.google.api.services.sheets.v4.model.ValueRange().setValues(headerValues)
                    sheetsService.spreadsheets().values()
                        .update(spreadsheetId, "$purchaseSheetName!A1", headerBody)
                        .setValueInputOption("USER_ENTERED")
                        .execute()
                    Log.d("FetchGRN", "'$purchaseSheetName' was created successfully.")
                }
                // ✅ END OF FIX

                // This logic remains correct from the previous step
                val productsResponse = sheetsService.spreadsheets().values().get(spreadsheetId, "Products!C2:D").execute()
                val imageUrlMap = productsResponse.getValues()?.associate { row -> row.getOrNull(1)?.toString() to row.getOrNull(0)?.toString() } ?: emptyMap()

                val requisitionRange = "purchase_requisition_sheet!A2:I"
                val requisitionResponse = sheetsService.spreadsheets().values().get(spreadsheetId, requisitionRange).execute()
                val requisitionValues = requisitionResponse.getValues()

                if (requisitionValues.isNullOrEmpty()) {
                    withContext(Dispatchers.Main) { Toast.makeText(this@goods_received_note, "No requisition data found.", Toast.LENGTH_SHORT).show() }
                    progressDialog.dismiss()
                    return@launch
                }

                val purchaseSheetRange = "purchased goods sheet!G2:K"
                val purchaseResponse = sheetsService.spreadsheets().values().get(spreadsheetId, purchaseSheetRange).execute()
                val purchaseData = purchaseResponse.getValues() ?: emptyList()
                val purchasedRequisitions = purchaseData.groupBy { it.getOrNull(4)?.toString() }

                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

                val groupedData = requisitionValues.groupBy { it.getOrNull(2)?.toString() ?: "UNKNOWN_CODE" }
                    .map { (requisitionCode, rows) ->
                        val purchasedItemsForThisCode = purchasedRequisitions[requisitionCode]
                        val status = if (purchasedItemsForThisCode == null) {
                            RequisitionStatusSangu.NOT_RECEIVED
                        } else {
                            val totalPurchasedItems = purchasedItemsForThisCode.size
                            val totalReceivedItems = purchasedItemsForThisCode.count { !it.getOrNull(0)?.toString().isNullOrBlank() }
                            when {
                                totalReceivedItems >= totalPurchasedItems && totalPurchasedItems > 0 -> RequisitionStatusSangu.FULLY_RECEIVED
                                else -> RequisitionStatusSangu.PARTIALLY_RECEIVED
                            }
                        }
                        val firstRow = rows.first()
                        GoodsReceivedItem(
                            requisitionCode = requisitionCode,
                            user = firstRow.getOrNull(7)?.toString() ?: "Unknown User",
                            timestamp = try { firstRow.getOrNull(8)?.toString()?.let { sdf.parse(it) } } catch (e: Exception) { null },
                            itemCount = rows.size,
                            totalValue = rows.sumOf { it.getOrNull(6)?.toString()?.toDoubleOrNull() ?: 0.0 },
                            firstProductName = firstRow.getOrNull(1)?.toString() ?: "Unknown Product",
                            imageUrl = imageUrlMap[firstRow.getOrNull(0)?.toString()],
                            status = status
                        )
                    }.sortedByDescending { it.timestamp }

                allGoodsReceivedItems.clear()
                allGoodsReceivedItems.addAll(groupedData)

                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    applyFilter(FilterType.ALL)
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Log.e("FetchGRN", "Error fetching data: ${e.message}", e)
                    Toast.makeText(this@goods_received_note, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }


    private fun applyFilter(filterType: FilterType) {
        val calendar = Calendar.getInstance()
        val filteredList = when (filterType) {
            FilterType.ALL -> {
                updateActiveButton(binding.AllBtn)
                allGoodsReceivedItems
            }
            FilterType.TODAY -> {
                updateActiveButton(binding.DepletedItem)
                val today = calendar.get(Calendar.DAY_OF_YEAR)
                val currentYear = calendar.get(Calendar.YEAR)
                allGoodsReceivedItems.filter {
                    it.timestamp?.let { date ->
                        val itemCal = Calendar.getInstance().apply { time = date }
                        itemCal.get(Calendar.DAY_OF_YEAR) == today && itemCal.get(Calendar.YEAR) == currentYear
                    } ?: false
                }
            }
            FilterType.LAST_7_DAYS -> {
                updateActiveButton(binding.MinimumOrder)
                calendar.add(Calendar.DAY_OF_YEAR, -7)
                val sevenDaysAgo = calendar.time
                allGoodsReceivedItems.filter { it.timestamp?.after(sevenDaysAgo) ?: false }
            }
            FilterType.OTHERS -> {
                updateActiveButton(binding.withinMonthExpirely)
                calendar.add(Calendar.DAY_OF_YEAR, -7)
                val sevenDaysAgo = calendar.time
                allGoodsReceivedItems.filter { it.timestamp?.before(sevenDaysAgo) ?: true }
            }
        }
        // ✅ Pass the isAdmin flag and the delete handler to the adapter
        binding.goodsRecievedNoteRecyclerView.adapter = GoodsReceivedAdapter(filteredList, isAdminUser) { requisitionCode ->
            handleDeleteRequisition(requisitionCode)
        }
    }

    private fun updateActiveButton(activeButton: CardView) {
        val activeColor = ContextCompat.getColor(this, R.color.active_filter_color)
        val inactiveColor = ContextCompat.getColor(this, R.color.inactive_filter_color)
        binding.AllBtn.setCardBackgroundColor(if (activeButton.id == R.id.AllBtn) activeColor else inactiveColor)
        binding.DepletedItem.setCardBackgroundColor(if (activeButton.id == R.id.DepletedItem) activeColor else inactiveColor)
        binding.MinimumOrder.setCardBackgroundColor(if (activeButton.id == R.id.MinimumOrder) activeColor else inactiveColor)
        binding.withinMonthExpirely.setCardBackgroundColor(if (activeButton.id == R.id.withinMonthExpirely) activeColor else inactiveColor)
    }

    //<editor-fold desc="Google API & Role Check Helpers">
    private fun checkUserRole(email: String, callback: (isAdmin: Boolean, exists: Boolean, parentEmail: String?) -> Unit) {
        val client = OkHttpClient()
        val request = okhttp3.Request.Builder()
            .url("https://opensheet.elk.sh/1W-LOkSgPPrfhZ_kqfycUvOcGviQplMng6xpc6KBQ9Ik/users")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { callback(false, false, null) }
            }

            override fun onResponse(call: Call, response: Response) {
                var isFound = false
                if (response.isSuccessful) {
                    try {
                        val jsonArray = JSONArray(response.body?.string() ?: "")
                        for (i in 0 until jsonArray.length()) {
                            val obj = jsonArray.getJSONObject(i)
                            if (email.equals(obj.optString("email").trim(), ignoreCase = true)) {
                                runOnUiThread { callback(obj.optString("admin") == "1", true, null) }
                                isFound = true; break
                            }
                        }
                    } catch (e: JSONException) {
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
