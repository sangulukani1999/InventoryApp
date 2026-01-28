package com.example.zed

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import coil.load
import com.example.zed.databinding.FragmentDetailsStockBinding
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
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
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

// ✅ --- START: HELPER DATA CLASSES ---
data class SaleRecord(
    val timestamp: Date,
    val quantity: Int
)

enum class TimeFilter {
    DAILY,
    WEEKLY,
    MONTHLY,
    YEARLY
}
// ✅ --- END: HELPER DATA CLASSES ---


class detailsStock : Fragment() {

    private var _binding: FragmentDetailsStockBinding? = null
    private val binding get() = _binding!!

    private val sharedViewModel: SharedViewModel by activityViewModels()

    private lateinit var viewPager: ViewPager2
    private var isPopulating = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDetailsStockBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewPager = requireActivity().findViewById(R.id.tabContent)

        // --- SETUP ---
        setupBarChart()
        setupListeners()
        setupDetailListeners()

        // --- OBSERVE SHARED VIEWMODEL ---
        sharedViewModel.selectedProductData.observe(viewLifecycleOwner) { data ->
            if (data != null) {
                isPopulating = true
                populateDetails(data)
                // ✅ --- NEW: Fetch sales data for the chart ---
                fetchSalesDataForProduct(data.product.barcode)
                isPopulating = false
            } else {
                clearDetails()
            }
        }
    }

    private val imagePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val imageUri: Uri? = result.data?.data
                ?: result.data?.getStringExtra("captured_image_uri")?.let { Uri.parse(it) }

            imageUri?.let { uri ->
                binding.stockImage.load(uri) {
                    crossfade(true)
                    placeholder(R.drawable.ic_placeholder)
                    error(R.drawable.ic_error_loading)
                }
                sharedViewModel.updateProductImage(uri.toString())
            }
        }
    }

    private fun setupListeners() {
        binding.btnImage.setOnClickListener { selectImage() }

        binding.barcodeScannerEdit.setOnClickListener {
            val scannerDialog = BarcodeScannerDialogFragment { scannedBarcode ->
                binding.barcode.setText(scannedBarcode)
            }
            scannerDialog.show(childFragmentManager, "DetailsScannerDialog")
        }

        binding.locationUom.setOnClickListener {
            viewPager.currentItem = 2
        }

        // ✅ --- NEW: Listeners for chart filter buttons ---
        binding.daily.setOnClickListener { updateChartWithFilter(TimeFilter.DAILY) }
        binding.weekly.setOnClickListener { updateChartWithFilter(TimeFilter.WEEKLY) }
        binding.monthly.setOnClickListener { updateChartWithFilter(TimeFilter.MONTHLY) }
        binding.yearly.setOnClickListener { updateChartWithFilter(TimeFilter.YEARLY) }
    }

    private fun setupDetailListeners() {
        val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!isPopulating) {
                    sharedViewModel.updateProductDetails(
                        newName = binding.productName.text.toString(),
                        newBarcode = binding.barcode.text.toString(),
                        newCaseQty = binding.units.text.toString(),
                        newMinOrder = binding.minOrder.text.toString(),
                        newUnitCost = binding.unitCost.text.toString()
                    )
                }
            }
        }

        binding.productName.addTextChangedListener(textWatcher)
        binding.barcode.addTextChangedListener(textWatcher)
        binding.units.addTextChangedListener(textWatcher)
        binding.minOrder.addTextChangedListener(textWatcher)
        binding.unitCost.addTextChangedListener(textWatcher)
    }

    private fun selectImage() {
        val options = arrayOf("Take Picture", "Choose from Gallery")
        AlertDialog.Builder(requireContext())
            .setTitle("Select Image Source")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val intent = Intent(requireContext(), CameraActivity::class.java)
                        imagePickerLauncher.launch(intent)
                    }
                    1 -> {
                        val intent = Intent(Intent.ACTION_PICK).apply { type = "image/*" }
                        imagePickerLauncher.launch(intent)
                    }
                }
            }
            .show()
    }

    private fun populateDetails(data: SelectedProductData) {
        binding.stockImage.load(data.product.imageUrl) {
            crossfade(true)
            placeholder(R.drawable.ic_placeholder)
            error(R.drawable.ic_error_loading)
        }

        if (binding.barcode.text.toString() != data.product.barcode) {
            binding.barcode.setText(data.product.barcode)
        }
        if (binding.productName.text.toString() != data.product.name) {
            binding.productName.setText(data.product.name)
        }
        if (binding.units.text.toString() != data.product.caseQty) {
            binding.units.setText(data.product.caseQty)
        }
        if (binding.minOrder.text.toString() != data.product.minOrder) {
            binding.minOrder.setText(data.product.minOrder)
        }

        val singleUnit = data.units.firstOrNull {
            it.quantityDescription.equals("unit", ignoreCase = true) || it.quantityDescription.equals("single", ignoreCase = true)
        }
        val unitCost = singleUnit?.cost ?: data.product.unitCost
        if (binding.unitCost.text.toString() != unitCost) {
            binding.unitCost.setText(unitCost)
        }
    }

    private fun clearDetails() {
        binding.stockImage.setImageResource(R.drawable.ic_placeholder)
        binding.barcode.setText("")
        binding.productName.setText("Select a product to see details")
        binding.unitCost.setText("")
        binding.units.setText("")
        binding.minOrder.setText("")
        // Clear the chart when no product is selected
        binding.barChart.clear()
        binding.barChart.invalidate()
    }

    // ✅ --- START: CHART AND SALES DATA LOGIC ---

    private var allSalesForProduct: List<SaleRecord> = emptyList()
    private var activeFilter: TimeFilter = TimeFilter.DAILY // Default filter

    /**
     * Fetches all transactions and filters them for the current product.
     */
    private fun fetchSalesDataForProduct(productBarcode: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val account = GoogleSignIn.getLastSignedInAccount(requireContext())
                    ?: throw IOException("User not signed in.")
                val sheetsService = getSheetsService(account)
                val spreadsheetId = findSheetIdByName(getDriveService(account), "nia-bridge data")
                    ?: throw IOException("Spreadsheet not found.")

                // Fetch the 'cart' and 'timestamp' columns from the Transactions sheet
                val range = "Transactions!H:I" // Column H is cart, Column I is timestamp
                val response = sheetsService.spreadsheets().values().get(spreadsheetId, range).execute()
                val values = response.getValues()

                if (values.isNullOrEmpty()) {
                    allSalesForProduct = emptyList()
                } else {
                    val sales = mutableListOf<SaleRecord>()
                    val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())

                    // Start from the second row to skip headers
                    for (row in values.drop(1)) {
                        val cartString = row.getOrNull(0)?.toString() // Cart is in the first column of our range (H)
                        val timestampStr = row.getOrNull(1)?.toString() // Timestamp is in the second (I)

                        if (cartString.isNullOrBlank() || timestampStr.isNullOrBlank()) continue

                        try {
                            val timestamp = dateFormat.parse(timestampStr) ?: continue
                            val cartArray = JSONArray(cartString)
                            for (i in 0 until cartArray.length()) {
                                val item = cartArray.getJSONObject(i)
                                if (item.getString("id") == productBarcode) {
                                    val quantity = item.getString("quantity").toIntOrNull() ?: 0
                                    sales.add(SaleRecord(timestamp, quantity))
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("SalesFetch", "Error parsing row: $row", e)
                        }
                    }
                    allSalesForProduct = sales
                }

                // Switch to the main thread to update the UI
                withContext(Dispatchers.Main) {
                    updateChartWithFilter(activeFilter) // Apply the current filter
                }

            } catch (e: Exception) {
                Log.e("SalesFetch", "Failed to fetch sales data", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Could not load sales data.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Called when a filter button is clicked. Sets the active filter and updates the chart.
     */
    private fun updateChartWithFilter(filter: TimeFilter) {
        activeFilter = filter
        // Update button backgrounds
        updateFilterButtonUI()

        val now = Calendar.getInstance()
        val (aggregatedData, labels) = when (filter) {
            TimeFilter.DAILY -> aggregateData(allSalesForProduct, now, Calendar.DAY_OF_YEAR, 7, "EEE") // Mon, Tue
            TimeFilter.WEEKLY -> aggregateData(allSalesForProduct, now, Calendar.WEEK_OF_YEAR, 4, "'Week' W") // Week 23
            TimeFilter.MONTHLY -> aggregateData(allSalesForProduct, now, Calendar.MONTH, 12, "MMM") // Jun, Jul
            TimeFilter.YEARLY -> aggregateData(allSalesForProduct, now, Calendar.YEAR, 5, "yyyy") // 2023, 2024
        }

        val entries = aggregatedData.mapIndexed { index, value ->
            BarEntry(index.toFloat(), value.toFloat())
        }

        updateBarChart(entries, labels, "Sales")
    }

    /**
     * Aggregates sales data based on a given time period.
     */
    private fun aggregateData(
        sales: List<SaleRecord>,
        now: Calendar,
        calendarField: Int,
        numberOfPeriods: Int,
        labelFormat: String
    ): Pair<List<Int>, List<String>> {
        val aggregatedData = IntArray(numberOfPeriods)
        val labels = Array(numberOfPeriods) { "" }
        val sdf = SimpleDateFormat(labelFormat, Locale.getDefault())

        // Create labels for the past periods
        for (i in (numberOfPeriods - 1) downTo 0) {
            val periodCal = now.clone() as Calendar
            periodCal.add(calendarField, -i)
            labels[numberOfPeriods - 1 - i] = sdf.format(periodCal.time)
        }

        val cutoff = now.clone() as Calendar
        cutoff.add(calendarField, -(numberOfPeriods - 1))
        cutoff.set(Calendar.HOUR_OF_DAY, 0) // Start of the first period

        // Filter sales within the relevant timeframe and aggregate them
        sales.filter { it.timestamp.after(cutoff.time) }.forEach { sale ->
            val saleCal = Calendar.getInstance().apply { time = sale.timestamp }
            val diff = now.timeInMillis - saleCal.timeInMillis

            val periodIndex = when (calendarField) {
                Calendar.DAY_OF_YEAR -> TimeUnit.MILLISECONDS.toDays(diff).toInt()
                Calendar.WEEK_OF_YEAR -> (TimeUnit.MILLISECONDS.toDays(diff) / 7).toInt()
                Calendar.MONTH -> {
                    (now.get(Calendar.YEAR) * 12 + now.get(Calendar.MONTH)) -
                            (saleCal.get(Calendar.YEAR) * 12 + saleCal.get(Calendar.MONTH))
                }
                Calendar.YEAR -> now.get(Calendar.YEAR) - saleCal.get(Calendar.YEAR)
                else -> -1
            }

            if (periodIndex in 0 until numberOfPeriods) {
                aggregatedData[numberOfPeriods - 1 - periodIndex] += sale.quantity
            }
        }

        return Pair(aggregatedData.toList(), labels.toList())
    }

    private fun updateFilterButtonUI() {
        val buttons = mapOf(
            TimeFilter.DAILY to binding.daily,
            TimeFilter.WEEKLY to binding.weekly,
            TimeFilter.MONTHLY to binding.monthly,
            TimeFilter.YEARLY to binding.yearly
        )
        val activeColor = ContextCompat.getColor(requireContext(), R.color.active_filter_color)
        val inactiveColor = ContextCompat.getColor(requireContext(), R.color.unselected_item_color)

        buttons.forEach { (filter, button) ->
            button.setCardBackgroundColor(if (filter == activeFilter) activeColor else inactiveColor)
        }
    }


    /**
     * Generic function to set up the bar chart's appearance.
     */
    private fun setupBarChart() {
        binding.barChart.apply {
            axisRight.isEnabled = false
            axisLeft.setDrawGridLines(false)
            xAxis.setDrawGridLines(false)
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.granularity = 1f
            description.isEnabled = false
            legend.isEnabled = false
            animateY(1000)
        }
    }

    /**
     * Updates the BarChart with new data and labels.
     */
    private fun updateBarChart(entries: List<BarEntry>, labels: List<String>, dataLabel: String) {
        if (entries.isEmpty()) {
            binding.barChart.clear()
            binding.barChart.invalidate()
            return
        }

        val dataSet = BarDataSet(entries, dataLabel).apply {
            colors = listOf(
                Color.parseColor("#004c91"),
                Color.parseColor("#00c3ff"),
                Color.parseColor("#4a90e2"),
                Color.parseColor("#0071bc")
            )
            valueTextColor = Color.parseColor("#0071c1")
            valueTextSize = 12f
        }

        binding.barChart.data = BarData(dataSet)
        binding.barChart.xAxis.valueFormatter = IndexAxisValueFormatter(labels)
        binding.barChart.invalidate() // Refresh the chart
    }

    // ✅ --- END: CHART AND SALES DATA LOGIC ---


    // --- Google Sheets Helper Functions ---
    private suspend fun getSheetsService(account: GoogleSignInAccount): Sheets {
        val credential = GoogleAccountCredential.usingOAuth2(requireContext(), listOf(SheetsScopes.SPREADSHEETS_READONLY))
            .setSelectedAccount(account.account)
        return Sheets.Builder(
            GoogleNetHttpTransport.newTrustedTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        ).setApplicationName(getString(R.string.app_name)).build()
    }

    private suspend fun getDriveService(account: GoogleSignInAccount): Drive {
        val credential = GoogleAccountCredential.usingOAuth2(requireContext(), listOf(DriveScopes.DRIVE_READONLY))
            .setSelectedAccount(account.account)
        return Drive.Builder(
            GoogleNetHttpTransport.newTrustedTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        ).setApplicationName(getString(R.string.app_name)).build()
    }

    private suspend fun findSheetIdByName(driveService: Drive, name: String): String? = withContext(Dispatchers.IO) {
        val query = "name='$name' and mimeType='application/vnd.google-apps.spreadsheet' and trashed=false"
        val result = driveService.files().list().setQ(query).setSpaces("drive").setFields("files(id)").execute()
        result.files.firstOrNull()?.id
    }


    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
