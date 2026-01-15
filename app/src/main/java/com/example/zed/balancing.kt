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
import java.io.IOException
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

class balancing : Fragment() {

    private var _binding: FragmentBalancingBinding? = null
    private val binding get() = _binding!!

    private lateinit var googleAccount: GoogleSignInAccount
    private val TAG = "BalancingFragment"

    private var fullBalancingData: BalancingData? = null

    // Enum for date filters
    private enum class DateFilter { TODAY, YESTERDAY, LAST_7_DAYS, THIS_MONTH, CUSTOM }

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
        binding.syncButton.setOnClickListener {
            fetchFinancialData()
        }

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

                val ranges = listOf(
                    "Transactions!H:K",      // 0: Timestamp, Total Amount
                    "Closing Balance!B:G",   // 1: Timestamp, Opening, Investment, Available
                    "Expenses!B:C",          // 2: Timestamp, Amount
                    "Products!D:I",          // 3: Barcode, Cost Price
                    "Transactions!G:H"       // 4: Cart, Timestamp
                )

                val response = sheetsService.spreadsheets().values()
                    .batchGet(spreadsheetId)
                    .setRanges(ranges)
                    .execute()

                val values = response.valueRanges

                // Parse all data
                val sales = parseSheet(values.getOrNull(0)?.getValues())
                val openingBalances = parseSheet(values.getOrNull(1)?.getValues())
                val investments = parseSheet(values.getOrNull(1)?.getValues(), 5) // Col G
                val availableCash = parseSheet(values.getOrNull(1)?.getValues(), 2) // Col D
                val expenses = parseSheet(values.getOrNull(2)?.getValues())
                val inventoryCost = calculateInventoryCost(values.getOrNull(3)?.getValues())

                val salesWithCost = calculateSalesWithCost(
                    values.getOrNull(4)?.getValues(), // Cart data
                    values.getOrNull(3)?.getValues()  // Product data for cost lookup
                )

                fullBalancingData = BalancingData(
                    sales,
                    openingBalances,
                    investments,
                    expenses,
                    availableCash,
                    inventoryCost,
                    salesWithCost
                )

                withContext(Dispatchers.Main) {
                    // Apply default filter after fetching
                    binding.dateFilterSpinner.setSelection(0) // Set to "Today"
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

    private fun parseSheet(data: List<List<Any>>?, valueColumnIndex: Int = 1): List<SheetRow> {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return data?.drop(1)?.mapNotNull { row ->
            try {
                val timestampStr = row.getOrNull(0)?.toString()
                val valueStr = row.getOrNull(valueColumnIndex)?.toString()
                if (timestampStr != null && valueStr != null) {
                    SheetRow(
                        timestamp = sdf.parse(timestampStr),
                        value = valueStr.toDoubleOrNull() ?: 0.0
                    )
                } else null
            } catch (e: Exception) {
                null
            }
        } ?: emptyList()
    }

    private fun calculateInventoryCost(productData: List<List<Any>>?): Double {
        return productData?.drop(1)?.sumOf { row ->
            val costPrice = row.getOrNull(5)?.toString()?.toDoubleOrNull() ?: 0.0 // Col I
            val quantity = row.getOrNull(3)?.toString()?.toDoubleOrNull() ?: 0.0  // Col G
            costPrice * quantity
        } ?: 0.0
    }

    private fun calculateSalesWithCost(salesData: List<List<Any>>?, productData: List<List<Any>>?): List<Pair<SheetRow, Double>> {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val productCostMap = productData?.drop(1)?.associate { row ->
            row.getOrNull(0).toString() to (row.getOrNull(5)?.toString()?.toDoubleOrNull() ?: 0.0)
        } ?: emptyMap()

        val salesWithCost = mutableListOf<Pair<SheetRow, Double>>()

        salesData?.drop(1)?.forEach { row ->
            try {
                val cartString = row.getOrNull(0)?.toString()
                val timestampStr = row.getOrNull(1)?.toString()
                if (cartString != null && timestampStr != null) {
                    val timestamp = sdf.parse(timestampStr)
                    var totalSaleValue = 0.0
                    var totalCostOfSale = 0.0

                    val cartArray = org.json.JSONArray(cartString)
                    for (i in 0 until cartArray.length()) {
                        val item = cartArray.getJSONObject(i)
                        val id = item.optString("id")
                        val price = item.optDouble("price", 0.0)
                        val quantity = item.optInt("quantity", 0)

                        totalSaleValue += price * quantity
                        totalCostOfSale += (productCostMap[id] ?: 0.0) * quantity
                    }

                    val saleRow = SheetRow(timestamp, totalSaleValue)
                    salesWithCost.add(Pair(saleRow, totalCostOfSale))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing sales cart data", e)
            }
        }
        return salesWithCost
    }


    private fun applyFilter(filter: DateFilter, customDate: Date? = null) {
        if (fullBalancingData == null) {
            Toast.makeText(requireContext(), "Data not loaded yet. Please sync.", Toast.LENGTH_SHORT).show()
            return
        }

        val calendar = Calendar.getInstance()
        val (startDate, endDate) = when (filter) {
            DateFilter.TODAY -> getStartAndEndOfDay(calendar.time)
            DateFilter.YESTERDAY -> {
                calendar.add(Calendar.DAY_OF_YEAR, -1)
                getStartAndEndOfDay(calendar.time)
            }
            DateFilter.LAST_7_DAYS -> {
                val end = getEndOfDay(Date())
                calendar.time = end
                calendar.add(Calendar.DAY_OF_YEAR, -6)
                val start = getStartOfDay(calendar.time)
                Pair(start, end)
            }
            DateFilter.THIS_MONTH -> {
                calendar.set(Calendar.DAY_OF_MONTH, 1)
                val start = getStartOfDay(calendar.time)
                calendar.set(Calendar.DAY_OF_MONTH, calendar.getActualMaximum(Calendar.DAY_OF_MONTH))
                val end = getEndOfDay(calendar.time)
                Pair(start, end)
            }
            // ✅ FIX #1: Handle the nullable customDate safely
            DateFilter.CUSTOM -> getStartAndEndOfDay(customDate ?: Date())
        }

        // ✅ FIX #2: Add null checks inside the filter conditions
        val filteredSales = fullBalancingData!!.sales.filter { it.timestamp != null && it.timestamp in startDate..endDate }.sumOf { it.value }
        val filteredOpeningBalance = fullBalancingData!!.openingBalances.filter { it.timestamp != null && it.timestamp in startDate..endDate }.sumOf { it.value }
        val filteredInvestment = fullBalancingData!!.investments.filter { it.timestamp != null && it.timestamp in startDate..endDate }.sumOf { it.value }
        val filteredExpenses = fullBalancingData!!.expenses.filter { it.timestamp != null && it.timestamp in startDate..endDate }.sumOf { it.value }
        val filteredAvailableCash = fullBalancingData!!.availableCash.filter { it.timestamp != null && it.timestamp in startDate..endDate }.sumOf { it.value }

        // ✅ THIS IS THE FIX
        val filteredSalesAndCost = fullBalancingData!!.salesWithCost.filter { it.first.timestamp != null && it.first.timestamp!! in startDate..endDate }
        val totalItemsSoldValue = filteredSalesAndCost.sumOf { it.first.value }
        val totalCostOfItemsSold = filteredSalesAndCost.sumOf { it.second }

        // Perform calculations
        val expectedCash = (filteredSales + filteredOpeningBalance + filteredInvestment) - filteredExpenses
        val shortage = expectedCash - filteredAvailableCash
        val capital = filteredAvailableCash + fullBalancingData!!.inventoryCost
        val profit = totalItemsSoldValue - totalCostOfItemsSold

        // Update UI
        updateUI(
            sales = filteredSales,
            openingBalance = filteredOpeningBalance,
            investment = filteredInvestment,
            expenses = filteredExpenses,
            expectedCash = expectedCash,
            availableCash = filteredAvailableCash,
            shortage = shortage,
            capital = capital,
            profit = profit
        )
    }

    private fun updateUI(
        sales: Double, openingBalance: Double, investment: Double, expenses: Double,
        expectedCash: Double, availableCash: Double, shortage: Double,
        capital: Double, profit: Double
    ) {
        val format = NumberFormat.getCurrencyInstance(Locale("en", "ZM")) // Assuming Zambian Kwacha format

        binding.salesFigure.text = format.format(sales)
        binding.openingBalanceFigure.text = format.format(openingBalance)
        binding.anyInvestmentFigure.text = format.format(investment)
        binding.lessExpensesFigure.text = "(${format.format(expenses)})"
        binding.expectedCashFigure.text = format.format(expectedCash)
        binding.availableCashFigure.text = format.format(availableCash)
        binding.shortagesFigure.text = format.format(shortage)
        binding.capitalFigure.text = format.format(capital)
        binding.profitFigure.text = format.format(profit)

        // Set color for shortage/overage
        val shortageColor = when {
            shortage < 0 -> R.color.variance_positive // Overage is green
            shortage > 0 -> R.color.variance_negative // Shortage is red
            else -> android.R.color.black
        }
        binding.shortagesFigure.setTextColor(resources.getColor(shortageColor, null))
    }

    private fun showDatePickerDialog() {
        val calendar = Calendar.getInstance()
        DatePickerDialog(
            requireContext(),
            { _, year, month, dayOfMonth ->
                val selectedDate = Calendar.getInstance().apply { set(year, month, dayOfMonth) }.time
                applyFilter(DateFilter.CUSTOM, selectedDate)
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    // --- Date Helper Functions ---
    private fun getStartOfDay(date: Date): Date = Calendar.getInstance().apply { time = date; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.time
    private fun getEndOfDay(date: Date): Date = Calendar.getInstance().apply { time = date; set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999) }.time
    private fun getStartAndEndOfDay(date: Date): Pair<Date, Date> = Pair(getStartOfDay(date), getEndOfDay(date))

    // --- Google API Helper Functions ---
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
        _binding = null // Clean up binding to avoid memory leaks
    }
}
