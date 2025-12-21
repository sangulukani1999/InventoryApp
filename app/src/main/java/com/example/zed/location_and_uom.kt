package com.example.zed

import android.app.ProgressDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
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
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
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
import com.example.zed.MyBottomStockSheet.SheetItem
import com.example.zed.MyBottomStockSheet.UnitOfMeasureItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class location_and_uom: Fragment() {

    companion object {
        private const val SPREADSHEET_NAME = "nia-bridge data"
        private const val FOLDER_NAME = "nia-bridge"
        private const val SHEET_TAB_NAME = "Products"
        private const val SHEET_UNITS = "unit_measure"
        private const val SHEET_LOCATIONS = "product_location"
        private const val SHEET_CATEGORY = "category"
    }


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
    private val occupiedLocationIds = mutableSetOf<String>()
    private val newlySelectedLocationIds = mutableSetOf<String>()


    private lateinit var progressDialog: ProgressDialog


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view  = inflater.inflate(R.layout.fragment_location_and_uom, container, false)
        // Inflate the layout for this fragment
        initializeViews(view)
        setupUomSpinner()
        setupListeners()
        fetchDynamicData()
        return view
    }
    private fun initializeViews(view: View) {
        quantity_display = view.findViewById(R.id.quantity_display)
        locationContainer = view.findViewById(R.id.locationContainer)
        location_add = view.findViewById(R.id.location_add)
        locationCounterTextView = view.findViewById(R.id.location_counter)
        unitCounterTextView = view.findViewById(R.id.unit_counter)
        unit_of_measure_populates = view.findViewById(R.id.unit_of_measure_populates)
        unitContainer = view.findViewById(R.id.unitContainer)
        btnAddUnit = view.findViewById(R.id.btnAddUnit)
        qty = view.findViewById(R.id.qty)
        locationCounterTextView.text = "0"
        unitCounterTextView.text = "0"
        progressDialog = ProgressDialog(requireContext()).apply {
            setCancelable(false)
        }
    }
    private fun setupUomSpinner() {
        if (dynamicUnitsOfMeasure.isEmpty()) {
            dynamicUnitsOfMeasure.add(UnitOfMeasureItem("Select a defined unit", 0))
        }
        uomAdapter = ArrayAdapter(requireContext(), R.layout.spinner_item, dynamicUnitsOfMeasure)
        uomAdapter.setDropDownViewResource(R.layout.spinner_item)
        unit_of_measure_populates.adapter = uomAdapter
    }

    private fun setupListeners() {
        btnAddUnit.setOnClickListener { addUnitView() }
        location_add.setOnClickListener { addUnitLocationView() }
        qty.addTextChangedListener(mainCalculationWatcher)
        unit_of_measure_populates.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateTotalCalculation()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {
                quantity_display.setText("0")
            }
        }

    }
    private val mainCalculationWatcher: TextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) { updateTotalCalculation() }
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

                // Fetch Categories
                val categoryRange = "'$SHEET_CATEGORY'!A2:B"
                val categoryResponse = sheetsService.spreadsheets().values().get(spreadsheetId, categoryRange).execute()
                val categoriesFromSheet = categoryResponse.getValues()?.mapNotNull { row ->
                    if (row.size >= 2) SheetItem(id = row[0].toString(), name = row[1].toString()) else null
                } ?: emptyList()

                // Fetch Locations
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

                // Fetch occupied location IDs from Products sheet
                val productsLocationRange = "'$SHEET_TAB_NAME'!I2:I" // Column I for Location_IDs
                val productsResponse = sheetsService.spreadsheets().values().get(spreadsheetId, productsLocationRange).execute()
                val usedIds = productsResponse.getValues()?.flatMap { row ->
                    val rawString = row.getOrNull(0)?.toString() ?: "[]"
                    rawString.removeSurrounding("[", "]").replace("'", "").split(",").map { it.trim() }.filter { it.isNotBlank() }
                } ?: emptyList()

                // Fetch existing barcodes from Products sheet
                val barcodeRange = "'$SHEET_TAB_NAME'!D2:D" // Column D for Barcode
                val barcodeResponse = sheetsService.spreadsheets().values().get(spreadsheetId, barcodeRange).execute()
                val barcodesFromSheet = barcodeResponse.getValues()?.mapNotNull { row ->
                    row.getOrNull(0)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                } ?: emptyList()

                withContext(Dispatchers.Main) {
                    dynamicAisles.clear()
                    dynamicAisles.addAll(aislesFromSheet.sorted())
                    dynamicRacks.clear()
                    dynamicRacks.addAll(racksFromSheet.sorted())
                    dynamicShelves.clear()
                    dynamicShelves.addAll(shelvesFromSheet.sorted())

                    locationNameToIdMap.clear()
                    locationNameToIdMap.putAll(tempLocationMap)

                    occupiedLocationIds.clear()
                    occupiedLocationIds.addAll(usedIds)

                    newlySelectedLocationIds.clear()
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

    private fun updateTotalCalculation() {
        val selectedItem = unit_of_measure_populates.selectedItem as? UnitOfMeasureItem
        if (selectedItem == null || selectedItem.value == 0) {
            quantity_display.setText("0")
            return
        }
        val selectedUnitValue = selectedItem.value.toDouble()
        val quantityOfCases = qty.text.toString().toDoubleOrNull() ?: 0.0
        val grandTotal = selectedUnitValue * quantityOfCases
        quantity_display.setText(String.format("%.0f", grandTotal))
    }
    private fun addUnitView() {
        val inflater = LayoutInflater.from(requireContext())
        val unitView = inflater.inflate(R.layout.unit_of_measure_item, unitContainer, false)

        val unitShelfField = unitView.findViewById<EditText>(R.id.unitShelf)
        val unitQtyField = unitView.findViewById<EditText>(R.id.unitQty)
        val btnRemove = unitView.findViewById<Button>(R.id.btnRemoveUnit)
        var oldDescription: String? = null
        val focusListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                oldDescription = unitQtyField.text.toString().trim()
            } else {
                val unitsInCase = unitShelfField.text.toString().toIntOrNull()
                val newDescription = unitQtyField.text.toString().trim()

                if (unitsInCase != null && newDescription.isNotEmpty()) {
                    if (!oldDescription.isNullOrEmpty() && oldDescription != newDescription) {
                        dynamicUnitsOfMeasure.removeAll { it.description == oldDescription }
                    }

                    val existingItem = dynamicUnitsOfMeasure.find { it.description == newDescription }
                    if (existingItem != null) {
                        val index = dynamicUnitsOfMeasure.indexOf(existingItem)
                        dynamicUnitsOfMeasure[index] = UnitOfMeasureItem(newDescription, unitsInCase)
                        Toast.makeText(context, "Updated '$newDescription'.", Toast.LENGTH_SHORT).show()
                    } else {
                        val newUomItem = UnitOfMeasureItem(newDescription, unitsInCase)
                        dynamicUnitsOfMeasure.add(newUomItem)
                        Toast.makeText(context, "Added '$newDescription' to session choices.", Toast.LENGTH_SHORT).show()
                    }

                    uomAdapter.notifyDataSetChanged()
                    val currentPosition = dynamicUnitsOfMeasure.indexOfFirst { it.description == newDescription }
                    if (currentPosition != -1) {
                        unit_of_measure_populates.setSelection(currentPosition)
                    }
                }
            }
        }
        unitQtyField.onFocusChangeListener = focusListener
        unitShelfField.onFocusChangeListener = focusListener

        btnRemove.setOnClickListener {
            val descriptionToRemove = unitQtyField.text.toString().trim()
            val currentlySelectedItem = unit_of_measure_populates.selectedItem as? UnitOfMeasureItem

            unitContainer.removeView(unitView)
            updateUnitCount()

            if (descriptionToRemove.isNotEmpty()) {
                val itemToRemove = dynamicUnitsOfMeasure.find { it.description == descriptionToRemove }
                if (itemToRemove != null) {
                    val wasCurrentlySelected = currentlySelectedItem?.description == itemToRemove.description
                    dynamicUnitsOfMeasure.remove(itemToRemove)
                    uomAdapter.notifyDataSetChanged()

                    if (wasCurrentlySelected) {
                        unit_of_measure_populates.setSelection(0)
                    }

                    Toast.makeText(context, "Removed '$descriptionToRemove' from session choices.", Toast.LENGTH_SHORT).show()
                }
            }
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

        val btnAddAisle = locationView.findViewById<CardView>(R.id.aisle_spinner_add)
        val btnAddRack = locationView.findViewById<CardView>(R.id.rack_spinner_add)
        val btnAddShelf = locationView.findViewById<CardView>(R.id.shelf_spinner_add)

        var thisRowSelectedId: String? = null

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