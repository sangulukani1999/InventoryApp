package com.example.zed

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import androidx.viewpager2.widget.ViewPager2
import coil.load
import com.example.zed.databinding.FragmentDetailsStockBinding
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.components.XAxis

class detailsStock : Fragment() {

    private var _binding: FragmentDetailsStockBinding? = null
    private val binding get() = _binding!!

    private val sharedViewModel: SharedViewModel by activityViewModels()

    private lateinit var viewPager: ViewPager2

    // A flag to prevent the TextWatcher from triggering when we programmatically set the text
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
                // When observing, wrap populateDetails in a flag to prevent infinite loops
                isPopulating = true
                populateDetails(data)
                isPopulating = false
            } else {
                clearDetails()
            }
        }
    }

    // ✅ --- START: CORRECTED IMAGE LAUNCHER ---
    private val imagePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // This safely gets the URI whether from the camera (as a string extra) or the gallery (as data).
            val imageUri: Uri? = result.data?.data
                ?: result.data?.getStringExtra("captured_image_uri")?.let { Uri.parse(it) }

            imageUri?.let { uri ->
                // 1. Load the new image into the ImageView using Coil for immediate UI feedback.
                binding.stockImage.load(uri) {
                    crossfade(true)
                    placeholder(R.drawable.ic_placeholder)
                    error(R.drawable.ic_error_loading)
                }

                // 2. Update the ViewModel so the new image path can be saved later.
                // This now stores a local file URI (e.g., content://...)
                sharedViewModel.updateProductImage(uri.toString())
            }
        }
    }
    // ✅ --- END: CORRECTED IMAGE LAUNCHER ---

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
    }

    /**
     * Sets up TextWatchers to listen for changes in the EditText fields
     * and automatically updates the SharedViewModel.
     */
    private fun setupDetailListeners() {
        val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // Only update the ViewModel if the change was made by the user, not by the program
                if (!isPopulating) {
                    sharedViewModel.updateProductDetails(
                        newName = binding.productName.text.toString(),
                        newBarcode = binding.barcode.text.toString(),
                        newCaseQty = binding.units.text.toString(), // Renamed for clarity
                        newMinOrder = binding.minOrder.text.toString(),
                        newUnitCost = binding.unitCost.text.toString()
                    )
                }
            }
        }

        // Attach the watcher to all relevant EditText fields
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

    /**
     * Populates the UI fields with data from the SelectedProductData object.
     */
    private fun populateDetails(data: SelectedProductData) {
        // This will now correctly load from a web URL (https://) or a local file URI (content://)
        binding.stockImage.load(data.product.imageUrl) {
            crossfade(true)
            placeholder(R.drawable.ic_placeholder)
            error(R.drawable.ic_error_loading)
        }

        binding.barcode.setText(data.product.barcode)
        binding.productName.setText(data.product.name)

        val singleUnit = data.units.firstOrNull {
            it.quantityDescription.equals("unit", ignoreCase = true) || it.quantityDescription.equals("single", ignoreCase = true)
        }

        binding.unitCost.setText(singleUnit?.cost ?: data.product.unitCost)
        binding.units.setText(data.product.caseQty)
        binding.minOrder.setText(data.product.minOrder)
    }

    /**
     * Clears all fields and shows a placeholder state.
     */
    private fun clearDetails() {
        binding.stockImage.setImageResource(R.drawable.ic_placeholder)
        binding.barcode.setText("")
        binding.productName.setText("Select a product to see details")
        binding.unitCost.setText("")
        binding.units.setText("")
        binding.minOrder.setText("")
    }

    /**
     * Sets up the BarChart with sample data.
     */
    private fun setupBarChart() {
        val barChart: BarChart = binding.barChart

        val entries = listOf(
            BarEntry(0f, 100f),
            BarEntry(1f, 150f),
            BarEntry(2f, 125f),
            BarEntry(3f, 180f)
        )

        val dataSet = BarDataSet(entries, "Stock Levels")
        dataSet.colors = listOf(
            Color.parseColor("#004c91"),
            Color.parseColor("#00c3ff"),
            Color.parseColor("#4a90e2"),
            Color.parseColor("#0071bc")
        )
        dataSet.valueTextColor = Color.parseColor("#0071c1")
        dataSet.valueTextSize = 14f

        val barData = BarData(dataSet)
        barChart.data = barData

        val labels = listOf("Yoyo", "Castle Lite", "Monarch", "Simba")
        barChart.xAxis.valueFormatter = IndexAxisValueFormatter(labels)
        barChart.xAxis.granularity = 1f
        barChart.xAxis.position = XAxis.XAxisPosition.BOTTOM
        barChart.xAxis.setDrawGridLines(false)

        barChart.axisLeft.setDrawGridLines(false)
        barChart.axisRight.isEnabled = false
        barChart.description.isEnabled = false
        barChart.legend.isEnabled = false
        barChart.animateY(1000)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
