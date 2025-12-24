package com.example.zed

import android.app.ProgressDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log // Keep this import for logging
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.cardview.widget.CardView
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.SheetsScopes
import com.google.api.services.sheets.v4.model.*
import com.example.zed.MyBottomStockSheet.UnitOfMeasureItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class location_and_uom : Fragment() {

    companion object {
        private const val SPREADSHEET_NAME = "nia-bridge data"
        private const val SHEET_PRODUCTS = "Products"
        private const val SHEET_LOCATIONS = "product_location"
        private const val SHEET_UNITS = "unit_measure"
        private const val TAG = "LocationUomFragment"
    }

    // --- All your existing variables ---
    private lateinit var quantity_display: EditText
    private lateinit var qty: EditText
    private val dynamicAisles = mutableListOf<String>()
    private val dynamicRacks = mutableListOf<String>()
    private val dynamicShelves = mutableListOf<String>()
    private lateinit var unitContainer: LinearLayout
    private lateinit var btnAddUnit: CardView
    private val dynamicUnitsOfMeasure = mutableListOf<UnitOfMeasureItem>()
    private lateinit var uomAdapter: ArrayAdapter<UnitOfMeasureItem>
    private lateinit var locationContainer: LinearLayout
    private lateinit var location_add: CardView
    private lateinit var locationCounterTextView: TextView
    private lateinit var unitCounterTextView: TextView
    private lateinit var unit_of_measure_populates: Spinner
    private val locationNameToIdMap = mutableMapOf<String, String>()

    // These sets will now be managed correctly
    private val occupiedLocationIds = mutableSetOf<String>()
    private val newlySelectedLocationIds = mutableSetOf<String>()

    private lateinit var progressDialog: ProgressDialog
    private lateinit var btnSaveChanges: CardView

    private val sharedViewModel: SharedViewModel by activityViewModels()

    private var pendingProductData: SelectedProductData? = null
    private var isDynamicDataLoaded = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_location_and_uom, container, false)
        initializeViews(view)
        setupUomSpinner()
        setupListeners()
        observeViewModel()
        fetchDynamicData()
        return view
    }

    private fun initializeViews(view: View) {
        locationContainer = view.findViewById(R.id.locationContainer)
        location_add = view.findViewById(R.id.location_add)
        locationCounterTextView = view.findViewById(R.id.location_counter)
        unitCounterTextView = view.findViewById(R.id.unit_counter)
        unit_of_measure_populates = view.findViewById(R.id.unit_of_measure_populates)
        unitContainer = view.findViewById(R.id.unitContainer)
        btnAddUnit = view.findViewById(R.id.btnAddUnit)
        qty = view.findViewById(R.id.qty)
        quantity_display = view.findViewById(R.id.quantity_display)
        btnSaveChanges = view.findViewById(R.id.btnSaveChanges)

        locationCounterTextView.text = "0"
        unitCounterTextView.text = "0"
        progressDialog = ProgressDialog(requireContext()).apply {
            setCancelable(false)
        }
    }

    private fun observeViewModel() {
        sharedViewModel.selectedProductData.observe(viewLifecycleOwner) { data ->
            pendingProductData = data
            if (isDynamicDataLoaded) {
                updateUiFromData(data)
            }
        }
    }

    private fun updateUiFromData(data: SelectedProductData?) {
        if (data != null) {
            populateUiWithData(data)
        } else {
            clearAllViews()
        }
    }

    private fun populateUiWithData(data: SelectedProductData) {
        clearAllViews()

        occupiedLocationIds.clear()
        newlySelectedLocationIds.clear()
        occupiedLocationIds.addAll(data.product.locationIds)

        for (unit in data.units) { addUnitView(unit, isNew = false) }
        for (location in data.locations) { addUnitLocationView(location, isNew = false) }

        dynamicUnitsOfMeasure.clear()
        val spinnerItems = data.units.mapNotNull {
            it.caseUnits.toIntOrNull()?.let { caseUnits -> UnitOfMeasureItem(it.quantityDescription, caseUnits) }
        }
        dynamicUnitsOfMeasure.addAll(spinnerItems)
        uomAdapter.notifyDataSetChanged()

        qty.setText(data.product.caseQty)
        updateTotalCalculation()
    }

    private fun clearAllViews() {
        locationContainer.removeAllViews()
        unitContainer.removeAllViews()
        updateLocationCount()
        updateUnitCount()
        dynamicUnitsOfMeasure.clear()
        uomAdapter.notifyDataSetChanged()
        qty.setText("")
        quantity_display.setText("")
    }

    private fun setupUomSpinner() {
        uomAdapter = ArrayAdapter(requireContext(), R.layout.spinner_item, dynamicUnitsOfMeasure)
        uomAdapter.setDropDownViewResource(R.layout.spinner_item)
        unit_of_measure_populates.adapter = uomAdapter
    }

    private fun setupListeners() {
        btnAddUnit.setOnClickListener { addUnitView(null, isNew = true) }
        location_add.setOnClickListener { addUnitLocationView(null, isNew = true) }
        qty.addTextChangedListener(mainCalculationWatcher)
        unit_of_measure_populates.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateTotalCalculation()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {
                quantity_display.setText("0")
            }
        }
        btnSaveChanges.setOnClickListener { handleSaveChanges() }
    }

    private val mainCalculationWatcher: TextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) { updateTotalCalculation() }
    }

    private fun updateTotalCalculation() {
        val selectedItem = unit_of_measure_populates.selectedItem as? UnitOfMeasureItem
        val selectedUnitValue = selectedItem?.value?.toDouble() ?: 0.0
        val quantityOfCases = qty.text.toString().toDoubleOrNull() ?: 0.0
        val grandTotal = selectedUnitValue * quantityOfCases
        quantity_display.setText(String.format("%.0f", grandTotal))
    }

    private fun addUnitView(unit: UnitOfMeasure?, isNew: Boolean) {
        val inflater = LayoutInflater.from(requireContext())
        val unitView = inflater.inflate(R.layout.unit_of_measure_item, unitContainer, false)

        // ✅ Store the original UOM object in the tag to preserve its data (like sellingPrice)
        // If it's a new row, the tag will be null initially.
        unitView.tag = unit

        val descriptionField = unitView.findViewById<EditText>(R.id.unitQty)
        val caseUnitsField = unitView.findViewById<EditText>(R.id.unitShelf)
        val btnRemove = unitView.findViewById<Button>(R.id.btnRemoveUnit)

        unit?.let {
            descriptionField.setText(it.quantityDescription)
            caseUnitsField.setText(it.caseUnits)
        }

        var oldDescription: String? = null
        val focusListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                oldDescription = descriptionField.text.toString().trim()
            } else {
                val unitsInCase = caseUnitsField.text.toString().toIntOrNull()
                val newDescription = descriptionField.text.toString().trim()
                if (unitsInCase != null && newDescription.isNotEmpty()) {
                    if (!oldDescription.isNullOrEmpty() && oldDescription != newDescription) {
                        dynamicUnitsOfMeasure.removeAll { it.description == oldDescription }
                    }
                    val existingItem = dynamicUnitsOfMeasure.find { it.description == newDescription }
                    if (existingItem != null) {
                        dynamicUnitsOfMeasure[dynamicUnitsOfMeasure.indexOf(existingItem)] = UnitOfMeasureItem(newDescription, unitsInCase)
                    } else {
                        dynamicUnitsOfMeasure.add(UnitOfMeasureItem(newDescription, unitsInCase))
                    }
                    uomAdapter.notifyDataSetChanged()
                    val currentPosition = dynamicUnitsOfMeasure.indexOfFirst { it.description == newDescription }
                    if (currentPosition != -1) {
                        unit_of_measure_populates.setSelection(currentPosition)
                    }
                }
            }
        }
        descriptionField.onFocusChangeListener = focusListener
        caseUnitsField.onFocusChangeListener = focusListener

        btnRemove.setOnClickListener {
            unitContainer.removeView(unitView)
            updateUnitCount()
        }
        unitContainer.addView(unitView)
        updateUnitCount()
    }

    private fun addUnitLocationView(location: Location?, isNew: Boolean) {
        val locationView = layoutInflater.inflate(R.layout.location_product_item, locationContainer, false)
        if (isNew) locationView.tag = "new"

        val aisleSpinner = locationView.findViewById<Spinner>(R.id.aisle_spinner)
        val rackSpinner = locationView.findViewById<Spinner>(R.id.rack_spinner)
        val shelfSpinner = locationView.findViewById<Spinner>(R.id.shelf_spinner)
        val btnRemove = locationView.findViewById<Button>(R.id.btnRemoveUnit)
        val btnAddAisle = locationView.findViewById<CardView>(R.id.aisle_spinner_add)
        val btnAddRack = locationView.findViewById<CardView>(R.id.rack_spinner_add)
        val btnAddShelf = locationView.findViewById<CardView>(R.id.shelf_spinner_add)
        var thisRowSelectedId: String? = location?.id

        thisRowSelectedId?.let { occupiedLocationIds.remove(it) }

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

        fun updateRackSpinner(selectedAislePosition: Int) {
            if (selectedAislePosition > 0) {
                rackSpinner.isEnabled = true
                rackSpinner.adapter = rackAdapter
            } else {
                rackSpinner.isEnabled = false
                shelfSpinner.isEnabled = false
                rackSpinner.adapter = ArrayAdapter(requireContext(), R.layout.spinner_item, listOf("Select Aisle First"))
                shelfSpinner.adapter = ArrayAdapter(requireContext(), R.layout.spinner_item, listOf("Select Rack First"))
            }
        }

        fun updateShelfSpinner(selectedRackPosition: Int) {
            if (selectedRackPosition > 0) {
                shelfSpinner.isEnabled = true
                shelfSpinner.adapter = shelfAdapter
            } else {
                shelfSpinner.isEnabled = false
                shelfSpinner.adapter = ArrayAdapter(requireContext(), R.layout.spinner_item, listOf("Select Rack First"))
            }
        }

        location?.let {
            val aislePos = aislesWithPlaceholder.indexOf(it.aisle)
            if (aislePos > 0) {
                aisleSpinner.setSelection(aislePos)
                updateRackSpinner(aislePos)
                val rackPos = racksWithPlaceholder.indexOf(it.rack)
                if (rackPos > 0) {
                    rackSpinner.setSelection(rackPos)
                    updateShelfSpinner(rackPos)
                    val shelfPos = shelvesWithPlaceholder.indexOf(it.shelf)
                    if (shelfPos > 0) {
                        shelfSpinner.setSelection(shelfPos)
                    }
                }
            }
        }

        btnAddAisle.setOnClickListener { showAddItemDialog("Add New Aisle", aisleAdapter, aisleSpinner) }
        btnAddRack.setOnClickListener { showAddItemDialog("Add New Rack", rackAdapter, rackSpinner) }
        btnAddShelf.setOnClickListener { showAddItemDialog("Add New Shelf", shelfAdapter, shelfSpinner) }

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
                updateRackSpinner(position)
                checkLocationAvailability()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        rackSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateShelfSpinner(position)
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
            thisRowSelectedId?.let {
                newlySelectedLocationIds.remove(it)
                if (!isNew) occupiedLocationIds.add(it)
            }
            locationContainer.removeView(locationView)
            updateLocationCount()
        }
        locationContainer.addView(locationView)
        updateLocationCount()
    }

    private fun updateUnitCount() {
        unitCounterTextView.text = unitContainer.childCount.toString()
    }

    private fun updateLocationCount() {
        locationCounterTextView.text = locationContainer.childCount.toString()
    }

    // --- START OF SAVE LOGIC ---
    private fun handleSaveChanges() {
        val currentProductData = sharedViewModel.selectedProductData.value ?: run {
            Toast.makeText(requireContext(), "No product selected to save.", Toast.LENGTH_SHORT).show()
            return
        }

        progressDialog.setMessage("Saving changes...")
        progressDialog.show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val account = GoogleSignIn.getLastSignedInAccount(requireContext())
                    ?: throw IllegalStateException("User not signed in.")
                val sheetsService = getSheetsService(account)
                val spreadsheetId = findSheetIdByName(getDriveService(account), SPREADSHEET_NAME)
                    ?: throw IllegalStateException("Spreadsheet not found.")

                val productRowIndex = findProductRowIndex(sheetsService, spreadsheetId, currentProductData.product.id)
                if (productRowIndex == -1) throw IllegalStateException("Could not find the product in the sheet to update.")

                // --- Location Logic ---
                val newLocations = getNewLocationDataFromUi()
                val newLocationIds = addNewLocationsToSheet(sheetsService, spreadsheetId, newLocations, account)
                val finalLocationIds = gatherFinalLocationIdsFromUi(newLocationIds)

                // --- UOM Logic (Delete then Re-add) ---
                val allCurrentUoms = gatherFinalUomDataFromUi()
                deleteAllUomsForProduct(sheetsService, spreadsheetId, currentProductData.product.barcode)
                addNewUnitsToSheet(sheetsService, spreadsheetId, allCurrentUoms, currentProductData.product.barcode, account)
                // --- End UOM Logic ---

                val mainUom = (unit_of_measure_populates.selectedItem as? UnitOfMeasureItem)?.description ?: ""
                val updatedValues = listOf(
                    currentProductData.product.name,
                    currentProductData.product.imageUrl,
                    currentProductData.product.barcode,
                    currentProductData.product.categoryId,
                    mainUom,
                    qty.text.toString(),
                    currentProductData.product.minOrder,
                    currentProductData.product.unitCost,
                    finalLocationIds.joinToString(prefix = "['", postfix = "']", separator = "', '"),
                    account.email ?: "Unknown",
                    getCurrentTimestamp()
                )

                Log.d(TAG, "Updating product row $productRowIndex with values: $updatedValues")

                val valueRange = ValueRange().setValues(listOf(updatedValues))
                sheetsService.spreadsheets().values()
                    .update(spreadsheetId, "$SHEET_PRODUCTS!B$productRowIndex", valueRange)
                    .setValueInputOption("USER_ENTERED")
                    .execute()

                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(requireContext(), "Product updated successfully!", Toast.LENGTH_LONG).show()
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Log.e(TAG, "Error updating product", e)
                    Toast.makeText(requireContext(), "Error saving changes: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private suspend fun gatherFinalLocationIdsFromUi(newLocationIds: List<String>): List<String> {
        val finalLocationIds = mutableListOf<String>()
        withContext(Dispatchers.Main) { // Must read UI on Main thread
            Log.d(TAG, "Reading final locations from UI...")
            for (i in 0 until locationContainer.childCount) {
                val view = locationContainer.getChildAt(i)
                val aisleSpinner = view.findViewById<Spinner>(R.id.aisle_spinner)
                val rackSpinner = view.findViewById<Spinner>(R.id.rack_spinner)
                val shelfSpinner = view.findViewById<Spinner>(R.id.shelf_spinner)

                if (aisleSpinner.selectedItemPosition > 0 && rackSpinner.selectedItemPosition > 0 && shelfSpinner.selectedItemPosition > 0) {
                    val aisle = aisleSpinner.selectedItem.toString()
                    val rack = rackSpinner.selectedItem.toString()
                    val shelf = shelfSpinner.selectedItem.toString()
                    val key = "$aisle-$rack-$shelf".lowercase()
                    locationNameToIdMap[key]?.let { id ->
                        finalLocationIds.add(id)
                        Log.d(TAG, "Found existing location ID '$id' for key '$key'")
                    }
                }
            }
        }
        finalLocationIds.addAll(newLocationIds)
        val distinctFinalIds = finalLocationIds.distinct()
        Log.d(TAG, "Final list of location IDs to be saved: $distinctFinalIds")
        return distinctFinalIds
    }

    // ✅ FIXED: Gathers ALL UOMs from the UI, preserving existing data
    private suspend fun gatherFinalUomDataFromUi(): List<Map<String, String>> {
        val allUnits = mutableListOf<Map<String, String>>()
        withContext(Dispatchers.Main) { // Must read UI on Main thread
            Log.d(TAG, "Starting to gather ALL Unit of Measure data from UI...")
            for (i in 0 until unitContainer.childCount) {
                val view = unitContainer.getChildAt(i)
                val originalUnit = view.tag as? UnitOfMeasure // Get the original data from the tag

                val descriptionField = view.findViewById<EditText>(R.id.unitQty)
                val caseUnitsField = view.findViewById<EditText>(R.id.unitShelf)
                val desc = descriptionField.text.toString().trim()
                val caseUnits = caseUnitsField.text.toString().trim()

                if (desc.isNotEmpty() && caseUnits.isNotEmpty()) {
                    // Start with original data, then overwrite with edited fields
                    val unitMap = mutableMapOf(
                        "description" to desc,
                        "caseUnits" to caseUnits,
                        "sellingPrice" to (originalUnit?.sellingPrice ?: ""),
                        "cost" to (originalUnit?.cost ?: ""),
                        "barcode" to (originalUnit?.unitBarcode ?: "")
                    )
                    allUnits.add(unitMap)
                    Log.d(TAG, "Gathered UOM row #${i}: $unitMap")
                } else {
                    Log.d(TAG, "Skipping UOM row #${i} because it's incomplete.")
                }
            }
        }
        if (allUnits.isEmpty()) {
            Log.d(TAG, "No valid UOMs were found in the UI to save.")
        }
        return allUnits
    }


    private fun getNewLocationDataFromUi(): List<Map<String, String>> {
        val newLocations = mutableListOf<Map<String, String>>()
        // This function now only handles BRAND NEW locations that need to be added to the location sheet
        for (i in 0 until locationContainer.childCount) {
            val view = locationContainer.getChildAt(i)
            if (view.tag == "new") {
                val aisleSpinner = view.findViewById<Spinner>(R.id.aisle_spinner)
                val rackSpinner = view.findViewById<Spinner>(R.id.rack_spinner)
                val shelfSpinner = view.findViewById<Spinner>(R.id.shelf_spinner)

                if (aisleSpinner.selectedItemPosition > 0 && rackSpinner.selectedItemPosition > 0 && shelfSpinner.selectedItemPosition > 0) {
                    val locationMap = mapOf(
                        "Aisle" to aisleSpinner.selectedItem.toString(),
                        "Rack" to rackSpinner.selectedItem.toString(),
                        "Shelf" to shelfSpinner.selectedItem.toString()
                    )
                    newLocations.add(locationMap)
                }
            }
        }
        return newLocations
    }

    private suspend fun addNewLocationsToSheet(sheetsService: Sheets, spreadsheetId: String, locations: List<Map<String, String>>, account: GoogleSignInAccount): List<String> {
        if (locations.isEmpty()) {
            Log.d(TAG, "addNewLocationsToSheet: No new locations to add. Skipping append.")
            return emptyList()
        }
        val newLocationIds = mutableListOf<String>()
        val locationRows = mutableListOf<List<Any>>()
        val timestamp = getCurrentTimestamp()
        val user = account.email ?: "Unknown"

        locations.forEach { loc ->
            val newId = "LOC-${UUID.randomUUID().toString().take(8).uppercase()}"
            newLocationIds.add(newId)
            locationRows.add(listOf(newId, loc["Aisle"]!!, loc["Rack"]!!, loc["Shelf"]!!, timestamp, user))
        }

        Log.d(TAG, "Appending ${locationRows.size} new rows to '$SHEET_LOCATIONS' sheet: $locationRows")
        val body = ValueRange().setValues(locationRows)
        sheetsService.spreadsheets().values()
            .append(spreadsheetId, "$SHEET_LOCATIONS!A1", body)
            .setValueInputOption("USER_ENTERED")
            .execute()
        return newLocationIds
    }

    // ✅ FIXED: Deletes all UOM rows for a specific product barcode
    private suspend fun deleteAllUomsForProduct(sheetsService: Sheets, spreadsheetId: String, productBarcode: String) {
        Log.d(TAG, "Attempting to delete all existing UOMs for barcode: $productBarcode")
        val sheetId = getSheetId(sheetsService, spreadsheetId, SHEET_UNITS)
            ?: throw IllegalStateException("Could not find sheet ID for '$SHEET_UNITS'")

        // 1. Find all rows that match the barcode
        val range = "$SHEET_UNITS!A:A" // Assuming product barcode is in column A
        val response = sheetsService.spreadsheets().values().get(spreadsheetId, range).execute()
        val values = response.getValues() ?: return // No UOMs exist, nothing to delete

        val deleteRequests = mutableListOf<Request>()
        // Iterate in reverse to avoid shifting indices
        for (i in values.indices.reversed()) {
            val row = values[i]
            if (row.isNotEmpty() && row[0].toString().trim() == productBarcode.trim()) {
                val rowIndex = i
                val deleteRequest = Request().setDeleteDimension(
                    DeleteDimensionRequest()
                        .setRange(
                            DimensionRange()
                                .setSheetId(sheetId)
                                .setDimension("ROWS")
                                .setStartIndex(rowIndex)
                                .setEndIndex(rowIndex + 1)
                        )
                )
                deleteRequests.add(deleteRequest)
                Log.d(TAG, "Prepared to delete UOM row at index: $rowIndex for barcode $productBarcode")
            }
        }

        // 2. Batch delete all found rows
        if (deleteRequests.isNotEmpty()) {
            Log.d(TAG, "Executing batch delete for ${deleteRequests.size} UOM rows.")
            val batchUpdateRequest = BatchUpdateSpreadsheetRequest().setRequests(deleteRequests)
            sheetsService.spreadsheets().batchUpdate(spreadsheetId, batchUpdateRequest).execute()
        } else {
            Log.d(TAG, "No existing UOMs found for barcode $productBarcode to delete.")
        }
    }


    private suspend fun addNewUnitsToSheet(sheetsService: Sheets, spreadsheetId: String, units: List<Map<String, String>>, mainBarcode: String, account: GoogleSignInAccount) {
        if (units.isEmpty()) {
            Log.d(TAG, "addNewUnitsToSheet: No new units to add. Skipping append.")
            return
        }
        val unitRows = units.map { u ->
            listOf(
                mainBarcode,
                u["barcode"] ?: mainBarcode,
                u["sellingPrice"] ?: "",
                u["caseUnits"] ?: "",
                u["description"] ?: "",
                u["cost"] ?: "",
                account.email ?: "Unknown",
                getCurrentTimestamp()
            )
        }

        Log.d(TAG, "Appending ${unitRows.size} new rows to '$SHEET_UNITS' sheet: $unitRows")
        val body = ValueRange().setValues(unitRows)
        sheetsService.spreadsheets().values()
            .append(spreadsheetId, "$SHEET_UNITS!A1", body)
            .setValueInputOption("USER_ENTERED")
            .execute()
    }

    private suspend fun findProductRowIndex(sheetsService: Sheets, spreadsheetId: String, productId: String): Int {
        val range = "$SHEET_PRODUCTS!A:A"
        val response = sheetsService.spreadsheets().values().get(spreadsheetId, range).execute()
        val values = response.getValues() ?: return -1
        for ((index, row) in values.drop(1).withIndex()) { // drop(1) to skip header
            if (row.isNotEmpty() && row[0].toString() == productId) {
                return index + 2 // +1 for 1-based index, +1 for dropped header
            }
        }
        return -1
    }

    // ✅ FIXED: Helper to get the numeric ID of a sheet by its name
    private suspend fun getSheetId(sheetsService: Sheets, spreadsheetId: String, sheetName: String): Int? {
        return withContext(Dispatchers.IO) {
            try {
                val spreadsheet = sheetsService.spreadsheets().get(spreadsheetId).setFields("sheets.properties").execute()
                spreadsheet.sheets.firstOrNull { it.properties.title == sheetName }?.properties?.sheetId
            } catch (e: Exception) {
                Log.e(TAG, "Could not get sheet ID for '$sheetName'", e)
                null
            }
        }
    }


    private fun fetchDynamicData() {
        progressDialog.setMessage("Loading available locations...")
        progressDialog.show()
        isDynamicDataLoaded = false
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val account = GoogleSignIn.getLastSignedInAccount(requireContext())
                    ?: throw IllegalStateException("User not signed in")
                val sheetsService = getSheetsService(account)
                val spreadsheetId = findSheetIdByName(getDriveService(account), SPREADSHEET_NAME)
                    ?: throw IllegalStateException("Spreadsheet not found")

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

                withContext(Dispatchers.Main) {
                    dynamicAisles.clear(); dynamicAisles.addAll(aislesFromSheet.sorted())
                    dynamicRacks.clear(); dynamicRacks.addAll(racksFromSheet.sorted())
                    dynamicShelves.clear(); dynamicShelves.addAll(shelvesFromSheet.sorted())
                    locationNameToIdMap.clear(); locationNameToIdMap.putAll(tempLocationMap)
                    progressDialog.dismiss()
                    isDynamicDataLoaded = true
                    updateUiFromData(pendingProductData)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    isDynamicDataLoaded = true
                    Toast.makeText(context, "Failed to load location data: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
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
            if (!adapter.isEmpty && adapter.getPosition(newItemName) >= 0) {
                Toast.makeText(context, "'$newItemName' already exists.", Toast.LENGTH_SHORT).show()
            } else {
                adapter.add(newItemName)
                adapter.sort(Comparator.naturalOrder())
                adapter.notifyDataSetChanged()
                Toast.makeText(context, "'$newItemName' added for this session.", Toast.LENGTH_SHORT).show()
            }
            spinner?.setSelection(adapter.getPosition(newItemName))
            dialog.dismiss()
        }
    }

    // --- Google API Helper Functions ---
    private fun getCurrentTimestamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

    private fun getSheetsService(account: GoogleSignInAccount): Sheets {
        val credential = GoogleAccountCredential.usingOAuth2(requireContext(), listOf(SheetsScopes.SPREADSHEETS))
            .apply { selectedAccountName = account.email }
        return Sheets.Builder(GoogleNetHttpTransport.newTrustedTransport(), GsonFactory.getDefaultInstance(), credential)
            .setApplicationName("Nia Bridge App").build()
    }

    private fun getDriveService(account: GoogleSignInAccount): Drive {
        val credential = GoogleAccountCredential.usingOAuth2(requireContext(), listOf(DriveScopes.DRIVE))
            .apply { selectedAccountName = account.email }
        return Drive.Builder(GoogleNetHttpTransport.newTrustedTransport(), GsonFactory.getDefaultInstance(), credential)
            .setApplicationName("Nia Bridge App").build()
    }

    private suspend fun findSheetIdByName(driveService: Drive, name: String): String? = withContext(Dispatchers.IO) {
        val query = "name='$name' and mimeType='application/vnd.google-apps.spreadsheet' and trashed=false"
        val result = driveService.files().list().setQ(query).setSpaces("drive").setCorpus("user")
            .setFields("files(id, owners, shared)").execute()
        result.files.firstOrNull { file -> (file.owners?.any { it.me == true } == true) || (file.shared == true) }?.id
    }
}
