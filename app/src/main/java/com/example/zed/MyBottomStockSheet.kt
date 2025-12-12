package com.example.zed

import android.app.ProgressDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.FileContent
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.SheetsScopes
import com.google.api.services.sheets.v4.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*

class MyBottomStockSheet(
    private val userEmail: String?,
    private val parentEmail: String?,
    private val onStockAdded: () -> Unit
) : BottomSheetDialogFragment() {

    companion object {
        private const val SPREADSHEET_NAME = "nia-bridge data"
        private const val FOLDER_NAME = "nia-bridge"
        private const val SHEET_TAB_NAME = "Products"
        private const val SHEET_UNITS = "unit_measure"
        private const val SHEET_LOCATIONS = "product_location"
        private const val SHEET_CATEGORY = "category"
    }

    // --- UI Views ---
    private var imageUri: Uri? = null
    private lateinit var quantity_display: EditText
    private lateinit var imageView: ImageView
    private lateinit var btnUpload: ImageView
    private lateinit var btnSubmit: CardView
    private lateinit var progressDialog: ProgressDialog
    private lateinit var productName: EditText
    private lateinit var barcode: EditText
    private lateinit var category: TextView
    private lateinit var qty: EditText
    private lateinit var unitCost: EditText
    private lateinit var unitCounterTextView: TextView
    private lateinit var unitContainer: LinearLayout
    private lateinit var btnAddUnit: CardView
    private lateinit var unitScrollView: NestedScrollView
    private lateinit var locationCounterTextView: TextView
    private lateinit var locationContainer: LinearLayout
    private lateinit var location_add: CardView
    private lateinit var locationScrollView: NestedScrollView
    private lateinit var addCategoryCardView: CardView
    private lateinit var unit_of_measure_populates: Spinner

    // --- Data Classes ---
    data class SheetItem(val id: String, val name: String) {
        override fun toString(): String = name
    }

    data class UnitOfMeasureItem(val description: String, val value: Int) {
        override fun toString(): String = description // This is what shows in the spinner
    }

    // --- Data Lists for Spinners and Validation ---
    private val dynamicCategories = mutableListOf<SheetItem>()
    private val dynamicAisles = mutableListOf<String>()
    private val dynamicRacks = mutableListOf<String>()
    private val dynamicShelves = mutableListOf<String>()
    private val dynamicUnitsOfMeasure = mutableListOf<UnitOfMeasureItem>() // Session list for the spinner
    private lateinit var uomAdapter: ArrayAdapter<UnitOfMeasureItem>

    // EFFICIENT VALIDATION LISTS
    private val occupiedLocationIds = mutableSetOf<String>()
    private val locationNameToIdMap = mutableMapOf<String, String>()
    private val newlySelectedLocationIds = mutableSetOf<String>()

    /**
     * This is the single, centralized watcher that will be attached to any field
     * that should trigger a recalculation of the total quantity.
     */
    private val mainCalculationWatcher: TextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            // Every time a relevant field changes, run the master calculation function.
            updateTotalCalculation()
        }
    }

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            imageUri = result.data?.data
            imageView.setImageURI(imageUri)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.bottom_sheet_stock_layout, container, false)

        initializeViews(view) // Initialize all views first

        // --- Setup for the dynamic unit_of_measure_populates Spinner ---
        // Initialize the session list with a placeholder item
        if (dynamicUnitsOfMeasure.isEmpty()) {
            dynamicUnitsOfMeasure.add(UnitOfMeasureItem("Select a defined unit", 0))
        }

        // Initialize the adapter for the spinner
        uomAdapter = ArrayAdapter(
            requireContext(),
            R.layout.spinner_item,
            dynamicUnitsOfMeasure
        )
        uomAdapter.setDropDownViewResource(R.layout.spinner_item)
        unit_of_measure_populates.adapter = uomAdapter

        setupListeners()
        fetchDynamicData()
        return view
    }

    private fun initializeViews(view: View) {
        imageView = view.findViewById(R.id.stockImage)
        btnUpload = view.findViewById(R.id.btnImage)
        btnSubmit = view.findViewById(R.id.btnSubmit)
        productName = view.findViewById(R.id.productName)
        barcode = view.findViewById(R.id.barcode)
        category = view.findViewById(R.id.category)

        // --- CORRECTED MAPPINGS ---
        qty = view.findViewById(R.id.qty)
        unitCost = view.findViewById(R.id.unitCost)

        quantity_display = view.findViewById(R.id.quantity_display)

        unitScrollView = view.findViewById(R.id.mainScroll)
        unitContainer = view.findViewById(R.id.unitContainer)
        btnAddUnit = view.findViewById(R.id.btnAddUnit)
        locationScrollView = view.findViewById(R.id.mainScroll)
        locationContainer = view.findViewById(R.id.locationContainer)
        location_add = view.findViewById(R.id.location_add)
        locationCounterTextView = view.findViewById(R.id.location_counter)
        unitCounterTextView = view.findViewById(R.id.unit_counter)
        addCategoryCardView = view.findViewById(R.id.add_category)
        unit_of_measure_populates = view.findViewById(R.id.unit_of_measure_populates)

        locationCounterTextView.text = "0"
        unitCounterTextView.text = "0"
        progressDialog = ProgressDialog(requireContext()).apply {
            setCancelable(false)
        }
    }

    private fun setupListeners() {
        btnAddUnit.setOnClickListener { addUnitView() }
        location_add.setOnClickListener { addUnitLocationView() }
        btnUpload.setOnClickListener { pickImageFromGallery() }
        btnSubmit.setOnClickListener { handleSubmission() }
        category.setOnClickListener { showCategoryDialog() }
        addCategoryCardView.setOnClickListener {
            showAddNewCategoryDialog()
        }
        // Attach the watcher to the main quantity field.
        qty.addTextChangedListener(mainCalculationWatcher)

        // Any change in the spinner selection must trigger a recalculation.
        unit_of_measure_populates.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateTotalCalculation()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                quantity_display.setText("0")
            }
        }
    }

    private fun fetchDynamicData() {
        progressDialog.setMessage("Loading dynamic data...")
        progressDialog.show()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val account = GoogleSignIn.getLastSignedInAccount(requireContext())
                    ?: throw IllegalStateException("User not signed in")
                val sheetsService = getSheetsService(account)
                val spreadsheetId = findSheetIdByName(getDriveService(account), SPREADSHEET_NAME)
                    ?: throw IllegalStateException("Spreadsheet not found")

                val categoryRange = "'$SHEET_CATEGORY'!A2:B"
                val categoryResponse = sheetsService.spreadsheets().values().get(spreadsheetId, categoryRange).execute()
                val categoriesFromSheet = categoryResponse.getValues()?.mapNotNull { row ->
                    if (row.size >= 2) SheetItem(id = row[0].toString(), name = row[1].toString()) else null
                } ?: emptyList()

                // FETCH ALL LOCATION DATA AND BUILD MAPS
                val locationRange = "'$SHEET_LOCATIONS'!A2:D"
                val locationResponse = sheetsService.spreadsheets().values().get(spreadsheetId, locationRange).execute()
                val aislesFromSheet = mutableSetOf<String>()
                val racksFromSheet = mutableSetOf<String>()
                val shelvesFromSheet = mutableSetOf<String>()
                val tempLocationMap = mutableMapOf<String, String>()

                locationResponse.getValues()?.forEach { row ->
                    if (row.size >= 4) {
                        val id = row[0].toString()
                        val aisle = row[1].toString()
                        val rack = row[2].toString()
                        val shelf = row[3].toString()

                        if (aisle.isNotBlank()) aislesFromSheet.add(aisle)
                        if (rack.isNotBlank()) racksFromSheet.add(rack)
                        if (shelf.isNotBlank()) shelvesFromSheet.add(shelf)

                        tempLocationMap["$aisle-$rack-$shelf".lowercase()] = id
                    }
                }

                // FETCH ALL OCCUPIED LOCATION IDS
                val productsLocationRange = "'$SHEET_TAB_NAME'!J2:J"
                val productsResponse = sheetsService.spreadsheets().values().get(spreadsheetId, productsLocationRange).execute()
                val usedIds = productsResponse.getValues()?.flatMap { row ->
                    val rawString = row.getOrNull(0)?.toString() ?: "[]"
                    rawString.removeSurrounding("[", "]").replace("'", "").split(",").map { it.trim() }.filter { it.isNotBlank() }
                } ?: emptyList()

                withContext(Dispatchers.Main) {
                    dynamicCategories.clear()
                    dynamicCategories.addAll(categoriesFromSheet)
                    dynamicAisles.clear()
                    dynamicAisles.addAll(aislesFromSheet.sorted())
                    dynamicRacks.clear()
                    dynamicRacks.addAll(racksFromSheet.sorted())
                    dynamicShelves.clear()
                    dynamicShelves.addAll(shelvesFromSheet.sorted())

                    // POPULATE THE LISTS FOR INSTANT CHECKING
                    locationNameToIdMap.clear()
                    locationNameToIdMap.putAll(tempLocationMap)
                    occupiedLocationIds.clear()
                    occupiedLocationIds.addAll(usedIds)
                    newlySelectedLocationIds.clear() // Clear session list on every fresh load

                    progressDialog.dismiss()
                    Toast.makeText(context, "Ready to add products.", Toast.LENGTH_SHORT).show()
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(context, "Failed to load dynamic data: ${e.message}", Toast.LENGTH_LONG).show()
                    Log.e("FetchDynamicData", "Error fetching from Google Sheet", e)
                }
            }
        }
    }

    /**
     * Calculates the total based on the SINGLE selected unit of measure
     * from the spinner. This version is safe against null selections.
     */
    private fun updateTotalCalculation() {
        // 1. Get the selected item from the spinner.
        val selectedItem = unit_of_measure_populates.selectedItem as? UnitOfMeasureItem

        // 2. If the selected item is null or it's the placeholder (value 0), the total is 0.
        if (selectedItem == null || selectedItem.value == 0) {
            quantity_display.setText("0")
            return // Stop execution here.
        }

        // 3. Get the value from the valid selected item.
        val selectedUnitValue = selectedItem.value.toDouble()

        // 4. Get the main quantity.
        val quantityOfCases = qty.text.toString().toDoubleOrNull() ?: 0.0

        // 5. Perform the calculation.
        val grandTotal = selectedUnitValue * quantityOfCases

        // 6. Update the display.
        quantity_display.setText(String.format("%.0f", grandTotal))
    }


    private fun handleSubmission() {
        progressDialog.setMessage("Validating product...")
        progressDialog.show()

        val pName = productName.text.toString().trim()
        val pBarcode = barcode.text.toString().trim()

        if (userEmail.isNullOrBlank() || imageUri == null || pName.isEmpty()) {
            Toast.makeText(requireContext(), "Please fill all required fields and select an image.", Toast.LENGTH_SHORT).show()
            progressDialog.dismiss()
            return
        }

        lifecycleScope.launch {
            try {
                val account = GoogleSignIn.getLastSignedInAccount(requireContext())
                    ?: throw Exception("Could not get user account.")

                // ✅ CORRECTED VALIDATION LOGIC
                var isAnyLocationInvalid = false
                for (i in 0 until locationContainer.childCount) {
                    val locationView = locationContainer.getChildAt(i)
                    val aisleSpinner = locationView.findViewById<Spinner>(R.id.aisle_spinner)
                    val errorDrawable = ContextCompat.getDrawable(requireContext(), R.drawable.spinner_border_error)

                    // Check if this specific row is invalid
                    if (aisleSpinner.background.constantState == errorDrawable?.constantState) {
                        isAnyLocationInvalid = true
                    }
                }

                if (isAnyLocationInvalid) {
                    withContext(Dispatchers.Main) {
                        AlertDialog.Builder(requireContext())
                            .setTitle("Invalid Location")
                            .setMessage("One or more selected locations is unavailable. Please correct the entries with a red border before submitting.")
                            .setPositiveButton("OK", null)
                            .show()
                        progressDialog.dismiss()
                    }
                    return@launch // Stop the submission
                }
                // ✅ END OF CORRECTION

                val units = getAllUnitsData()
                val locations = getAllLocationsData()

                // ✅ **FIX**: Use the calculated value from quantity_display for the case quantity.
                val finalCaseQty = quantity_display.text.toString()

                progressDialog.setMessage("Uploading product...")
                uploadImageAndWriteToSheet(
                    account, imageUri!!, SPREADSHEET_NAME, SHEET_TAB_NAME, userEmail,
                    pName, pBarcode, category.text.toString(),
                    "single",
                    finalCaseQty, // Pass the corrected quantity here
                    "single", qty.text.toString(), unitCost.text.toString(),
                    "null", units, locations
                )

                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "✅ Uploaded successfully!", Toast.LENGTH_LONG).show()
                    onStockAdded()
                    dialog?.dismiss()
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "❌ ${e.message}", Toast.LENGTH_LONG).show()
                    Log.e("MyBottomStockSheet", "Submission failed", e)
                }
            } finally {
                withContext(Dispatchers.Main) {
                    if (progressDialog.isShowing) progressDialog.dismiss()
                }
            }
        }
    }

    private fun addUnitView() {
        val inflater = LayoutInflater.from(requireContext())
        val unitView = inflater.inflate(R.layout.unit_of_measure_item, unitContainer, false)

        val unitShelfField = unitView.findViewById<EditText>(R.id.unitShelf) // How many units in case
        val unitQtyField = unitView.findViewById<EditText>(R.id.unitQty)     // The text part, e.g., "50g"
        val btnRemove = unitView.findViewById<Button>(R.id.btnRemoveUnit)

        // --- Session Focus Listener (With Upsert Logic) ---
        var oldDescription: String? = null
        unitQtyField.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                // When user clicks INTO the field, store the current description
                oldDescription = unitQtyField.text.toString().trim()
            } else {
                // When user clicks OUT of the field, perform the update/insert logic
                val unitsInCase = unitShelfField.text.toString().toIntOrNull()
                val newDescription = unitQtyField.text.toString().trim()

                if (unitsInCase != null && newDescription.isNotEmpty()) {
                    // Remove the old entry if the description text was changed
                    if (!oldDescription.isNullOrEmpty() && oldDescription != newDescription) {
                        dynamicUnitsOfMeasure.removeAll { it.description == oldDescription }
                    }

                    // Find an existing item with the new description
                    val existingItem = dynamicUnitsOfMeasure.find { it.description == newDescription }
                    if (existingItem != null) {
                        // PROBLEM 1 FIX: Update existing item's value
                        val index = dynamicUnitsOfMeasure.indexOf(existingItem)
                        dynamicUnitsOfMeasure[index] = UnitOfMeasureItem(newDescription, unitsInCase)
                        Toast.makeText(context, "Updated '$newDescription'.", Toast.LENGTH_SHORT).show()
                    } else {
                        // Insert new item
                        val newUomItem = UnitOfMeasureItem(newDescription, unitsInCase)
                        dynamicUnitsOfMeasure.add(newUomItem)
                        Toast.makeText(context, "Added '$newDescription' to session choices.", Toast.LENGTH_SHORT).show()
                    }

                    uomAdapter.notifyDataSetChanged()
                    // Ensure the spinner selects the item we just added/edited
                    val currentPosition = dynamicUnitsOfMeasure.indexOfFirst { it.description == newDescription }
                    if (currentPosition != -1) {
                        unit_of_measure_populates.setSelection(currentPosition)
                    }
                }
            }
        }
        // Also trigger the "upsert" if the value field is changed
        unitShelfField.onFocusChangeListener = unitQtyField.onFocusChangeListener


        // --- Setup Remove Button (With Crash Fix) ---
        btnRemove.setOnClickListener {
            val descriptionToRemove = unitQtyField.text.toString().trim()
            val currentlySelectedItem = unit_of_measure_populates.selectedItem as? UnitOfMeasureItem

            // 1. Remove the view from the layout.
            unitContainer.removeView(unitView)
            updateUnitCount()

            // 2. If the description is valid, find and remove it from the data source.
            if (descriptionToRemove.isNotEmpty()) {
                val itemToRemove = dynamicUnitsOfMeasure.find { it.description == descriptionToRemove }
                if (itemToRemove != null) {
                    val wasCurrentlySelected = currentlySelectedItem?.description == itemToRemove.description

                    dynamicUnitsOfMeasure.remove(itemToRemove)
                    uomAdapter.notifyDataSetChanged() // Update the adapter with the removed item

                    // PROBLEM 2 FIX: If the item we just removed WAS the selected one,
                    // manually set the selection to the safe placeholder (position 0).
                    if (wasCurrentlySelected) {
                        unit_of_measure_populates.setSelection(0)
                    }

                    Toast.makeText(context, "Removed '$descriptionToRemove' from session choices.", Toast.LENGTH_SHORT).show()
                }
            }
            // 4. Finally, trigger a recalculation. This will now run on a valid spinner selection.
            updateTotalCalculation()
        }

        unitContainer.addView(unitView)
        updateUnitCount()
    }



    private fun updateUnitCount() {
        unitCounterTextView.text = unitContainer.childCount.toString()
    }

    private fun updateLocationCount() {
        locationCounterTextView.text = locationContainer.childCount.toString()
    }

    private fun addUnitLocationView() {
        val locationView = layoutInflater.inflate(R.layout.location_product_item, locationContainer, false)
        val aisleSpinner = locationView.findViewById<Spinner>(R.id.aisle_spinner)
        val rackSpinner = locationView.findViewById<Spinner>(R.id.rack_spinner)
        val shelfSpinner = locationView.findViewById<Spinner>(R.id.shelf_spinner)
        val btnRemove = locationView.findViewById<Button>(R.id.btnRemoveUnit)

        // Find the "add" buttons
        val btnAddAisle = locationView.findViewById<CardView>(R.id.aisle_spinner_add)
        val btnAddRack = locationView.findViewById<CardView>(R.id.rack_spinner_add)
        val btnAddShelf = locationView.findViewById<CardView>(R.id.shelf_spinner_add)

        var thisRowSelectedId: String? = null

        // --- Adapter setup ---
        val aislesWithPlaceholder = mutableListOf("Select Aisle").apply { addAll(dynamicAisles) }
        val racksWithPlaceholder = mutableListOf("Select Rack").apply { addAll(dynamicRacks) }
        val shelvesWithPlaceholder = mutableListOf("Select Shelf").apply { addAll(dynamicShelves) }
        val aisleAdapter = ArrayAdapter(requireContext(), R.layout.spinner_item, aislesWithPlaceholder)
        val rackAdapter = ArrayAdapter(requireContext(), R.layout.spinner_item, racksWithPlaceholder)
        val shelfAdapter = ArrayAdapter(requireContext(), R.layout.spinner_item, shelvesWithPlaceholder)

        aisleAdapter.setDropDownViewResource(R.layout.spinner_item)
        rackAdapter.setDropDownViewResource(R.layout.spinner_item)
        shelfAdapter.setDropDownViewResource(R.layout.spinner_item)

        aisleSpinner.adapter = aisleAdapter
        rackSpinner.adapter = ArrayAdapter(requireContext(), R.layout.spinner_item, listOf("Select Aisle First"))
        shelfSpinner.adapter = ArrayAdapter(requireContext(), R.layout.spinner_item, listOf("Select Rack First"))
        rackSpinner.isEnabled = false
        shelfSpinner.isEnabled = false

        // Set click listeners for the add buttons
        btnAddAisle.setOnClickListener {
            showAddItemDialog("Add New Aisle", aisleAdapter, aisleSpinner)
        }
        btnAddRack.setOnClickListener {
            showAddItemDialog("Add New Rack", rackAdapter, rackSpinner)
        }
        btnAddShelf.setOnClickListener {
            showAddItemDialog("Add New Shelf", shelfAdapter, shelfSpinner)
        }

        val checkLocationAvailability = {
            aisleSpinner.setBackgroundResource(R.drawable.spinner_border)
            rackSpinner.setBackgroundResource(if (rackSpinner.isEnabled) R.drawable.spinner_border else R.drawable.spinner_border_disabled)
            shelfSpinner.setBackgroundResource(if (shelfSpinner.isEnabled) R.drawable.spinner_border else R.drawable.spinner_border_disabled)

            thisRowSelectedId?.let { newlySelectedLocationIds.remove(it) }
            thisRowSelectedId = null

            if (aisleSpinner.selectedItemPosition > 0 && rackSpinner.selectedItemPosition > 0 && shelfSpinner.selectedItemPosition > 0) {
                val aisle = aisleSpinner.selectedItem.toString()
                val rack = rackSpinner.selectedItem.toString()
                val shelf = shelfSpinner.selectedItem.toString()
                val locationKey = "$aisle-$rack-$shelf".lowercase()
                val selectedLocationId = locationNameToIdMap[locationKey]

                if (selectedLocationId != null && (occupiedLocationIds.contains(selectedLocationId) || newlySelectedLocationIds.contains(selectedLocationId))) {
                    aisleSpinner.setBackgroundResource(R.drawable.spinner_border_error)
                    rackSpinner.setBackgroundResource(R.drawable.spinner_border_error)
                    shelfSpinner.setBackgroundResource(R.drawable.spinner_border_error)
                } else if (selectedLocationId != null) {
                    newlySelectedLocationIds.add(selectedLocationId)
                    thisRowSelectedId = selectedLocationId
                }
            }
        }

        aisleSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position > 0) {
                    rackSpinner.isEnabled = true
                    rackSpinner.adapter = rackAdapter
                } else {
                    rackSpinner.isEnabled = false
                    shelfSpinner.isEnabled = false
                    rackSpinner.adapter = ArrayAdapter(requireContext(), R.layout.spinner_item, listOf("Select Aisle First"))
                    shelfSpinner.adapter = ArrayAdapter(requireContext(), R.layout.spinner_item, listOf("Select Rack First"))
                }
                checkLocationAvailability()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        rackSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position > 0) {
                    shelfSpinner.isEnabled = true
                    shelfSpinner.adapter = shelfAdapter
                } else {
                    shelfSpinner.isEnabled = false
                    shelfSpinner.adapter = ArrayAdapter(requireContext(), R.layout.spinner_item, listOf("Select Rack First"))
                }
                checkLocationAvailability()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        shelfSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                checkLocationAvailability()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnRemove.setOnClickListener {
            thisRowSelectedId?.let { newlySelectedLocationIds.remove(it) }
            locationContainer.removeView(locationView)
            updateLocationCount()
        }

        locationContainer.addView(locationView)
        updateLocationCount()
    }

    private fun showAddItemDialog(title: String, adapter: ArrayAdapter<String>, spinner: Spinner?) {
        val input = EditText(requireContext()).apply { hint = "Enter new value" }
        val container = FrameLayout(requireContext())
        val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = (19 * resources.displayMetrics.density).toInt()
            rightMargin = (19 * resources.displayMetrics.density).toInt()
        }
        input.layoutParams = params
        container.addView(input)

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(container)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add", null)
            .create()

        dialog.show()
        val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        positiveButton.isEnabled = false

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val inputText = s.toString().trim()
                positiveButton.isEnabled = inputText.isNotEmpty()
            }
        })

        positiveButton.setOnClickListener {
            val newItemName = input.text.toString().trim()
            val position = adapter.getPosition(newItemName)
            if (position >= 0) {
                Toast.makeText(context, "'$newItemName' already exists in the list.", Toast.LENGTH_SHORT).show()
            } else {
                adapter.add(newItemName)
                adapter.notifyDataSetChanged()
                Toast.makeText(context, "'$newItemName' added to list for this session.", Toast.LENGTH_SHORT).show()
            }
            spinner?.setSelection(adapter.getPosition(newItemName))
            dialog.dismiss()
        }
    }

    private fun showAddNewCategoryDialog() {
        val input = EditText(requireContext()).apply { hint = "Enter new category name" }
        val container = FrameLayout(requireContext())
        val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = (19 * resources.displayMetrics.density).toInt()
            rightMargin = (19 * resources.displayMetrics.density).toInt()
        }
        input.layoutParams = params
        container.addView(input)

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Add New Category")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add", null)
            .create()

        dialog.show()
        val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val inputText = s.toString().trim()
                if (dynamicCategories.any { it.name.equals(inputText, ignoreCase = true) }) {
                    positiveButton.text = "Exists"
                    positiveButton.isEnabled = false
                } else {
                    positiveButton.text = "Add"
                    positiveButton.isEnabled = inputText.isNotEmpty()
                }
            }
        })

        positiveButton.setOnClickListener {
            val categoryName = input.text.toString().trim()
            if (categoryName.isEmpty() || dynamicCategories.any { it.name.equals(categoryName, ignoreCase = true) }) {
                Toast.makeText(context, "Category cannot be empty or already exists", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            progressDialog.setMessage("Adding category '$categoryName'...")
            progressDialog.show()

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val account = GoogleSignIn.getLastSignedInAccount(requireContext()) ?: throw Exception("User not logged in")
                    val sheetsService = getSheetsService(account)
                    val spreadsheetId = findSheetIdByName(getDriveService(account), SPREADSHEET_NAME) ?: throw Exception("Spreadsheet not found")

                    val newCategoryId = "CAT-${UUID.randomUUID().toString().take(8).uppercase()}"
                    val timestamp = getCurrentTimestamp()
                    val newRow = listOf(newCategoryId, categoryName, account.email, timestamp)
                    val body = ValueRange().setValues(listOf(newRow))

                    sheetsService.spreadsheets().values()
                        .append(spreadsheetId, "'$SHEET_CATEGORY'!A1", body)
                        .setValueInputOption("USER_ENTERED").execute()

                    val newCategoryItem = SheetItem(newCategoryId, categoryName)
                    withContext(Dispatchers.Main) {
                        dynamicCategories.add(newCategoryItem)
                        category.text = newCategoryItem.name
                        category.tag = newCategoryItem
                        progressDialog.dismiss()
                        dialog.dismiss()
                        Toast.makeText(context, "Category '$categoryName' added!", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        progressDialog.dismiss()
                        Toast.makeText(context, "Failed to add category: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun getAllUnitsData(): List<Map<String, String>> {
        val unitsList = mutableListOf<Map<String, String>>()
        val mainProductBarcode = barcode.text.toString().trim()

        for (i in 0 until unitContainer.childCount) {
            val view = unitContainer.getChildAt(i)
            val unitBarcodeField = view.findViewById<EditText?>(R.id.unitAisle)
            val priceField = view.findViewById<EditText?>(R.id.unitRack)
            val unitsInCaseField = view.findViewById<EditText?>(R.id.unitShelf)
            val qtyDescriptionField = view.findViewById<EditText?>(R.id.unitQty)
            val costField = view.findViewById<EditText?>(R.id.unit_of_measure_cost)

            val unitSpecificBarcode = unitBarcodeField?.text?.toString()?.trim()
            val sellingPrice = priceField?.text?.toString()?.trim().orEmpty()
            val unitsInCase = unitsInCaseField?.text?.toString()?.trim().orEmpty()
            val qtyDescription = qtyDescriptionField?.text?.toString()?.trim().orEmpty()
            val unitCostValue = costField?.text?.toString()?.trim().orEmpty()

            if (sellingPrice.isNotEmpty() || unitsInCase.isNotEmpty() || qtyDescription.isNotEmpty()) {
                unitsList.add(mapOf(
                    "product_id" to mainProductBarcode,
                    "barcode" to (if (unitSpecificBarcode.isNullOrEmpty()) mainProductBarcode else unitSpecificBarcode),
                    "price" to sellingPrice,
                    "caseType" to unitsInCase,
                    "quantity" to qtyDescription,
                    "cost" to unitCostValue
                ))
            }
        }
        return unitsList
    }

    private fun getAllLocationsData(): List<Map<String, String>> {
        val locationList = mutableListOf<Map<String, String>>()
        for (i in 0 until locationContainer.childCount) {
            val view = locationContainer.getChildAt(i)
            val aisleSpinner = view.findViewById<Spinner?>(R.id.aisle_spinner)
            val rackSpinner = view.findViewById<Spinner?>(R.id.rack_spinner)
            val shelfSpinner = view.findViewById<Spinner?>(R.id.shelf_spinner)

            val aisle = if (aisleSpinner?.selectedItemPosition ?: 0 > 0) aisleSpinner?.selectedItem as? String else ""
            val rack = if (rackSpinner?.selectedItemPosition ?: 0 > 0) rackSpinner?.selectedItem as? String else ""
            val shelf = if (shelfSpinner?.selectedItemPosition ?: 0 > 0) shelfSpinner?.selectedItem as? String else ""

            if (aisle?.isNotEmpty() == true && rack?.isNotEmpty() == true && shelf?.isNotEmpty() == true) {
                locationList.add(mapOf("Aisle" to aisle, "Rack" to rack, "Shelf" to shelf))
            }
        }
        return locationList
    }

    private fun pickImageFromGallery() {
        val intent = Intent(Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        pickImageLauncher.launch(intent)
    }

    private fun showCategoryDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_category_selector, null)
        val searchBox = dialogView.findViewById<EditText>(R.id.searchField)
        val listView = dialogView.findViewById<ListView>(R.id.listViewItems)
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, dynamicCategories)
        listView.adapter = adapter
        val dialog = AlertDialog.Builder(requireContext()).setView(dialogView).create()
        searchBox.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { adapter.filter.filter(s) }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        listView.setOnItemClickListener { _, _, position, _ ->
            val selected = adapter.getItem(position)
            category.text = selected?.name
            category.tag = selected
            dialog.dismiss()
        }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            val selected = adapter.getItem(position) ?: return@setOnItemLongClickListener true
            val options = arrayOf("Edit", "Delete")
            AlertDialog.Builder(requireContext())
                .setTitle("Manage Category")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> showEditCategoryDialog(position, selected, adapter)
                        1 -> showDeleteCategoryDialog(selected, adapter)
                    }
                }.show()
            true
        }
        dialog.show()
    }

    private fun showEditCategoryDialog(position: Int, oldItem: SheetItem, adapter: ArrayAdapter<SheetItem>) {
        Toast.makeText(context, "Edit functionality is not fully implemented yet.", Toast.LENGTH_SHORT).show()
    }

    private fun showDeleteCategoryDialog(item: SheetItem, adapter: ArrayAdapter<SheetItem>) {
        Toast.makeText(context, "Delete functionality is not fully implemented yet.", Toast.LENGTH_SHORT).show()
    }

    private suspend fun uploadImageAndWriteToSheet(
        account: GoogleSignInAccount, uri: Uri, spreadsheetName: String, sheetTabName: String, email: String,
        prodName: String, barcode: String, categoryName: String, unit: String,
        caseQty: String,
        minOrder: String, qty: String, unitCost: String, unitSelling: String,
        units: List<Map<String, String>>, locations: List<Map<String, String>>
    ) {
        withContext(Dispatchers.IO) {
            try {
                val driveService = getDriveService(account)
                val sheetsService = getSheetsService(account)
                val spreadsheetId = findSheetIdByName(driveService, spreadsheetName)
                    ?: throw Exception("Spreadsheet '$spreadsheetName' not found")

                ensureSheetExists(sheetsService, spreadsheetId, SHEET_TAB_NAME)
                ensureSheetExists(sheetsService, spreadsheetId, SHEET_UNITS)
                ensureSheetExists(sheetsService, spreadsheetId, SHEET_LOCATIONS)
                ensureSheetExists(sheetsService, spreadsheetId, SHEET_CATEGORY)

                val folderId = getOrCreateNiaBridgeFolder(driveService)
                    ?: throw Exception("Folder '$FOLDER_NAME' not found")

                val imageFile = createTempFileFromUri(uri)
                val fileMetadata = File().apply {
                    name = imageFile.name; mimeType = "image/jpeg"; parents = listOf(folderId)
                }
                val mediaContent = FileContent("image/jpeg", imageFile)
                val uploadedFile = driveService.files().create(fileMetadata, mediaContent).setFields("id").execute()
                val publicUrl = "https://drive.google.com/uc?id=${uploadedFile.id}"

                val timestamp = getCurrentTimestamp()

                val selectedCategory = withContext(Dispatchers.Main) { category.tag as? SheetItem }
                val finalCategoryId: String = if (selectedCategory != null) {
                    selectedCategory.id
                } else if (categoryName.isNotBlank()) {
                    val newCategoryId = "CAT-${UUID.randomUUID().toString().take(8).uppercase()}"
                    val categoryRow = listOf(newCategoryId, categoryName, email, timestamp)
                    sheetsService.spreadsheets().values()
                        .append(spreadsheetId, "'$SHEET_CATEGORY'!A1", ValueRange().setValues(listOf(categoryRow)))
                        .setValueInputOption("USER_ENTERED").execute()
                    newCategoryId
                } else {
                    ""
                }

                val locationIds = mutableListOf<String>()
                if (locations.isNotEmpty()) {
                    val locationRows = mutableListOf<List<Any>>()
                    val existingLocations = locationNameToIdMap.keys.map { it.lowercase() }

                    locations.forEach { loc ->
                        val locKey = "${loc["Aisle"]}-${loc["Rack"]}-${loc["Shelf"]}".lowercase()
                        var locationId = locationNameToIdMap[locKey]
                        if (locationId == null && !existingLocations.contains(locKey)) {
                            locationId = "LOC-${UUID.randomUUID().toString().take(8).uppercase()}"
                            val newLocRow = listOf(locationId, loc["Aisle"] ?: "", loc["Rack"] ?: "", loc["Shelf"] ?: "", timestamp, email)
                            locationRows.add(newLocRow)
                            locationNameToIdMap[locKey] = locationId
                        }
                        if (locationId != null) {
                            locationIds.add(locationId)
                        }
                    }
                    if (locationRows.isNotEmpty()) {
                        sheetsService.spreadsheets().values()
                            .append(spreadsheetId, "'$SHEET_LOCATIONS'!A1", ValueRange().setValues(locationRows))
                            .setValueInputOption("USER_ENTERED").execute()
                    }
                }

                val productId = "PROD-${UUID.randomUUID().toString().take(8).uppercase()}"
                val locationIdsString = locationIds.joinToString(prefix = "['", postfix = "']", separator = "', '")
                val productRow = listOf<Any>(
                    productId, prodName, publicUrl, barcode, finalCategoryId, unit,
                    caseQty, minOrder, unitCost, locationIdsString, email, timestamp
                )
                sheetsService.spreadsheets().values()
                    .append(spreadsheetId, "'$SHEET_TAB_NAME'!A1", ValueRange().setValues(listOf(productRow)))
                    .setValueInputOption("USER_ENTERED").execute()

                if (units.isNotEmpty()) {
                    val unitRows = units.map { u ->
                        listOf(
                            u["product_id"] ?: "",
                            u["barcode"] ?: "",
                            u["price"] ?: "",
                            u["caseType"] ?: "",
                            u["quantity"] ?: "",
                            u["cost"] ?: "",
                            email,
                            timestamp
                        )
                    }
                    sheetsService.spreadsheets().values()
                        .append(spreadsheetId, "'$SHEET_UNITS'!A1", ValueRange().setValues(unitRows))
                        .setValueInputOption("USER_ENTERED").execute()
                }

            } catch (e: Exception) {
                Log.e("GoogleAPI", "Error in uploadImageAndWriteToSheet: ${e.message}", e)
                throw e
            }
        }
    }

    private fun getCurrentTimestamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

    private fun getDriveService(account: GoogleSignInAccount): Drive {
        val credential = GoogleAccountCredential.usingOAuth2(requireContext(), listOf(DriveScopes.DRIVE))
            .apply { selectedAccountName = account.email }
        return Drive.Builder(GoogleNetHttpTransport.newTrustedTransport(), GsonFactory.getDefaultInstance(), credential)
            .setApplicationName("Nia Bridge App").build()
    }

    private fun getSheetsService(account: GoogleSignInAccount): Sheets {
        val credential = GoogleAccountCredential.usingOAuth2(requireContext(), listOf(SheetsScopes.SPREADSHEETS))
            .apply { selectedAccountName = account.email }
        return Sheets.Builder(GoogleNetHttpTransport.newTrustedTransport(), GsonFactory.getDefaultInstance(), credential)
            .setApplicationName("Nia Bridge App").build()
    }

    private suspend fun findSheetIdByName(driveService: Drive, name: String): String? = withContext(Dispatchers.IO) {
        val query = "name='$name' and mimeType='application/vnd.google-apps.spreadsheet' and trashed=false"
        val result = driveService.files().list().setQ(query).setSpaces("drive").setCorpus("user")
            .setFields("files(id, owners, shared)").execute()
        result.files.firstOrNull { file -> (file.owners?.any { it.me == true } == true) || (file.shared == true) }?.id
    }

    private suspend fun getOrCreateNiaBridgeFolder(driveService: Drive): String? = withContext(Dispatchers.IO) {
        val query = "name='$FOLDER_NAME' and mimeType='application/vnd.google-apps.folder' and trashed=false"
        val result = driveService.files().list().setQ(query).setSpaces("drive").setCorpus("user")
            .setFields("files(id, owners, shared)").execute()
        val accessibleFolder = result.files.firstOrNull { file -> (file.owners?.any { it.me == true } == true) || (file.shared == true) }
        if (accessibleFolder != null) return@withContext accessibleFolder.id
        if (parentEmail == null) {
            val folderMetadata = File().apply { this.name = FOLDER_NAME; mimeType = "application/vnd.google-apps.folder" }
            val folder = driveService.files().create(folderMetadata).setFields("id").execute()
            return@withContext folder.id
        }
        return@withContext null
    }

    private suspend fun ensureSheetExists(sheetsService: Sheets, spreadsheetId: String, sheetName: String) {
        val spreadsheet = sheetsService.spreadsheets().get(spreadsheetId).execute()
        if (!spreadsheet.sheets.any { it.properties.title == sheetName }) {
            val addSheetRequest = AddSheetRequest().setProperties(SheetProperties().setTitle(sheetName))
            val batchRequest = BatchUpdateSpreadsheetRequest().setRequests(listOf(Request().setAddSheet(addSheetRequest)))
            sheetsService.spreadsheets().batchUpdate(spreadsheetId, batchRequest).execute()

            val headers = when (sheetName) {
                SHEET_TAB_NAME -> listOf("Product_ID", "Product Name", "Images", "Barcode", "Category_ID", "Unit", "Case Quantity", "Minimum Order", "Unit Cost", "Location_IDs", "Updated by", "Timestamp")
                SHEET_LOCATIONS -> listOf("Location_ID", "Aisle", "Rack", "Shelf", "Timestamp", "Updated By")
                SHEET_UNITS -> listOf("Product_ID", "Barcode", "Selling Price", "Case Units", "Quantity Description", "Cost", "Updated By", "Timestamp")
                SHEET_CATEGORY -> listOf("Category_ID", "Category Name", "Created By", "Timestamp")
                else -> emptyList()
            }
            if (headers.isNotEmpty()) {
                val body = ValueRange().setValues(listOf(headers))
                sheetsService.spreadsheets().values().update(spreadsheetId, "$sheetName!A1", body).setValueInputOption("RAW").execute()
            }
        }
    }

    private fun createTempFileFromUri(uri: Uri): java.io.File {
        val inputStream: InputStream = requireContext().contentResolver.openInputStream(uri)!!
        val tempFile = java.io.File.createTempFile("upload_", ".jpg", requireContext().cacheDir)
        FileOutputStream(tempFile).use { outputStream -> inputStream.copyTo(outputStream) }
        inputStream.close()
        return tempFile
    }
}
