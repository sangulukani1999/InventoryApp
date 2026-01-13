package com.example.zed

import DetailedGoodsReceivedProduct
import android.app.ProgressDialog
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.cursoradapter.widget.SimpleCursorAdapter
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.math.BigDecimal
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

class detailed_goods_received_note : AppCompatActivity() {
    // Search-related properties
    private val allProductsForSearch = mutableListOf<DetailedGoodsReceivedProduct>()
    private lateinit var suggestionAdapter: SimpleCursorAdapter

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
        setupRecyclerView()
        setupClickListeners()
        setupSearchView() // Call the search setup

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
    }

    override fun onResume() {
        super.onResume()
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

                val rangesToFetch = listOf(
                    "purchased goods sheet!A:K",
                    "Products!A:M"
                )
                val batchGetData = sheetsService.spreadsheets().values()
                    .batchGet(spreadsheetId)
                    .setRanges(rangesToFetch)
                    .execute()

                val purchaseValueRange = batchGetData.valueRanges[0]
                val productValueRange = batchGetData.valueRanges[1]

                // Part 1: Update the "purchased goods sheet"
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

                // Part 2: Find the product row and prepare a batch update
                productValueRange.getValues()?.let { productValues ->
                    if (productValues.size > 1) {
                        for (i in 1 until productValues.size) {
                            val row = productValues[i] as? List<Any> ?: continue
                            val rowBarcode = if (row.size > 3) row[3]?.toString() else null

                            if (rowBarcode == item.barcode) {
                                val rowIndex = i + 1
                                val currentCaseUnits = (if (row.size > 6) row[6]?.toString() else "0")?.toIntOrNull() ?: 0
                                val newCaseUnits = currentCaseUnits + item.quantity

                                val productUpdateData = mutableListOf<ValueRange>()
                                productUpdateData.add(ValueRange().setRange("'Products'!G$rowIndex").setValues(listOf(listOf(newCaseUnits))))
                                productUpdateData.add(ValueRange().setRange("'Products'!I$rowIndex").setValues(listOf(listOf(item.unitCost))))
                                productUpdateData.add(ValueRange().setRange("'Products'!M$rowIndex").setValues(listOf(listOf(item.expiryDate))))

                                val productBatchUpdateRequest = BatchUpdateValuesRequest().setValueInputOption("USER_ENTERED").setData(productUpdateData)
                                sheetsService.spreadsheets().values().batchUpdate(spreadsheetId, productBatchUpdateRequest).execute()
                                break
                            }
                        }
                    }
                }

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
                    if(dialog.isShowing) dialog.dismiss()
                }
            }
        }
    }

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

                val purchasedItemsMap = mutableMapOf<String, String>()
                val receivedBarcodes = mutableSetOf<String>()
                val purchaseSheetName = "purchased goods sheet"
                var requisitionHasPurchasedItems = false

                try {
                    val purchaseSheetValues = sheets.spreadsheets().values()
                        .get(spreadsheetId, "$purchaseSheetName!A:K").execute()

                    purchaseSheetValues.getValues()?.drop(1)?.forEach { rowObject ->
                        val row = rowObject as? List<Any> ?: return@forEach
                        val reqCodeInSheet = if (row.size > 10) row[10]?.toString() else null
                        if (reqCodeInSheet == requisitionCode) {
                            requisitionHasPurchasedItems = true
                            val barcode = if (row.size > 0) row[0]?.toString() else null
                            val unitCost = if (row.size > 3) row[3]?.toString() else null
                            val expiryDate = if (row.size > 8) row[8]?.toString() else null

                            if (barcode != null && unitCost != null) {
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
                allProductsForSearch.clear()
                val targetCode = requisitionCode.trim()

                reqRows.forEach { rowObject ->
                    val req = rowObject as? List<Any> ?: return@forEach
                    val currentBarcode = normalize(req.getOrNull(0))
                    if (normalize(req.getOrNull(2)).equals(targetCode, ignoreCase = true)) {
                        productsMap[currentBarcode]?.let { productRowObject ->
                            val productRow = productRowObject as? List<Any> ?: return@let
                            val unitCostToUse = purchasedItemsMap[currentBarcode] ?: normalize(productRow.getOrNull(8))
                            val product = DetailedGoodsReceivedProduct(
                                barcode = currentBarcode,
                                name = normalize(productRow.getOrNull(1)),
                                unitCost = unitCostToUse,
                                quantity = normalize(req.getOrNull(4)).toIntOrNull() ?: 0,
                                imageUrl = convertDriveUrlToDirect(normalize(productRow.getOrNull(2))),
                                isPurchased = purchasedItemsMap.containsKey(currentBarcode),
                                isReceived = receivedBarcodes.contains(currentBarcode)
                            )
                            detailedItems.add(product)
                            allProductsForSearch.add(product)
                        }
                    }
                }

                if (detailedItems.isEmpty()) {
                    throw Exception("No items found for Requisition Code '$requisitionCode'. Check for typos.")
                }

                withContext(Dispatchers.Main) {
                    isRequisitionAlreadyPurchased = requisitionHasPurchasedItems
                    adapter.isPurchaseComplete = isRequisitionAlreadyPurchased
                    adapter.notifyDataSetChanged()
                    updateTotals()

                    if (isRequisitionAlreadyPurchased) {
                        binding.purchase.isEnabled = false
                        binding.purchase.cardElevation = 0f
                        binding.purchase.setCardBackgroundColor(ContextCompat.getColor(this@detailed_goods_received_note, R.color.variance_zero))
                        binding.ClearSelectedItems.isEnabled = false
                        binding.textView27.text = "Generate GRN"
                    } else {
                        binding.purchase.isEnabled = true
                        binding.purchase.cardElevation = 2f
                        binding.purchase.setCardBackgroundColor(ContextCompat.getColor(this@detailed_goods_received_note, R.color.selected_item_color))
                        binding.ClearSelectedItems.isEnabled = true
                        binding.textView27.text = "Requisition"
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

        // ✅ --- BARCODE SCANNER LOGIC ADDED HERE ---
        binding.barcodeScanner.setOnClickListener {
            val scannerDialog = BarcodeScannerDialogFragment { scannedBarcode ->
                findAndHighlightItem(scannedBarcode)
            }
            scannerDialog.show(supportFragmentManager, "DetailedGRNScanner")
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

                    val headerValues = listOf(listOf(
                        "Barcode", "Product Name", "Quantity", "Unit Cost", "Total Cost",
                        "Purchased By", "Received By", "Timestamp", "Expiry Date", "Expiry Timestamp",
                        "Requisition Code"
                    ))
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
                    if(dialog.isShowing) dialog.dismiss()
                }
            }
        }
    }

    private fun setupSearchView() {
        val from = arrayOf("productName")
        val to = intArrayOf(android.R.id.text1)
        suggestionAdapter = SimpleCursorAdapter(this, android.R.layout.simple_list_item_1, null, from, to, 0)
        binding.searchView.suggestionsAdapter = suggestionAdapter

        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                val firstMatch = allProductsForSearch.firstOrNull { it.name.contains(query ?: "", ignoreCase = true) }
                if (firstMatch != null) {
                    findAndHighlightItem(firstMatch.barcode)
                } else {
                    Toast.makeText(this@detailed_goods_received_note, "No product found for '$query'", Toast.LENGTH_SHORT).show()
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

    private fun findAndHighlightItem(barcode: String) {
        val progressDialog = ProgressDialog(this).apply {
            setMessage("Finding item..."); setCancelable(false); show()
        }

        lifecycleScope.launch {
            delay(50)
            val itemIndex = detailedItems.indexOfFirst { it.barcode == barcode }

            if (itemIndex != -1) {
                val layoutManager = binding.datailedGoodsReceivenNoteRecyclerView.layoutManager as LinearLayoutManager
                layoutManager.scrollToPositionWithOffset(itemIndex, 0)
                // You could add a temporary highlight effect here if needed
                adapter.notifyItemChanged(itemIndex, "HIGHLIGHT")
            } else {
                Toast.makeText(this@detailed_goods_received_note, "Product with barcode '$barcode' not found in this list.", Toast.LENGTH_SHORT).show()
            }
            progressDialog.dismiss()
        }
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

    private fun generatePdf(items: List<DetailedGoodsReceivedProduct>, requisitionCode: String) {
        val dialog = ProgressDialog(this).apply {
            setMessage("Generating PDF...")
            setCancelable(false)
            show()
        }

        lifecycleScope.launch(Dispatchers.IO) {
            var receivers = "N/A"
            val pdfTitle = if (isRequisitionAlreadyPurchased) "GOODS RECEIVED NOTE" else "PURCHASE REQUISITION"
            val fileName = "${pdfTitle.replace(" ", "_")}_$requisitionCode.pdf"

            try {
                if (isRequisitionAlreadyPurchased) {
                    val sheetsService = getSheetsService(googleAccount)
                    val spreadsheetId = findSheetIdByName(getDriveService(googleAccount), "nia-bridge data")
                        ?: throw IOException("Spreadsheet 'nia-bridge data' not found.")

                    val purchaseSheetName = "purchased goods sheet"
                    val range = "$purchaseSheetName!G:K"

                    val response = sheetsService.spreadsheets().values().get(spreadsheetId, range).execute()
                    val uniqueReceivers = response.getValues()?.drop(1)?.mapNotNull { row ->
                        val receivedBy = if (row.size > 0) row[0]?.toString() else null
                        val reqCodeInSheet = if (row.size > 4) row[4]?.toString() else null

                        if (reqCodeInSheet == requisitionCode && !receivedBy.isNullOrBlank()) {
                            receivedBy.trim()
                        } else {
                            null
                        }
                    }?.toSet()

                    if (!uniqueReceivers.isNullOrEmpty()) {
                        receivers = uniqueReceivers.joinToString(", ")
                    }
                }

                val document = PdfDocument()
                val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
                val page = document.startPage(pageInfo)
                val canvas: Canvas = page.canvas

                val titlePaint = Paint().apply {
                    color = Color.BLACK
                    textSize = 16f
                    isFakeBoldText = true
                    textAlign = Paint.Align.CENTER
                }
                val headerPaint = Paint().apply {
                    color = Color.BLACK
                    textSize = 8f
                    isFakeBoldText = true
                }
                val textPaint = Paint().apply {
                    color = Color.BLACK
                    textSize = 8f
                }

                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                val printedDate = dateFormat.format(Date())

                canvas.drawText(pdfTitle, (pageInfo.pageWidth / 2).toFloat(), 40f, titlePaint)
                canvas.drawText("Requisition Code: $requisitionCode", 40f, 60f, textPaint)
                canvas.drawText("Printed on: $printedDate", 40f, 70f, textPaint)

                if (isRequisitionAlreadyPurchased) {
                    canvas.drawText("Received By: $receivers", 40f, 80f, textPaint)
                }

                var yPos = 110f
                canvas.drawLine(38f, yPos - 12, pageInfo.pageWidth - 38f, yPos - 12, headerPaint)
                canvas.drawText("PRODUCT NAME", 40f, yPos, headerPaint)
                canvas.drawText("QTY", 380f, yPos, headerPaint)
                canvas.drawText("UNIT COST", 430f, yPos, headerPaint)
                canvas.drawText("TOTAL COST", 500f, yPos, headerPaint)
                canvas.drawLine(38f, yPos + 4, pageInfo.pageWidth - 38f, yPos + 4, headerPaint)
                yPos += 18

                var subtotal = BigDecimal.ZERO
                for (item in items) {
                    val totalCost = (item.unitCost.toBigDecimalOrNull() ?: BigDecimal.ZERO) * item.quantity.toBigDecimal()
                    subtotal += totalCost
                    val productName = item.name ?: "Unknown"
                    val textWidth = textPaint.measureText(productName)
                    if (textWidth > 320) {
                        var breakPoint = productName.length / 2
                        for(i in productName.length / 2 downTo 0){
                            if(productName[i] == ' '){
                                breakPoint = i
                                break
                            }
                        }
                        val line1 = productName.substring(0, breakPoint)
                        val line2 = productName.substring(breakPoint).trim()
                        canvas.drawText(line1, 40f, yPos, textPaint)
                        canvas.drawText(line2, 40f, yPos + 10, textPaint)
                        yPos += 10
                    } else {
                        canvas.drawText(productName, 40f, yPos, textPaint)
                    }

                    canvas.drawText(item.quantity.toString(), 380f, yPos, textPaint)
                    canvas.drawText("K${item.unitCost}", 430f, yPos, textPaint)
                    canvas.drawText("K${"%.2f".format(totalCost)}", 500f, yPos, textPaint)
                    yPos += 14
                }

                canvas.drawLine(380f, yPos, pageInfo.pageWidth - 38f, yPos, headerPaint)
                yPos += 14
                canvas.drawText("SUBTOTAL:", 430f, yPos, headerPaint)
                canvas.drawText("K${"%.2f".format(subtotal)}", 500f, yPos, textPaint)

                document.finishPage(page)

                withContext(Dispatchers.Main) {
                    savePdfToDownloads(document, fileName)
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e(TAG, "Error generating PDF", e)
                    Toast.makeText(this@detailed_goods_received_note, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    if (dialog.isShowing) dialog.dismiss()
                }
            }
        }
    }

    private fun savePdfToDownloads(document: PdfDocument, fileName: String) {
        val resolver = contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
        }

        var pdfUri: Uri? = null
        try {
            pdfUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (pdfUri == null) {
                throw IOException("Failed to create new MediaStore entry.")
            }
            resolver.openOutputStream(pdfUri)?.use { outputStream ->
                document.writeTo(outputStream)
            }
            document.close()
            Toast.makeText(this, "PDF saved to Downloads folder", Toast.LENGTH_LONG).show()
            openPdf(pdfUri)
        } catch (e: Exception) {
            Log.e("PDF", "Error saving PDF", e)
            Toast.makeText(this, "Error saving PDF: ${e.message}", Toast.LENGTH_LONG).show()
            pdfUri?.let { resolver.delete(it, null, null) }
            document.close()
        }
    }

    private fun openPdf(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(uri, "application/pdf")
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No application found to open PDF", Toast.LENGTH_SHORT).show()
        }
    }

    private fun normalize(value: Any?): String {
        return value?.toString()?.trim()?.removeSuffix(".0") ?: ""
    }

    private fun convertDriveUrlToDirect(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val fileId = Regex("""/d/([a-zA-Z0-9_-]+)|id=([a-zA-Z0-9_-]+)""").find(url)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
        return if (fileId != null) "https://drive.google.com/uc?export=download&id=$fileId" else url
    }

    private fun setupSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
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
