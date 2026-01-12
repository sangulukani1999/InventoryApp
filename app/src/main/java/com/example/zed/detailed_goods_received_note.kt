package com.example.zed

import DetailedGoodsReceivedProduct
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.values
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.zed.databinding.ActivityDetailedGoodsReceivedNoteBinding
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.math.BigDecimal
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

class detailed_goods_received_note : AppCompatActivity() {

    private lateinit var binding: ActivityDetailedGoodsReceivedNoteBinding
    private lateinit var googleAccount: GoogleSignInAccount
    private val TAG = "DetailedGRN"
    private val detailedItems = mutableListOf<DetailedGoodsReceivedProduct>()
    private lateinit var adapter: DetailedGoodsReceivedAdapter
    private var isRequisitionAlreadyPurchased = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetailedGoodsReceivedNoteBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSystemBars()
        setupClickListeners()
        setupRecyclerView()

        googleAccount = GoogleSignIn.getLastSignedInAccount(this)
            ?: run {
                Toast.makeText(this, "User not signed in.", Toast.LENGTH_LONG).show()
                finish()
                return
            }

        val requisitionCode = intent.getStringExtra("REQUISITION_CODE")
            ?: run {
                Toast.makeText(this, "Error: Requisition Code was not provided.", Toast.LENGTH_LONG).show()
                finish()
                return
            }

        binding.textView51.text = requisitionCode
        // Data is now fetched in onResume to ensure it's always fresh
    }

    override fun onResume() {
        super.onResume()
        // Always refresh data when the screen becomes active.
        if (::googleAccount.isInitialized) {
            fetchDetailedData(binding.textView51.text.toString())
        }
    }

    private fun setupRecyclerView() {
        adapter = DetailedGoodsReceivedAdapter(
            detailedItems,
            onStateChanged = { updateTotals() },
            onConfirmReceive = { item, position ->
                updateGoodsReceivedStatus(item, position)
            }
        )
        binding.datailedGoodsReceivenNoteRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.datailedGoodsReceivenNoteRecyclerView.adapter = adapter
    }

    // In detailed_goods_received_note.kt

    // ✅ --- FINAL CORRECTED FUNCTION with Batch Update for Products Sheet ---
    // In detailed_goods_received_note.kt

    private fun updateGoodsReceivedStatus(item: DetailedGoodsReceivedProduct, position: Int) {
        val dialog = ProgressDialog(this).apply {
            setMessage("Confirming received goods...")
            setCancelable(false)
            show()
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val sheetsService = getSheetsService(googleAccount)
                val spreadsheetId = findSheetIdByName(getDriveService(googleAccount), "nia-bridge data")
                    ?: throw IOException("Spreadsheet 'nia-bridge data' not found.")

                val currentUserEmail = googleAccount.email ?: "Unknown"
                val requisitionCode = binding.textView51.text.toString()

                // --- Use batchGet to fetch both ranges in ONE API call ---
                val rangesToFetch = listOf(
                    "purchased goods sheet!A:K",
                    "Products!A:M" // Read up to column M to get all necessary data
                )
                val batchGetData = sheetsService.spreadsheets().values()
                    .batchGet(spreadsheetId)
                    .setRanges(rangesToFetch)
                    .execute()

                val purchaseValueRange = batchGetData.valueRanges[0]
                val productValueRange = batchGetData.valueRanges[1]

                // --- Part 1: Update the "purchased goods sheet" ---
                purchaseValueRange.getValues()?.let { purchaseValues ->
                    if (purchaseValues.size > 1) {
                        for (i in 1 until purchaseValues.size) {
                            val row = purchaseValues[i] as? List<Any> ?: continue
                            val rowBarcode = if (row.size > 0) row[0]?.toString() else null
                            val rowReqCode = if (row.size > 10) row[10]?.toString() else null

                            if (rowBarcode == item.barcode && rowReqCode == requisitionCode) {
                                val rowIndex = i + 1
                                val expiryTimestamp = try {
                                    item.expiryDate?.let { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).parse(it)?.time.toString() } ?: ""
                                } catch (e: Exception) { "" }

                                val updateData = listOf(
                                    ValueRange().setRange("'purchased goods sheet'!G$rowIndex").setValues(listOf(listOf(currentUserEmail))),
                                    ValueRange().setRange("'purchased goods sheet'!I$rowIndex").setValues(listOf(listOf(item.expiryDate))),
                                    ValueRange().setRange("'purchased goods sheet'!J$rowIndex").setValues(listOf(listOf(expiryTimestamp)))
                                )
                                val batchUpdateRequest = BatchUpdateValuesRequest().setValueInputOption("USER_ENTERED").setData(updateData)
                                sheetsService.spreadsheets().values().batchUpdate(spreadsheetId, batchUpdateRequest).execute()
                                break
                            }
                        }
                    }
                }

                // --- Part 2: Find the product row and prepare a batch update ---
                productValueRange.getValues()?.let { productValues ->
                    if (productValues.size > 1) {
                        for (i in 1 until productValues.size) {
                            val row = productValues[i] as? List<Any> ?: continue
                            val rowBarcode = if (row.size > 3) row[3]?.toString() else null

                            if (rowBarcode == item.barcode) {
                                val rowIndex = i + 1

                                // 1. ADD to case_units (Column G, index 6)
                                val currentCaseUnits = (if (row.size > 6) row[6]?.toString() else "0")?.toIntOrNull() ?: 0
                                val newCaseUnits = currentCaseUnits + item.quantity

                                val productUpdateData = mutableListOf<ValueRange>()

                                // Add the update for Column G (case_units)
                                productUpdateData.add(
                                    ValueRange().setRange("'Products'!G$rowIndex").setValues(listOf(listOf(newCaseUnits)))
                                )
                                // ✅ THIS IS THE LINE IN QUESTION - IT IS SYNTACTICALLY CORRECT
                                // Add the update for Column I (unit_cost)
                                productUpdateData.add(
                                    ValueRange().setRange("'Products'!I$rowIndex").setValues(listOf(listOf(item.unitCost)))
                                )
                                // Add the update for Column M (expiry_date)
                                productUpdateData.add(
                                    ValueRange().setRange("'Products'!M$rowIndex").setValues(listOf(listOf(item.expiryDate)))
                                )

                                val productBatchUpdateRequest = BatchUpdateValuesRequest().setValueInputOption("USER_ENTERED").setData(productUpdateData)
                                sheetsService.spreadsheets().values().batchUpdate(spreadsheetId, productBatchUpdateRequest).execute()
                                break
                            }
                        }
                    }
                }

                // --- Final UI update on success ---
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@detailed_goods_received_note, "Item confirmed successfully!", Toast.LENGTH_SHORT).show()
                    item.isReceived = true
                    adapter.notifyItemChanged(position)
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e(TAG, "Failed to update received status", e)
                    Toast.makeText(this@detailed_goods_received_note, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    dialog.dismiss()
                }
            }
        }
    }


    // In detailed_goods_received_note.kt

    // In detailed_goods_received_note.kt

    private fun fetchDetailedData(requisitionCode: String) {
        val dialog = ProgressDialog(this).apply {
            setMessage("Loading details...")
            setCancelable(false)
            show()
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val sheets = getSheetsService(googleAccount)
                val drive = getDriveService(googleAccount)
                val spreadsheetId = findSheetIdByName(drive, "nia-bridge data")
                    ?: throw Exception("Spreadsheet 'nia-bridge data' not found.")

                // ✅ --- NEW: Use a Map to store costs from the purchase sheet ---
                val purchasedItemsMap = mutableMapOf<String, String>() // Map<Barcode, UnitCost>
                val receivedBarcodes = mutableSetOf<String>()
                val purchaseSheetName = "purchased goods sheet"
                var requisitionHasPurchasedItems = false

                try {
                    val purchaseSheetValues = sheets.spreadsheets().values()
                        .get(spreadsheetId, "$purchaseSheetName!A:K").execute()

                    purchaseSheetValues.getValues()?.drop(1)?.forEach { rowObject ->
                        val row = rowObject as? List<Any> ?: return@forEach
                        val reqCodeInSheet = if (row.size > 10) row[10]?.toString() else null

                        // Only process rows for the current requisition
                        if (reqCodeInSheet == requisitionCode) {
                            requisitionHasPurchasedItems = true
                            val barcode = if (row.size > 0) row[0]?.toString() else null
                            val unitCost = if (row.size > 3) row[3]?.toString() else null // Unit Cost is in Column D (index 3)
                            val expiryDate = if (row.size > 8) row[8]?.toString() else null

                            if (barcode != null && unitCost != null) {
                                // Store the specific unit cost for this barcode and requisition
                                purchasedItemsMap[barcode] = unitCost

                                if (!expiryDate.isNullOrBlank()) {
                                    receivedBarcodes.add(barcode)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "'$purchaseSheetName' probably does not exist yet.")
                }

                val productsResponse = sheets.spreadsheets().values().get(spreadsheetId, "Products!A:M").execute()
                val productsRows = productsResponse.getValues()?.drop(1) ?: emptyList()
                val productsMap = productsRows.associateBy { rowObject ->
                    val row = rowObject as? List<Any>
                    normalize(row?.getOrNull(3))
                }

                val reqSheetName = "purchase_requisition_sheet"
                val lastRow = sheets.spreadsheets().get(spreadsheetId).setFields("sheets(properties(title,gridProperties(rowCount)))").execute()
                    .sheets?.firstOrNull { it.properties.title == reqSheetName }?.properties?.gridProperties?.rowCount ?: 1000
                val reqResponse = sheets.spreadsheets().values().get(spreadsheetId, "$reqSheetName!A1:I$lastRow").execute()
                val reqRows = reqResponse.getValues()?.drop(1) ?: emptyList()

                detailedItems.clear()
                val targetCode = requisitionCode.trim()

                reqRows.forEach { rowObject ->
                    val req = rowObject as? List<Any> ?: return@forEach
                    val currentBarcode = normalize(req.getOrNull(0))
                    if (normalize(req.getOrNull(2)).equals(targetCode, ignoreCase = true)) {
                        productsMap[currentBarcode]?.let { productRowObject ->
                            val productRow = productRowObject as? List<Any> ?: return@let

                            // ✅ --- NEW LOGIC: Determine the correct unit cost ---
                            // 1. Look for the cost in our map from the "purchased goods sheet".
                            // 2. If not found, fall back to the default cost from the "Products" sheet (Column I, index 8).
                            val unitCostToUse = purchasedItemsMap[currentBarcode] ?: normalize(productRow.getOrNull(8))

                            detailedItems.add(
                                DetailedGoodsReceivedProduct(
                                    barcode = currentBarcode,
                                    name = normalize(productRow.getOrNull(1)),
                                    unitCost = unitCostToUse, // Use the determined cost
                                    quantity = normalize(req.getOrNull(4)).toIntOrNull() ?: 0,
                                    imageUrl = convertDriveUrlToDirect(normalize(productRow.getOrNull(2))),
                                    isPurchased = purchasedItemsMap.containsKey(currentBarcode),
                                    isReceived = receivedBarcodes.contains(currentBarcode)
                                )
                            )
                        }
                    }
                }

                if (detailedItems.isEmpty()) {
                    throw Exception("No items found for Requisition Code '$requisitionCode'. Check for typos.")
                }

                // --- All data is valid, now update the UI safely ---
                withContext(Dispatchers.Main) {
                    isRequisitionAlreadyPurchased = requisitionHasPurchasedItems
                    adapter.isPurchaseComplete = isRequisitionAlreadyPurchased
                    adapter.notifyDataSetChanged()
                    updateTotals()

                    if (isRequisitionAlreadyPurchased) {
                        binding.purchase.isEnabled = false
                        binding.purchase.cardElevation = 0f
                        binding.purchase.setCardBackgroundColor(ContextCompat.getColor(this@detailed_goods_received_note, R.color.variance_zero))
                    } else {
                        binding.purchase.isEnabled = true
                        binding.purchase.cardElevation = 2f
                        binding.purchase.setCardBackgroundColor(ContextCompat.getColor(this@detailed_goods_received_note, R.color.nav_bar_color))
                    }
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e(TAG, "Failure during fetchDetailedData", e)
                    Toast.makeText(this@detailed_goods_received_note, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    binding.purchase.isEnabled = false
                    binding.purchase.cardElevation = 0f
                }
            } finally {
                withContext(Dispatchers.Main) {
                    if (dialog.isShowing) {
                        dialog.dismiss()
                    }
                }
            }
        }
    }

    // The rest of the file (setupClickListeners, handlePurchase, generatePdf, etc.) remains unchanged.
    // Omitted for brevity.
    private fun setupClickListeners() {
        binding.backBtnPhysicalInventory.setOnClickListener { finish() }

        binding.pdf.setOnClickListener {
            val checkedItems = detailedItems.filter { it.isChecked }
            if (checkedItems.isNotEmpty()) {
                generatePdf(checkedItems, binding.textView51.text.toString())
            } else {
                Toast.makeText(this, "No items selected to generate PDF.", Toast.LENGTH_SHORT).show()
            }
        }

        binding.ClearSelectedItems.setOnClickListener {
            detailedItems.forEach { it.isChecked = false }
            adapter.notifyDataSetChanged()
            updateTotals()
        }

        binding.purchase.setOnClickListener {
            val checkedItems = detailedItems.filter { it.isChecked }
            if (checkedItems.isEmpty()) {
                Toast.makeText(this, "No items selected to purchase.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            handlePurchase(checkedItems)
        }
    }

    private fun handlePurchase(purchasedItems: List<DetailedGoodsReceivedProduct>) {
        val dialog = ProgressDialog(this).apply {
            setMessage("Processing purchase...")
            setCancelable(false)
            show()
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val sheetsService = getSheetsService(googleAccount)
                val spreadsheetId = findSheetIdByName(getDriveService(googleAccount), "nia-bridge data")
                    ?: throw IOException("Spreadsheet 'nia-bridge data' not found.")

                val purchaseSheetName = "purchased goods sheet"

                var sheetExists = false
                val spreadsheet = sheetsService.spreadsheets().get(spreadsheetId).execute()
                for (sheet in spreadsheet.sheets) {
                    if (sheet.properties.title == purchaseSheetName) {
                        sheetExists = true
                        break
                    }
                }

                if (!sheetExists) {
                    val addSheetRequest = AddSheetRequest().setProperties(SheetProperties().setTitle(purchaseSheetName))
                    val batchUpdate = BatchUpdateSpreadsheetRequest().setRequests(listOf(Request().setAddSheet(addSheetRequest)))
                    sheetsService.spreadsheets().batchUpdate(spreadsheetId, batchUpdate).execute()

                    val headerValues = listOf(
                        listOf(
                            "Barcode", "Product Name", "Quantity", "Unit Cost", "Total Cost",
                            "Purchased By", "Received By", "Timestamp", "Expiry Date", "Expiry Timestamp",
                            "Requisition Code"
                        )
                    )
                    val headerBody = ValueRange().setValues(headerValues)
                    sheetsService.spreadsheets().values()
                        .update(spreadsheetId, "$purchaseSheetName!A1", headerBody)
                        .setValueInputOption("USER_ENTERED")
                        .execute()
                }

                val valuesToAppend = mutableListOf<List<Any>>()
                val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                val currentUserEmail = googleAccount.email ?: "Unknown"
                val requisitionCode = binding.textView51.text.toString()

                for (item in purchasedItems) {
                    val unitCost = item.unitCost.toBigDecimalOrNull() ?: BigDecimal.ZERO
                    val totalCost = unitCost * item.quantity.toBigDecimal()
                    val timestamp = timestampFormat.format(Date())

                    val row = listOf(
                        item.barcode, item.name, item.quantity, item.unitCost, totalCost.toPlainString(),
                        currentUserEmail, "", timestamp, item.expiryDate ?: "", "", requisitionCode
                    )
                    valuesToAppend.add(row)
                }

                val appendBody = ValueRange().setValues(valuesToAppend)
                sheetsService.spreadsheets().values()
                    .append(spreadsheetId, purchaseSheetName, appendBody)
                    .setValueInputOption("USER_ENTERED")
                    .setInsertDataOption("INSERT_ROWS")
                    .execute()

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@detailed_goods_received_note, "Purchase recorded successfully!", Toast.LENGTH_LONG).show()
                    fetchDetailedData(requisitionCode)
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e(TAG, "Failed to process purchase", e)
                    Toast.makeText(this@detailed_goods_received_note, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    dialog.dismiss()
                }
            }
        }
    }

    private fun updateTotals() {
        val checkedItems = detailedItems.filter { it.isChecked }
        val totalBudget = checkedItems.sumOf {
            (it.unitCost.toBigDecimalOrNull() ?: BigDecimal.ZERO) * it.quantity.toBigDecimal()
        }
        val currencyFormat = NumberFormat.getCurrencyInstance(Locale("en", "ZM"))
        binding.totalBudgetValue.text = currencyFormat.format(totalBudget)
        val deselectedCount = detailedItems.size - checkedItems.size
        binding.noItemsSelected.text = deselectedCount.toString()
    }

    private fun generatePdf(items: List<DetailedGoodsReceivedProduct>, requisitionCode: String) { /* Omitted for brevity */ }
    private fun savePdfToDownloads(document: PdfDocument, requisitionCode: String) { /* Omitted for brevity */ }
    private fun openPdf(uri: Uri) { /* Omitted for brevity */ }

    private fun normalize(value: Any?): String {
        return value?.toString()?.trim()?.removeSuffix(".0") ?: ""
    }

    private fun convertDriveUrlToDirect(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val fileId = Regex("""/d/([a-zA-Z0-9_-]+)|id=([a-zA-Z0-9_-]+)""").find(url)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
        return if (fileId != null) "https://drive.google.com/uc?export=download&id=$fileId" else url
    }

    private fun setupSystemBars() {
        //WindowCompat.setDecorFitsSystemWindows(window, false)
        //window.statusBarColor = ContextCompat.getColor(this, R.color.selected_item_color)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        //window.navigationBarColor = ContextCompat.getColor(this, R.color.white)
        //WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = true
    }

    private suspend fun findSheetIdByName(driveService: Drive, name: String): String? =
        withContext(Dispatchers.IO) {
            driveService.files().list()
                .setQ("name='$name' and mimeType='application/vnd.google-apps.spreadsheet' and trashed=false")
                .setFields("files(id)")
                .execute()
                .files
                .firstOrNull()
                ?.id
        }

    private fun getDriveService(account: GoogleSignInAccount): Drive {
        val credential = GoogleAccountCredential
            .usingOAuth2(this, listOf(DriveScopes.DRIVE))
            .apply { selectedAccount = account.account }
        return Drive.Builder(GoogleNetHttpTransport.newTrustedTransport(), GsonFactory.getDefaultInstance(), credential)
            .setApplicationName("Nia Bridge App").build()
    }

    private fun getSheetsService(account: GoogleSignInAccount): Sheets {
        val credential = GoogleAccountCredential
            .usingOAuth2(this, listOf(SheetsScopes.SPREADSHEETS))
            .apply { selectedAccount = account.account }
        return Sheets.Builder(GoogleNetHttpTransport.newTrustedTransport(), GsonFactory.getDefaultInstance(), credential)
            .setApplicationName("Nia Bridge App").build()
    }
}
