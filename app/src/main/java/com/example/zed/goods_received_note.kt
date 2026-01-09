package com.example.zed

import android.app.ProgressDialog
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class goods_received_note : AppCompatActivity() {

    private lateinit var binding: ActivityGoodsReceivedNoteBinding
    private lateinit var googleAccount: GoogleSignInAccount

    // Master list to hold all fetched items
    private val allGoodsReceivedItems = mutableListOf<GoodsReceivedItem>()

    // Enum for filter types
    private enum class FilterType { ALL, TODAY, LAST_7_DAYS, OTHERS }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGoodsReceivedNoteBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set system bar colors
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

        // Fetch data from the sheet
        fetchGoodsReceivedData()
    }

    private fun setupSystemBars() {
        // Top Status Bar: Blue background, White icons
        window.statusBarColor = ContextCompat.getColor(this, R.color.selected_item_color)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false

        // Bottom Navigation Bar: White background, Dark icons
        window.navigationBarColor = ContextCompat.getColor(this, R.color.white)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = true
    }

    private fun setupRecyclerView() {
        binding.goodsRecievedNoteRecyclerView.layoutManager = LinearLayoutManager(this)
        // Adapter will be set after data is fetched and filtered
    }

    private fun setupFilterButtons() {
        binding.AllBtn.setOnClickListener { applyFilter(FilterType.ALL) }
        binding.DepletedItem.setOnClickListener { applyFilter(FilterType.TODAY) } // Renamed to "Today"
        binding.MinimumOrder.setOnClickListener { applyFilter(FilterType.LAST_7_DAYS) } // "Last 7 days"
        binding.withinMonthExpirely.setOnClickListener { applyFilter(FilterType.OTHERS) } // "Others"

        // Set initial active state
        updateActiveButton(binding.AllBtn)
    }

    private fun fetchGoodsReceivedData() {
        val progressDialog = ProgressDialog(this).apply {
            setMessage("Fetching received goods...")
            setCancelable(false)
            show()
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val sheetsService = getSheetsService(googleAccount)
                val driveService = getDriveService(googleAccount)
                val spreadsheetId = findSheetIdByName(driveService, "nia-bridge data")
                    ?: throw Exception("Spreadsheet 'nia-bridge data' not found.")

                // We need to fetch the image URL from the Products sheet.
                // First, fetch all products to create a map of barcode -> imageUrl
                val productsResponse = sheetsService.spreadsheets().values().get(spreadsheetId, "Products!C2:D").execute()
                val productValues = productsResponse.getValues()
                val imageUrlMap = productValues?.associate { row ->
                    val barcode = row.getOrNull(1)?.toString()
                    val imageUrl = row.getOrNull(0)?.toString()
                    barcode to imageUrl
                } ?: emptyMap()

                val range = "purchase_requisition_sheet!A2:I" // Fetch all relevant columns
                val response = sheetsService.spreadsheets().values().get(spreadsheetId, range).execute()
                val values = response.getValues()

                if (values.isNullOrEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@goods_received_note, "No goods received data found.", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

                // Group rows by "Purchase Requisition code" (column C, index 2)
                val groupedData = values.groupBy { row ->
                    row.getOrNull(2)?.toString() ?: "UNKNOWN_CODE"
                }.map { (requisitionCode, rows) ->
                    val firstRow = rows.first()

                    // --- FIX IS HERE ---
                    val uniqueId = firstRow.getOrNull(0)?.toString() // This is often the barcode
                    val firstProductName = firstRow.getOrNull(1)?.toString() ?: "Unknown Product"
                    val imageUrl = imageUrlMap[uniqueId] // Look up the image URL from the map
                    val user = firstRow.getOrNull(7)?.toString() ?: "Unknown User"
                    val timestampStr = firstRow.getOrNull(8)?.toString()
                    val timestamp = try { timestampStr?.let { sdf.parse(it) } } catch (e: Exception) { null }
                    val totalValue = rows.sumOf { it.getOrNull(6)?.toString()?.toDoubleOrNull() ?: 0.0 }

                    GoodsReceivedItem(
                        requisitionCode = requisitionCode,
                        user = user,
                        timestamp = timestamp,
                        itemCount = rows.size,
                        totalValue = totalValue,
                        firstProductName = firstProductName, // Pass the product name
                        imageUrl = imageUrl                    // Pass the image URL
                    )
                    // --- END OF FIX ---

                }.sortedByDescending { it.timestamp } // Sort by most recent first

                allGoodsReceivedItems.clear()
                allGoodsReceivedItems.addAll(groupedData)

                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    // Apply the default "ALL" filter on initial load
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

        binding.goodsRecievedNoteRecyclerView.adapter = GoodsReceivedAdapter(filteredList)
    }

    private fun updateActiveButton(activeButton: CardView) {
        val activeColor = ContextCompat.getColor(this, R.color.active_filter_color)
        val inactiveColor = ContextCompat.getColor(this, R.color.inactive_filter_color)

        binding.AllBtn.setCardBackgroundColor(if (activeButton.id == R.id.AllBtn) activeColor else inactiveColor)
        binding.DepletedItem.setCardBackgroundColor(if (activeButton.id == R.id.DepletedItem) activeColor else inactiveColor)
        binding.MinimumOrder.setCardBackgroundColor(if (activeButton.id == R.id.MinimumOrder) activeColor else inactiveColor)
        binding.withinMonthExpirely.setCardBackgroundColor(if (activeButton.id == R.id.withinMonthExpirely) activeColor else inactiveColor)
    }

    //<editor-fold desc="Google API Helpers">
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
