package com.example.zed

import android.app.ProgressDialog
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
// ✅ DELETED: The conflicting 'import androidx.core.graphics.values' is removed.
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class detailed_goods_received_note : AppCompatActivity() {

    private lateinit var binding: ActivityDetailedGoodsReceivedNoteBinding
    private lateinit var googleAccount: GoogleSignInAccount

    // Using a consistent TAG for logging
    private val TAG = "DetailedGRN"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetailedGoodsReceivedNoteBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSystemBars()
        binding.backBtnPhysicalInventory.setOnClickListener { finish() }

        googleAccount = GoogleSignIn.getLastSignedInAccount(this)
            ?: run {
                Toast.makeText(this, "User not signed in. Please sign in and try again.", Toast.LENGTH_LONG).show()
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

        setupRecyclerView()
        fetchDetailedData(requisitionCode)
    }

    // ---------------------- CORE LOGIC (FINAL, CORRECTED VERSION) ----------------------

    // Replace the entire function in your detailed_goods_received_note.kt
    private fun fetchDetailedData(requisitionCode: String) {

        val dialog = ProgressDialog(this).apply {
            setMessage("Loading details for $requisitionCode...")
            setCancelable(false)
            show()
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val sheets = getSheetsService(googleAccount)
                val drive = getDriveService(googleAccount)

                val spreadsheetId = findSheetIdByName(drive, "nia-bridge data")
                    ?: throw Exception("Spreadsheet 'nia-bridge data' not found.")

                // --- 1. Load products, using getValues() ---
                val productsResponse = sheets.spreadsheets().values()
                    .get(spreadsheetId, "Products!A:M")
                    .execute()
                val productsRows = productsResponse.getValues() // Using explicit getValues()
                    ?.drop(1) // Skips the header row
                    ?: emptyList()

                val productsMap = HashMap<String, List<Any>>()
                for (rowObject in productsRows) {
                    (rowObject as? List<Any>)?.let { list ->
                        val barcode = normalize(list.getOrNull(3))
                        if (barcode.isNotEmpty()) {
                            productsMap[barcode] = list
                        }
                    }
                }
                Log.d(TAG, "Loaded ${productsMap.size} products into the map for lookup.")

                // --- 2. Load requisitions, using getValues() ---
                val sheetName = "purchase_requisition_sheet"
                val sheetMetadata = sheets.spreadsheets().get(spreadsheetId)
                    .setFields("sheets(properties(title,gridProperties(rowCount)))")
                    .execute()

                val lastRow = sheetMetadata.sheets
                    ?.firstOrNull { it.properties.title == sheetName }
                    ?.properties?.gridProperties?.rowCount ?: 1000

                val preciseRange = "$sheetName!A1:I$lastRow"

                // ✅ CHANGED: Using the explicit getValues() method as you requested.
                val reqResponse = sheets.spreadsheets().values()
                    .get(spreadsheetId, preciseRange)
                    .execute()
                val reqRows = reqResponse.getValues() // Using explicit getValues()
                    ?.drop(1) // Skips the header row
                    ?: emptyList()


                Log.d(TAG, "Successfully fetched and are now processing ${reqRows.size} data rows.")

                // --- 3. Loop through requisitions and find matches ---
                val targetCode = requisitionCode.trim()
                val detailedItems = mutableListOf<DetailedGoodsReceivedProduct>()

                for ((index, rowObject) in reqRows.withIndex()) {
                    (rowObject as? List<Any>)?.let { req ->
                        val codeFromSheet = normalize(req.getOrNull(2))
                        val uniqueBarcode = normalize(req.getOrNull(0))

                        Log.d(TAG, "Row ${index + 2}: Comparing Sheet Code ['${codeFromSheet}'] with Target Code ['${targetCode}']. Barcode is ['${uniqueBarcode}'].")

                        if (codeFromSheet.equals(targetCode, ignoreCase = true)) {
                            Log.d(TAG, "   ✅ REQ CODE MATCH! Proceeding with barcode lookup.")

                            val quantity = normalize(req.getOrNull(4)).toIntOrNull() ?: 0
                            val productRow = productsMap[uniqueBarcode]

                            if (productRow != null) {
                                val name = normalize(productRow.getOrNull(1))
                                detailedItems.add(
                                    DetailedGoodsReceivedProduct(
                                        name = name,
                                        unitCost = normalize(productRow.getOrNull(8)),
                                        quantity = quantity,
                                        imageUrl = convertDriveUrlToDirect(normalize(productRow.getOrNull(2)))
                                    )
                                )
                                Log.d(TAG, "      ✅ PRODUCT FOUND! Added '$name' to the list.")
                            } else {
                                Log.e(TAG, "      ❌ PRODUCT NOT FOUND! The barcode ['$uniqueBarcode'] was not found in the Products sheet map.")
                            }
                        }
                    }
                }

                // --- 4. Final check and UI update ---
                if (detailedItems.isEmpty()) {
                    throw Exception("No valid items found for Requisition Code '$requisitionCode'. Check logs for mismatches.")
                }

                withContext(Dispatchers.Main) {
                    binding.datailedGoodsReceivenNoteRecyclerView.adapter = DetailedGoodsReceivedAdapter(detailedItems)
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e(TAG, "A failure occurred during fetchDetailedData", e)
                    Toast.makeText(this@detailed_goods_received_note, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    dialog.dismiss()
                }
            }
        }
    }

    // ---------------------- HELPERS ----------------------

    private fun normalize(value: Any?): String {
        return value?.toString()
            ?.trim()
            ?.removeSuffix(".0")
            ?: ""
    }

    private fun convertDriveUrlToDirect(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val fileId = Regex("""/d/([a-zA-Z0-9_-]+)|id=([a-zA-Z0-9_-]+)""").find(url)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
        return if (fileId != null) "https://drive.google.com/uc?export=download&id=$fileId" else url
    }

    private fun setupRecyclerView() {
        binding.datailedGoodsReceivenNoteRecyclerView.layoutManager = LinearLayoutManager(this)
    }

    private fun setupSystemBars() {
        //WindowCompat.setDecorFitsSystemWindows(window, false)
        //window.statusBarColor = ContextCompat.getColor(this, R.color.selected_item_color)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
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

        return Drive.Builder(
            GoogleNetHttpTransport.newTrustedTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        ).setApplicationName("Nia Bridge App").build()
    }

    private fun getSheetsService(account: GoogleSignInAccount): Sheets {
        val credential = GoogleAccountCredential
            .usingOAuth2(this, listOf(SheetsScopes.SPREADSHEETS))
            .apply { selectedAccount = account.account }

        return Sheets.Builder(
            GoogleNetHttpTransport.newTrustedTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        ).setApplicationName("Nia Bridge App").build()
    }
}
