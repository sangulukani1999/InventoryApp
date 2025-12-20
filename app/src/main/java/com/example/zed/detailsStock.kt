package com.example.zed

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import androidx.viewpager2.widget.ViewPager2 // ✅ 1. IMPORT ViewPager2
import coil.load
import com.example.zed.databinding.FragmentDetailsStockBinding
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.components.XAxis

class detailsStock : Fragment() {
    private var imageUri: Uri? = null

    private var _binding: FragmentDetailsStockBinding? = null
    private val binding get() = _binding!!

    private val sharedViewModel: SharedViewModel by activityViewModels()

    // ✅ 2. DECLARE a variable for the ViewPager
    private lateinit var viewPager: ViewPager2

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDetailsStockBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // ✅ 3. INITIALIZE the ViewPager from the parent activity
        viewPager = requireActivity().findViewById(R.id.tabContent) // Assumes this is the ID in your main activity

        // --- SETUP BARCHART ---
        setupBarChart()

        // --- SETUP LISTENERS ---
        setupListeners()

        // --- OBSERVE SHARED VIEWMODEL FOR PRODUCT DETAILS ---
        sharedViewModel.selectedProductData.observe(viewLifecycleOwner) { data ->
            if (data != null) {
                populateDetails(data)
            } else {
                clearDetails()
            }
        }
    }

    private val imagePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            var foundUri: Uri? = null

            if (result.data?.data != null) {
                foundUri = result.data?.data
            } else {
                result.data?.getStringExtra("captured_image_uri")?.let { uriString ->
                    foundUri = Uri.parse(uriString)
                }
            }

            foundUri?.let {
                imageUri = it
                binding.stockImage.setImageURI(imageUri)
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

        // ✅ 4. SET the click listener for the location_uom CardView
        binding.locationUom.setOnClickListener {
            // Switch to the next tab (index 2, for "Locations & UOM")
            viewPager.currentItem = 2
        }

        // You can add the save button listener here as well
        // binding.btnSaveChanges.setOnClickListener { /* ... */ }
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
                        val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
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

        if (singleUnit != null) {
            binding.unitCost.setText(singleUnit.cost)
        } else {
            binding.unitCost.setText(data.product.unitCost)
        }

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
