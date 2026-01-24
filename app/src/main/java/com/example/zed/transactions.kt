package com.example.zed

import android.app.Activity
import android.app.DatePickerDialog
import android.app.ProgressDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.zed.databinding.ActivityTransactionsBinding
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.SheetsScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.ArrayList

class transactions : AppCompatActivity() {

    private lateinit var binding: ActivityTransactionsBinding
    private lateinit var transactionsAdapter: TransactionsAdapter
    private val allTransactions = mutableListOf<Transaction>()
    private val TAG = "TransactionsActivity"

    private lateinit var googleAccount: GoogleSignInAccount

    // ✅ 1. ADD A CLASS VARIABLE TO HOLD TOTAL STOCK CAPITAL
    private var totalStockCapital: Double = 0.0

    companion object {
        private const val REQUEST_AUTHORIZATION = 1001
    }

    private enum class FilterType { ALL, TODAY, LAST_7_DAYS, CUSTOM }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityTransactionsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        googleAccount = GoogleSignIn.getLastSignedInAccount(this)
            ?: run {
                Toast.makeText(this, "User not signed in.", Toast.LENGTH_LONG).show()
                finish()
                return
            }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, 0, systemBars.right, 0)
            insets
        }

        setupSystemBars()
        setupRecyclerView()
        setupClickListeners()
        fetchTransactionsFromSheet()
    }

    private fun setupRecyclerView() {
        transactionsAdapter = TransactionsAdapter(ArrayList())
        binding.transactionsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@transactions)
            adapter = transactionsAdapter
        }
    }

    private fun setupClickListeners() {
        binding.backBtnPhysicalInventory.setOnClickListener {
            finish()
        }
        binding.reloadBtn.setOnClickListener {
            fetchTransactionsFromSheet()
        }
        
        // Pass null for the customDate parameter here
        binding.AllBtn.setOnClickListener { applyFilter(FilterType.ALL, null) }
        binding.DepletedItem.setOnClickListener { applyFilter(FilterType.TODAY, null) }
        binding.MinimumOrder.setOnClickListener { applyFilter(FilterType.LAST_7_DAYS, null) }
        binding.custom.setOnClickListener {
            showDatePickerDialog()
        }
    }

    private fun fetchTransactionsFromSheet() {
        val progressDialog = ProgressDialog(this).apply {
            setMessage("Fetching transactions...")
            setCancelable(false)
            show()
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val sheetsService = getSheetsService(googleAccount)
                val spreadsheetId = findSheetIdByName(getDriveService(googleAccount), "nia-bridge data")
                    ?: throw IOException("Spreadsheet 'nia-bridge data' not found.")

                // Fetch both Transactions and Products sheets
                val rangesToFetch = listOf("Transactions!A:P", "Products!D:I")
                val batchGetData = sheetsService.spreadsheets().values()
                    .batchGet(spreadsheetId)
                    .setRanges(rangesToFetch)
                    .execute()

                val transactionValues = batchGetData.valueRanges[0].getValues()
                val productValues = batchGetData.valueRanges[1].getValues()

                // Create a map of Barcode -> Cost Price
                val costPriceMap = productValues?.drop(1)?.associate { row ->
                    val barcode = row.getOrNull(0)?.toString()
                    val cost = row.getOrNull(5)?.toString()?.toDoubleOrNull() ?: 0.0
                    barcode to cost
                } ?: emptyMap()

                // ✅ --- THIS IS THE CORRECTED CALCULATION ---
                totalStockCapital = 0.0 // Reset before calculating
                productValues?.drop(1)?.forEach { productRow ->
                    // Our range is Products!D:I.
                    // Column D (Barcode) is index 0.
                    // Column G (Case Quantity) is index 3.
                    // Column I (Unit Cost) is index 5.
                    val caseQuantityStr = productRow.getOrNull(3)?.toString()?.trim() // Column G
                    val unitCostStr = productRow.getOrNull(5)?.toString()?.trim()     // Column I

                    if (!caseQuantityStr.isNullOrEmpty() && !unitCostStr.isNullOrEmpty()) {
                        val caseQuantity = caseQuantityStr.toDoubleOrNull() ?: 0.0
                        val unitCost = unitCostStr.toDoubleOrNull() ?: 0.0
                        totalStockCapital += unitCost * caseQuantity
                    }
                }
                // --- END OF CORRECTION ---

                if (transactionValues.isNullOrEmpty() || transactionValues.size <= 1) {
                    withContext(Dispatchers.Main) {
                        progressDialog.dismiss()
                        allTransactions.clear()
                        applyFilter(FilterType.ALL, null) // Apply filter even if no transactions
                        Toast.makeText(this@transactions, "No transactions found.", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val headers = transactionValues[0].map { it.toString().lowercase(Locale.ROOT).trim() }
                val parsedTransactions = parseFromSheetValues(transactionValues.drop(1), headers, costPriceMap)

                allTransactions.clear()
                allTransactions.addAll(parsedTransactions)

                withContext(Dispatchers.Main) {
                    // Apply default filter, summary will use the stored totalStockCapital
                    applyFilter(FilterType.ALL, null)
                }

            } catch (e: UserRecoverableAuthIOException) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Log.w(TAG, "Authorization is required. Launching consent screen.", e)
                    startActivityForResult(e.intent, REQUEST_AUTHORIZATION)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching transactions from sheet", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@transactions, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    if (progressDialog.isShowing) {
                        progressDialog.dismiss()
                    }
                }
            }
        }
    }

    // In transactions.kt

    private fun parseFromSheetValues(values: List<List<Any>>, headers: List<String>, costMap: Map<String?, Double>): List<Transaction> {
        val transactionList = mutableListOf<Transaction>()

        // Date format for string-based dates
        val stringDateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())

        // Function to convert Excel serial number to a Java Date
        fun excelSerialToDate(excelSerial: Double): Date {
            // Excel's epoch starts on 1900-01-01, but it has a leap year bug for 1900.
            // The serial number is the number of days since 1899-12-30.
            val calendar = Calendar.getInstance()
            calendar.set(1899, 11, 30, 0, 0, 0)
            calendar.add(Calendar.DATE, excelSerial.toInt())
            val fractionalPart = excelSerial - excelSerial.toInt()
            val millisecondsInDay = (fractionalPart * 24 * 60 * 60 * 1000).toLong()
            calendar.timeInMillis += millisecondsInDay
            return calendar.time
        }

        for (row in values) {
            if (row.size < 16) {
                Log.w(TAG, "Skipping malformed row with not enough columns: $row")
                continue
            }

            try {
                val transactionId = row.getOrNull(0)?.toString()
                val cartString = row.getOrNull(7)?.toString()
                val dateValue = row.getOrNull(8) // Get the raw date value
                val paymentMethod = row.getOrNull(9)?.toString() ?: "N/A"
                val totalAmountStr = row.getOrNull(11)?.toString()
                val user = row.getOrNull(15)?.toString() ?: "Unknown"

                if (transactionId.isNullOrBlank() || cartString.isNullOrBlank() || !cartString.startsWith("[")) {
                    continue
                }

                // ✅ --- THIS IS THE CORRECTED DATE PARSING LOGIC ---
                val timestamp: Date? = when (dateValue) {
                    is String -> {
                        try {
                            stringDateFormat.parse(dateValue)
                        } catch (e: Exception) {
                            // If parsing the string fails, try to convert it to a Double
                            dateValue.toDoubleOrNull()?.let { excelSerialToDate(it) }
                        }
                    }
                    is Number -> {
                        excelSerialToDate(dateValue.toDouble())
                    }
                    else -> null
                }
                // --- END OF CORRECTION ---

                val cartArray = JSONArray(cartString)
                val items = mutableListOf<TransactionItem>()
                for (j in 0 until cartArray.length()) {
                    val itemObj = cartArray.getJSONObject(j)
                    val barcode = itemObj.optString("id")
                    items.add(
                        TransactionItem(
                            id = barcode,
                            productName = itemObj.optString("product_name"),
                            price = itemObj.optDouble("price", 0.0),
                            quantity = itemObj.optString("quantity").toIntOrNull() ?: 0,
                            costPrice = costMap[barcode] ?: 0.0
                        )
                    )
                }

                if (items.isEmpty()) {
                    continue
                }

                transactionList.add(
                    Transaction(
                        transactionId = transactionId,
                        items = items,
                        timestamp = timestamp, // Use the new, correctly parsed timestamp
                        paymentMethod = paymentMethod,
                        totalAmount = totalAmountStr?.toDoubleOrNull() ?: 0.0,
                        user = user
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse transaction row: $row", e)
            }
        }
        Log.d(TAG, "Successfully parsed ${transactionList.size} transactions.")
        return transactionList.sortedByDescending { it.timestamp }
    }


    // ✅ 3. SIMPLIFY applyFilter
    private fun applyFilter(filterType: FilterType, customDate: Date?) {
        val calendar = Calendar.getInstance()
        val filteredList = when (filterType) {
            FilterType.ALL -> {
                updateActiveButton(binding.AllBtn)
                allTransactions
            }
            FilterType.TODAY -> {
                updateActiveButton(binding.DepletedItem)
                val startOfDay = getStartOfDay(calendar.time)
                allTransactions.filter {
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
                calendar.add(Calendar.DAY_OF_YEAR, -7)
                val sevenDaysAgo = getStartOfDay(calendar.time)
                allTransactions.filter { it.timestamp != null && it.timestamp >= sevenDaysAgo }
            }
            FilterType.CUSTOM -> {
                updateActiveButton(binding.custom)
                if (customDate != null) {
                    val startOfDay = getStartOfDay(customDate)
                    val endOfDay = getEndOfDay(customDate)
                    allTransactions.filter { it.timestamp != null && it.timestamp >= startOfDay && it.timestamp <= endOfDay }
                } else {
                    allTransactions
                }
            }
        }
        transactionsAdapter.updateData(filteredList)
        updateSummary(filteredList) // Just call updateSummary

        if (filterType == FilterType.TODAY && filteredList.isEmpty()) {
            Toast.makeText(this, "No transactions found for today.", Toast.LENGTH_SHORT).show()
        }
    }

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

    // ✅ 4. SIMPLIFY updateSummary
    private fun updateSummary(filteredList: List<Transaction>) {
        var totalSales = 0.0
        var totalProfit = 0.0

        // Sales and Profit are calculated from the filtered list of transactions
        for (transaction in filteredList) {
            totalSales += transaction.totalAmount
            for (item in transaction.items) {
                val itemProfit = (item.price - item.costPrice) * item.quantity
                totalProfit += itemProfit
            }
        }

        binding.cardView2.findViewById<TextView>(R.id.textView).text = "Sales: K${"%.2f".format(totalSales)}"
        binding.cardView2.findViewById<TextView>(R.id.textView26).text = "Profit: K${"%.2f".format(totalProfit)}"

        // Capital is now read from the class variable and is independent of the filter
        binding.cardView2.findViewById<TextView>(R.id.textView54).text = "Capital: K${"%.2f".format(totalStockCapital)}"
    }

    private fun updateActiveButton(activeButton: CardView) {
        val activeColor = ContextCompat.getColor(this, R.color.active_filter_color)
        val inactiveColor = ContextCompat.getColor(this, R.color.inactive_filter_color)
        binding.AllBtn.setCardBackgroundColor(if (activeButton.id == R.id.AllBtn) activeColor else inactiveColor)
        binding.DepletedItem.setCardBackgroundColor(if (activeButton.id == R.id.DepletedItem) activeColor else inactiveColor)
        binding.MinimumOrder.setCardBackgroundColor(if (activeButton.id == R.id.MinimumOrder) activeColor else inactiveColor)
        binding.custom.setCardBackgroundColor(if (activeButton.id == R.id.custom) activeColor else inactiveColor)
    }

    private fun getStartOfDay(date: Date): Date {
        return Calendar.getInstance().apply {
            time = date
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.time
    }

    private fun getEndOfDay(date: Date): Date {
        return Calendar.getInstance().apply {
            time = date
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }.time
    }

    private fun setupSystemBars() {
        val brandColor = Color.parseColor("#0071c1")
        window.statusBarColor = brandColor
        window.navigationBarColor = brandColor
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = false
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

    @Deprecated("This method has been deprecated in favor of using the Activity Result API which brings increased type safety via contracts and outcome received in separate callbacks.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_AUTHORIZATION) {
            if (resultCode == RESULT_OK) {
                Log.i(TAG, "Authorization granted by user. Retrying fetch.")
                fetchTransactionsFromSheet()
            } else {
                Log.w(TAG, "Authorization was denied by the user.")
                Toast.makeText(this, "Permission to access Google Sheets is required to view transactions.", Toast.LENGTH_LONG).show()
            }
        }
    }
}
