package com.example.zed

import android.app.DatePickerDialog
import android.app.ProgressDialog
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.zed.databinding.ActivityStockSummaryReportBinding
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
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class stockSummaryReport : AppCompatActivity() {

    private lateinit var binding: ActivityStockSummaryReportBinding
    private lateinit var varianceAdapter: StockVarianceAdapter
    private val allVarianceItems = mutableListOf<StockVarianceItem>()
    private lateinit var googleAccount: GoogleSignInAccount
    private val TAG = "StockVarianceReport"

    private enum class FilterType { ALL, TODAY, LAST_7_DAYS, CUSTOM }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityStockSummaryReportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        googleAccount = GoogleSignIn.getLastSignedInAccount(this)
            ?: run {
                Toast.makeText(this, "User not signed in.", Toast.LENGTH_LONG).show()
                finish()
                return
            }

        setupSystemBars()
        setupRecyclerView()
        setupClickListeners()
        fetchVarianceData()
    }

    private fun setupRecyclerView() {
        varianceAdapter = StockVarianceAdapter(emptyList())
        binding.stockSummaryReportRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@stockSummaryReport)
            adapter = varianceAdapter
        }
    }

    private fun setupClickListeners() {
        binding.backBtnPhysicalInventory.setOnClickListener { finish() }
        binding.reloadBtn.setOnClickListener { fetchVarianceData() }
        binding.AllBtn.setOnClickListener { applyFilter(FilterType.ALL) }
        binding.DepletedItem.setOnClickListener { applyFilter(FilterType.TODAY) }
        binding.MinimumOrder.setOnClickListener { applyFilter(FilterType.LAST_7_DAYS) }
        binding.custom.setOnClickListener { showDatePickerDialog() }
    }

    private fun fetchVarianceData() {
        val progressDialog = ProgressDialog(this).apply {
            setMessage("Fetching variance report...")
            setCancelable(false)
            show()
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val sheetsService = getSheetsService(googleAccount)
                val spreadsheetId = findSheetIdByName(getDriveService(googleAccount), "nia-bridge data")
                    ?: throw IOException("Spreadsheet 'nia-bridge data' not found.")

                // Fetching logic is correct
                val rangesToFetch = listOf("stock_taking!A:H", "Products!D:E")
                val batchGetData = sheetsService.spreadsheets().values()
                    .batchGet(spreadsheetId)
                    .setRanges(rangesToFetch)
                    .execute()

                val varianceValues = batchGetData.valueRanges[0].getValues()
                val productValues = batchGetData.valueRanges[1].getValues()

                val sellingPriceMap = productValues?.drop(1)?.associate { row ->
                    val barcode = row.getOrNull(0)?.toString()
                    val price = row.getOrNull(1)?.toString()?.toDoubleOrNull() ?: 0.0
                    barcode to price
                } ?: emptyMap()

                if (varianceValues.isNullOrEmpty() || varianceValues.size <= 1) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@stockSummaryReport, "No variance data found.", Toast.LENGTH_SHORT).show()
                        allVarianceItems.clear()
                        applyFilter(FilterType.ALL)
                    }
                    return@launch
                }

                allVarianceItems.clear()
                allVarianceItems.addAll(parseVarianceValues(varianceValues.drop(1), sellingPriceMap))

                withContext(Dispatchers.Main) {
                    applyFilter(FilterType.ALL)
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e(TAG, "Error fetching variance data: ", e)
                    Toast.makeText(this@stockSummaryReport, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    if (progressDialog.isShowing) progressDialog.dismiss()
                }
            }
        }
    }

    private fun parseVarianceValues(values: List<List<Any>>, priceMap: Map<String?, Double>): List<StockVarianceItem> {
        val items = mutableListOf<StockVarianceItem>()
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        for (row in values) {
            try {
                val barcode = row.getOrNull(1)?.toString()
                items.add(
                    StockVarianceItem(
                        timestamp = row.getOrNull(0)?.toString()?.let { sdf.parse(it) },
                        productName = row.getOrNull(2)?.toString() ?: "N/A",
                        countedQuantity = row.getOrNull(3)?.toString()?.toIntOrNull() ?: 0,
                        quantityVariance = row.getOrNull(4)?.toString()?.toIntOrNull() ?: 0,
                        unitCost = row.getOrNull(5)?.toString()?.toDoubleOrNull() ?: 0.0,
                        totalCost = row.getOrNull(6)?.toString()?.toDoubleOrNull() ?: 0.0, // This is Total Cost (Col G)
                        countedBy = row.getOrNull(7)?.toString() ?: "Unknown",
                        sellingPrice = priceMap[barcode] ?: 0.0
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse variance row: $row", e)
            }
        }
        // Sort ascending by date to easily find first and last dates
        return items.sortedBy { it.timestamp }
    }

    // ✅ UPDATED: The applyFilter function with new duration logic for "ALL"
    private fun applyFilter(filterType: FilterType, customDate: Date? = null) {
        val calendar = Calendar.getInstance()
        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        var durationText = ""

        val filteredList = when (filterType) {
            FilterType.ALL -> {
                updateActiveButton(binding.AllBtn)
                // Find the earliest and latest dates from all items
                val firstDate = allVarianceItems.firstOrNull()?.timestamp
                val lastDate = allVarianceItems.lastOrNull()?.timestamp
                durationText = if (firstDate != null && lastDate != null) {
                    "Duration: ${sdf.format(firstDate)} to ${sdf.format(lastDate)}"
                } else {
                    "Duration: All Dates"
                }
                allVarianceItems
            }
            FilterType.TODAY -> {
                updateActiveButton(binding.DepletedItem)
                val today = calendar.time
                durationText = "Duration: Today (${sdf.format(today)})"
                val startOfDay = getStartOfDay(today)
                allVarianceItems.filter {
                    it.timestamp?.let { ts ->
                        val itemCal = Calendar.getInstance().apply { time = ts }
                        val todayCal = Calendar.getInstance().apply { time = startOfDay }
                        itemCal.get(Calendar.DAY_OF_YEAR) == todayCal.get(Calendar.DAY_OF_YEAR) &&
                                itemCal.get(Calendar.YEAR) == todayCal.get(Calendar.YEAR)
                    } ?: false
                }
            }
            FilterType.LAST_7_DAYS -> {
                updateActiveButton(binding.MinimumOrder)
                val today = calendar.time
                calendar.add(Calendar.DAY_OF_YEAR, -6)
                val sevenDaysAgo = calendar.time
                durationText = "Duration: ${sdf.format(sevenDaysAgo)} - ${sdf.format(today)}"
                val startOfSevenDays = getStartOfDay(sevenDaysAgo)
                val endOfToday = getEndOfDay(today)
                allVarianceItems.filter { it.timestamp != null && it.timestamp >= startOfSevenDays && it.timestamp <= endOfToday }
            }
            FilterType.CUSTOM -> {
                updateActiveButton(binding.custom)
                if (customDate != null) {
                    durationText = "Duration: ${sdf.format(customDate)}"
                    val startOfDay = getStartOfDay(customDate)
                    val endOfDay = getEndOfDay(customDate)
                    allVarianceItems.filter { it.timestamp != null && it.timestamp >= startOfDay && it.timestamp <= endOfDay }
                } else {
                    durationText = "Duration: All Dates"
                    allVarianceItems
                }
            }
        }

        varianceAdapter.updateData(filteredList)
        updateSummary(filteredList, durationText)
    }

    // ✅ UPDATED: The summary function with the new calculation for Sales from Variance
    private fun updateSummary(filteredList: List<StockVarianceItem>, duration: String) {
        // This is now the sum of the "Total Cost" column for the filtered items
        val salesFromVariance = filteredList.sumOf { it.totalCost }
        val productsWithVarianceCount = filteredList.count { it.quantityVariance != 0 }

        binding.duration.text = duration
        binding.salesFromVariance.text = "Sales from variance: K${"%.2f".format(salesFromVariance)}"
        binding.productsNoVariance.text = "Products variance No. $productsWithVarianceCount"
    }

    // --- Helper Functions (DatePicker, SystemBars, Dates, Google API) ---

    private fun showDatePickerDialog() {
        val calendar = Calendar.getInstance()
        DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                val selectedCalendar = Calendar.getInstance()
                selectedCalendar.set(year, month, dayOfMonth)
                applyFilter(FilterType.CUSTOM, selectedCalendar.time)
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun getStartOfDay(date: Date): Date {
        return Calendar.getInstance().apply {
            time = date
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.time
    }

    private fun getEndOfDay(date: Date): Date {
        return Calendar.getInstance().apply {
            time = date
            set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
        }.time
    }

    private fun updateActiveButton(activeButton: CardView) {
        val activeColor = ContextCompat.getColor(this, R.color.active_filter_color)
        val inactiveColor = ContextCompat.getColor(this, R.color.inactive_filter_color)
        binding.AllBtn.setCardBackgroundColor(if (activeButton.id == R.id.AllBtn) activeColor else inactiveColor)
        binding.DepletedItem.setCardBackgroundColor(if (activeButton.id == R.id.DepletedItem) activeColor else inactiveColor)
        binding.MinimumOrder.setCardBackgroundColor(if (activeButton.id == R.id.MinimumOrder) activeColor else inactiveColor)
        binding.custom.setCardBackgroundColor(if (activeButton.id == R.id.custom) activeColor else inactiveColor)
    }

    private fun setupSystemBars() {
        val brandColor = Color.parseColor("#0071c1")
        window.statusBarColor = brandColor
        window.navigationBarColor = Color.parseColor("#0071c1") // Light navigation bar
        // Status bar background is dark, icons should be light
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        // Navigation bar background is light, icons should be dark
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = true
    }

    //<editor-fold desc="Google API Helpers">
    private suspend fun findSheetIdByName(driveService: Drive, name: String): String? = withContext(Dispatchers.IO) {
        driveService.files().list().setQ("name='$name' and mimeType='application/vnd.google-apps.spreadsheet' and trashed=false").setFields("files(id)").execute().files.firstOrNull()?.id
    }

    private fun getDriveService(account: GoogleSignInAccount): Drive {
        val credential = GoogleAccountCredential.usingOAuth2(this, listOf(DriveScopes.DRIVE_READONLY)).setBackOff(com.google.api.client.util.ExponentialBackOff()).apply { selectedAccount = account.account }
        return Drive.Builder(GoogleNetHttpTransport.newTrustedTransport(), GsonFactory.getDefaultInstance(), credential).setApplicationName("Nia Bridge App").build()
    }

    private fun getSheetsService(account: GoogleSignInAccount): Sheets {
        val credential = GoogleAccountCredential.usingOAuth2(this, listOf(SheetsScopes.SPREADSHEETS_READONLY)).setBackOff(com.google.api.client.util.ExponentialBackOff()).apply { selectedAccount = account.account }
        return Sheets.Builder(GoogleNetHttpTransport.newTrustedTransport(), GsonFactory.getDefaultInstance(), credential).setApplicationName("Nia Bridge App").build()
    }
    //</editor-fold>
}
