package com.example.zed

import android.app.DatePickerDialog
import android.app.ProgressDialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.zed.databinding.FragmentBalancingBinding
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
import org.json.JSONArray
import java.io.IOException
import java.text.NumberFormat
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.*

//region Data Classes for Balancing Fragment
data class SheetRow(
    val timestamp: Date?,
    val value: Double
)

data class ProfitTransaction(
    val items: List<ProfitTransactionItem>,
    val timestamp: Date?
)

data class ProfitTransactionItem(
    val price: Double,
    val quantity: Int,
    val costPrice: Double
)

// Reverted: Removed variances as per your instruction
data class BalancingData(
    val sales: List<SheetRow>,
    val closingBalances: List<SheetRow>,
    val investments: List<SheetRow>,
    val expenses: List<SheetRow>,
    val inventoryCost: Double,
    val profitTransactions: List<ProfitTransaction>
)
//endregion

class balancing : Fragment() {

    private var _binding: FragmentBalancingBinding? = null
    private val binding get() = _binding!!

    private lateinit var googleAccount: GoogleSignInAccount
    private val TAG = "BalancingFragment"

    private var fullBalancingData: BalancingData? = null

    private enum class DateFilter { TODAY, YESTERDAY, LAST_7_DAYS, THIS_MONTH, CUSTOM }

    private val dateFormats = listOf(
        SimpleDateFormat("d/M/yyyy, h:mm:ss a", Locale.getDefault()),
        SimpleDateFormat("dd/MM/yyyy, HH:mm:ss", Locale.getDefault()),
        SimpleDateFormat("d/M/yyyy, HH:mm:ss", Locale.getDefault()),
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    )

    private fun parseDateString(dateStr: String?): Date? {
        if (dateStr.isNullOrBlank()) return null
        for (format in dateFormats) {
            try {
                return format.parse(dateStr)
            } catch (e: ParseException) {
                // Try next format
            }
        }
        Log.e(TAG, "Unparseable date after trying all formats: '$dateStr'")
        return null
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBalancingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val account = GoogleSignIn.getLastSignedInAccount(requireContext())
        if (account == null) {
            Toast.makeText(requireContext(), "User not signed in.", Toast.LENGTH_LONG).show()
            return
        }
        googleAccount = account
        setupSpinner()
        binding.syncButton.setOnClickListener { fetchFinancialData() }
        fetchFinancialData()
    }

    private fun setupSpinner() {
        val filters = arrayOf("Today", "Yesterday", "Last 7 Days", "This Month", "Custom")
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, filters)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.dateFilterSpinner.adapter = adapter
        binding.dateFilterSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                when (position) {
                    0 -> applyFilter(DateFilter.TODAY)
                    1 -> applyFilter(DateFilter.YESTERDAY)
                    2 -> applyFilter(DateFilter.LAST_7_DAYS)
                    3 -> applyFilter(DateFilter.THIS_MONTH)
                    4 -> showDatePickerDialog()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun fetchFinancialData() {
        val progressDialog = ProgressDialog(requireContext()).apply {
            setMessage("Syncing financial data...")
            setCancelable(false)
            show()
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val sheetsService = getSheetsService(googleAccount)
                val spreadsheetId = findSheetIdByName(getDriveService(googleAccount), "nia-bridge data")
                    ?: throw IOException("Spreadsheet 'nia-bridge data' not found.")

                // Reverted: Removed stock_taking from ranges
                val ranges = listOf(
                    "Closing Balance!A:H",
                    "Expenses!E:H",
                    "Products!D:I",
                    "Transactions!G:H"
                )

                val response = sheetsService.spreadsheets().values().batchGet(spreadsheetId).setRanges(ranges).execute()
                val values = response.valueRanges

                val closingBalanceData = values.getOrNull(0)?.getValues()
                val expensesData = values.getOrNull(1)?.getValues()
                val productData = values.getOrNull(2)?.getValues()
                val transactionCartData = values.getOrNull(3)?.getValues()

                // --- Parse all data ---
                val sales = parseSheet(closingBalanceData, timestampCol = 5, valueCol = 1)
                val closingBalances = parseSheet(closingBalanceData, timestampCol = 5, valueCol = 3)
                val investments = parseSheet(closingBalanceData, timestampCol = 5, valueCol = 6)
                val expenses = parseSheet(expensesData, timestampCol = 3, valueCol = 0)

                val inventoryCost = calculateInventoryCost(productData)
                val costPriceMap = productData?.drop(1)?.associate { row ->
                    row.getOrNull(0)?.toString() to (row.getOrNull(5)?.toString()?.toDoubleOrNull() ?: 0.0)
                } ?: emptyMap()
                val profitTransactions = parseTransactionsForProfit(transactionCartData, costPriceMap)

                // Reverted: Removed variances from BalancingData constructor
                fullBalancingData = BalancingData(
                    sales,
                    closingBalances,
                    investments,
                    expenses,
                    inventoryCost,
                    profitTransactions
                )

                withContext(Dispatchers.Main) {
                    binding.dateFilterSpinner.setSelection(0)
                    applyFilter(DateFilter.TODAY)
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e(TAG, "Error fetching financial data", e)
                    Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    if (progressDialog.isShowing) progressDialog.dismiss()
                }
            }
        }
    }

    private fun parseSheet(data: List<List<Any>>?, timestampCol: Int, valueCol: Int): List<SheetRow> {
        return data?.drop(1)?.mapNotNull { row ->
            val timestampStr = row.getOrNull(timestampCol)?.toString()
            val valueStr = row.getOrNull(valueCol)?.toString()
            val timestamp = parseDateString(timestampStr)

            if (timestamp != null && !valueStr.isNullOrBlank()) {
                SheetRow(
                    timestamp = timestamp,
                    value = valueStr.replace(",", "").toDoubleOrNull() ?: 0.0
                )
            } else {
                if (row.any { it.toString().isNotBlank() } && !timestampStr.isNullOrBlank()) {
                    Log.w(TAG, "Skipping row with blank value or unparseable date in parseSheet: $row")
                }
                null
            }
        } ?: emptyList()
    }

    private fun parseTransactionsForProfit(data: List<List<Any>>?, costMap: Map<String?, Double>): List<ProfitTransaction> {
        val transactionList = mutableListOf<ProfitTransaction>()
        data?.drop(1)?.forEach { row ->
            val cartString = row.getOrNull(0)?.toString()
            val timestampStr = row.getOrNull(1)?.toString()
            val timestamp = parseDateString(timestampStr)

            if (cartString.isNullOrBlank() || !cartString.startsWith("[") || timestamp == null) {
                return@forEach
            }

            try {
                val items = mutableListOf<ProfitTransactionItem>()
                val cartArray = JSONArray(cartString)
                for (j in 0 until cartArray.length()) {
                    val itemObj = cartArray.getJSONObject(j)
                    items.add(
                        ProfitTransactionItem(
                            price = itemObj.optDouble("price", 0.0),
                            quantity = itemObj.optString("quantity").toIntOrNull() ?: 0,
                            costPrice = costMap[itemObj.optString("id")] ?: 0.0
                        )
                    )
                }
                if (items.isNotEmpty()) {
                    transactionList.add(ProfitTransaction(items, timestamp))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse transaction for profit: $row", e)
            }
        }
        return transactionList
    }

    private fun calculateInventoryCost(productData: List<List<Any>>?): Double {
        return productData?.drop(1)?.sumOf { row ->
            val caseQuantity = row.getOrNull(3)?.toString()?.toDoubleOrNull() ?: 0.0
            val unitCost = row.getOrNull(5)?.toString()?.toDoubleOrNull() ?: 0.0
            unitCost * caseQuantity
        } ?: 0.0
    }

    private fun applyFilter(filter: DateFilter, customDate: Date? = null) {
        if (fullBalancingData == null) {
            Toast.makeText(requireContext(), "Data not loaded yet. Please sync.", Toast.LENGTH_SHORT).show()
            return
        }

        val (startDate, endDate) = when (filter) {
            DateFilter.TODAY -> getStartAndEndOfDay(Date())
            DateFilter.YESTERDAY -> {
                val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
                getStartAndEndOfDay(cal.time)
            }
            DateFilter.LAST_7_DAYS -> {
                val end = getEndOfDay(Date())
                val cal = Calendar.getInstance().apply { time = end; add(Calendar.DAY_OF_YEAR, -6) }
                Pair(getStartOfDay(cal.time), end)
            }
            DateFilter.THIS_MONTH -> {
                val cal = Calendar.getInstance()
                cal.set(Calendar.DAY_OF_MONTH, 1)
                val start = getStartOfDay(cal.time)
                cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
                Pair(start, getEndOfDay(cal.time))
            }
            DateFilter.CUSTOM -> getStartAndEndOfDay(customDate ?: Date())
        }

        // Correctly calculate the opening balance from the day BEFORE the start date.
        val openingBalanceCal = Calendar.getInstance().apply { time = startDate; add(Calendar.DAY_OF_YEAR, -1) }
        val (openingStart, openingEnd) = getStartAndEndOfDay(openingBalanceCal.time)
        val openingBalance = fullBalancingData!!.closingBalances.lastOrNull { it.timestamp != null && it.timestamp >= openingStart && it.timestamp <= openingEnd }?.value ?: 0.0

        // --- Filter all data for the selected period (startDate to endDate) ---
        val filteredSales = fullBalancingData!!.sales.filter { it.timestamp != null && it.timestamp >= startDate && it.timestamp <= endDate }.sumOf { it.value }
        val filteredInvestment = fullBalancingData!!.investments.filter { it.timestamp != null && it.timestamp >= startDate && it.timestamp <= endDate }.sumOf { it.value }
        val filteredExpenses = fullBalancingData!!.expenses.filter { it.timestamp != null && it.timestamp >= startDate && it.timestamp <= endDate }.sumOf { it.value }

        // ✅ FINAL FIX: Get the MOST RECENT available cash figure within the selected period.
        val filteredAvailableCash = fullBalancingData!!.closingBalances.lastOrNull { it.timestamp != null && it.timestamp >= startDate && it.timestamp <= endDate }?.value ?: 0.0

        val filteredProfitTransactions = fullBalancingData!!.profitTransactions.filter { it.timestamp != null && it.timestamp >= startDate && it.timestamp <= endDate }
        val profit = filteredProfitTransactions.sumOf { tx -> tx.items.sumOf { item -> (item.price - item.costPrice) * item.quantity } }

        // Formula remains the same, but inputs are now correct
        val expectedCash = (filteredSales + openingBalance + filteredInvestment) - filteredExpenses
        val shortage = expectedCash - filteredAvailableCash
        val capital = filteredAvailableCash + fullBalancingData!!.inventoryCost

        // Reverted: Removed variances from updateUI call
        updateUI(filteredSales, openingBalance, filteredInvestment, filteredExpenses, expectedCash, filteredAvailableCash, shortage, capital, profit)
    }

    // Reverted: Removed variances from signature
    private fun updateUI(sales: Double, openingBalance: Double, investment: Double, expenses: Double, expectedCash: Double, availableCash: Double, shortage: Double, capital: Double, profit: Double) {
        val format = NumberFormat.getCurrencyInstance(Locale("en", "ZM"))

        binding.salesFigure.text = format.format(sales)
        binding.openingBalanceFigure.text = format.format(openingBalance)
        binding.anyInvestmentFigure.text = format.format(investment)
        binding.lessExpensesFigure.text = "(${format.format(expenses)})"
        // Reverted: Removed binding for variances
        binding.expectedCashFigure.text = format.format(expectedCash)
        binding.availableCashFigure.text = format.format(availableCash)
        binding.shortagesFigure.text = format.format(shortage)
        binding.capitalFigure.text = format.format(capital)
        binding.profitFigure.text = format.format(profit)

        val shortageColor = when {
            shortage < -0.01 -> resources.getColor(R.color.variance_positive, null)
            shortage > 0.01 -> resources.getColor(R.color.variance_negative, null)
            else -> resources.getColor(android.R.color.black, null)
        }
        binding.shortagesFigure.setTextColor(shortageColor)
    }

    private fun showDatePickerDialog() {
        val calendar = Calendar.getInstance()
        DatePickerDialog(requireContext(), { _, year, month, dayOfMonth ->
            val selectedDate = Calendar.getInstance().apply { set(year, month, dayOfMonth) }.time
            applyFilter(DateFilter.CUSTOM, selectedDate)
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun getStartOfDay(date: Date): Date = Calendar.getInstance().apply { time = date; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.time
    private fun getEndOfDay(date: Date): Date = Calendar.getInstance().apply { time = date; set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999) }.time
    private fun getStartAndEndOfDay(date: Date): Pair<Date, Date> = Pair(getStartOfDay(date), getEndOfDay(date))

    private fun getSheetsService(account: GoogleSignInAccount): Sheets {
        val credential = GoogleAccountCredential.usingOAuth2(requireContext(), listOf(SheetsScopes.SPREADSHEETS_READONLY)).setBackOff(com.google.api.client.util.ExponentialBackOff()).apply { selectedAccount = account.account }
        return Sheets.Builder(GoogleNetHttpTransport.newTrustedTransport(), GsonFactory.getDefaultInstance(), credential).setApplicationName(getString(R.string.app_name)).build()
    }

    private fun getDriveService(account: GoogleSignInAccount): Drive {
        val credential = GoogleAccountCredential.usingOAuth2(requireContext(), listOf(DriveScopes.DRIVE_READONLY)).setBackOff(com.google.api.client.util.ExponentialBackOff()).apply { selectedAccount = account.account }
        return Drive.Builder(GoogleNetHttpTransport.newTrustedTransport(), GsonFactory.getDefaultInstance(), credential).setApplicationName(getString(R.string.app_name)).build()
    }

    private suspend fun findSheetIdByName(driveService: Drive, name: String): String? = withContext(Dispatchers.IO) {
        driveService.files().list().setQ("name='$name' and mimeType='application/vnd.google-apps.spreadsheet' and trashed=false").setFields("files(id)").execute().files.firstOrNull()?.id
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
