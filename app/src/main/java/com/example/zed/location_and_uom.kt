package com.example.zed

import android.app.ProgressDialog
import android.net.Uri
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
import androidx.annotation.Keep
import androidx.appcompat.app.AlertDialog
import androidx.cardview.widget.CardView
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.FileContent
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.SheetsScopes
import com.google.api.services.sheets.v4.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.Comparator


// Helper class for the spinner
data class UnitOfMeasureItem(val description: String, val value: Int) {
    override fun toString(): String = description
}
// ✅ --- END: DATA CLASS DEFINITIONS ---


class location_and_uom : Fragment() {

    companion object {
        private const val SPREADSHEET_NAME = "nia-bridge data"
        private const val SHEET_PRODUCTS = "Products"
        private const val SHEET_LOCATIONS = "product_location"
        private const val SHEET_UNITS = "unit_measure"
        private const val TAG = "LocationUomFragment"
    }

    private val sharedViewModel: SharedViewModel by activityViewModels()
    private lateinit var progressDialog: ProgressDialog

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
    private lateinit var btnSaveChanges: CardView
    private lateinit var deleteProduct: CardView // View for the delete button
    private var pendingProductData: SelectedProductData? = null
    private var isDynamicDataLoaded = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_location_and_uom, container, false)
        initializeViews(view)
        setupUomSpinner()
        setupListeners()
        observeViewModel()
        fetchDynamicData()
        return view
    }

    // ✅ --- START: DELETE PRODUCT LOGIC ---

    /**
     * Shows a confirmation dialog before proceeding with deletion.
     */
    private fun confirmAndDeleteProduct() {
        val currentProductData = sharedViewModel.selectedProductData.value ?: return

        AlertDialog.Builder(requireContext())
            .setTitle("Delete Product")
            .setMessage("Are you sure you want to permanently delete '${currentProductData.product.name}'? This will also remove all its units of measure. This action cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                handleDeleteProduct(currentProductData)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Handles the actual deletion from Google Sheets.
     */
    private fun handleDeleteProduct(productToDelete: SelectedProductData) {
        progressDialog.setMessage("Deleting product...")
        progressDialog.show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val account = GoogleSignIn.getLastSignedInAccount(requireContext())
                    ?: throw IllegalStateException("User not signed in.")
                val sheetsService = getSheetsService(account)
                val spreadsheetId = findSheetIdByName(getDriveService(account), SPREADSHEET_NAME)
                    ?: throw IllegalStateException("Spreadsheet not found.")

                // 1. Delete all associated Units of Measure
                withContext(Dispatchers.Main) {
                    progressDialog.setMessage("Deleting units of measure...")
                }
                deleteAllUomsForProduct(sheetsService, spreadsheetId, productToDelete.product.barcode)

                // 2. Delete the product itself from the Products sheet
                withContext(Dispatchers.Main) {
                    progressDialog.setMessage("Deleting main product entry...")
                }
                val productRowIndex = findProductRowIndex(sheetsService, spreadsheetId, productToDelete.product.id)
                if (productRowIndex != -1) {
                    val productsSheetId = getSheetId(sheetsService, spreadsheetId, SHEET_PRODUCTS)
                        ?: throw IllegalStateException("Could not find sheet ID for '$SHEET_PRODUCTS'")

                    val deleteRequest = Request().setDeleteDimension(
                        DeleteDimensionRequest().setRange(
                            DimensionRange()
                                .setSheetId(productsSheetId)
                                .setDimension("ROWS")
                                .setStartIndex(productRowIndex - 1) // Adjust for 0-based index
                                .setEndIndex(productRowIndex)
                        )
                    )

                    val batchUpdateRequest = BatchUpdateSpreadsheetRequest().setRequests(listOf(deleteRequest))
                    sheetsService.spreadsheets().batchUpdate(spreadsheetId, batchUpdateRequest).execute()
                }

                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(requireContext(), "'${productToDelete.product.name}' was deleted.", Toast.LENGTH_LONG).show()
                    // Clear the UI and the ViewModel selection
                    sharedViewModel.clearSelection()
                    clearAllViews()
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Log.e(TAG, "Error during handleDeleteProduct", e)
                    Toast.makeText(requireContext(), "Error deleting product: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ✅ --- END: DELETE PRODUCT LOGIC ---

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
                val driveService = getDriveService(account)
                val sheetsService = getSheetsService(account)
                val spreadsheetId = findSheetIdByName(driveService, SPREADSHEET_NAME)
                    ?: throw IllegalStateException("Spreadsheet not found.")

                val productRowIndex = findProductRowIndex(sheetsService, spreadsheetId, currentProductData.product.id)
                if (productRowIndex == -1) throw IllegalStateException("Could not find the product in the sheet to update.")

                // --- Image Logic: Delete old, Upload new ---
                var finalImageUrl = currentProductData.product.imageUrl
                val currentImageUrl = currentProductData.product.imageUrl

                if (currentImageUrl != null && (currentImageUrl.startsWith("content://") || currentImageUrl.startsWith("file://"))) {
                    withContext(Dispatchers.Main) {
                        progressDialog.setMessage("Uploading new image...")
                    }
                    val newImageUri = Uri.parse(currentImageUrl)

                    val originalData = sharedViewModel.selectedProductData.value
                    // Correctly read from the stashed field
                    val oldImageDriveUrl = originalData?.product?.stashedImageUrl

                    if (!oldImageDriveUrl.isNullOrBlank() && oldImageDriveUrl.startsWith("https://")) {
                        deleteImageFromDrive(driveService, oldImageDriveUrl)
                    }

                    finalImageUrl = uploadImageToDriveAndGetLink(driveService, newImageUri)
                }

                // --- Location & UOM Logic ---
                val newLocations = getNewLocationDataFromUi()
                val newLocationIds = addNewLocationsToSheet(sheetsService, spreadsheetId, newLocations, account)
                val finalLocationIds = gatherFinalLocationIdsFromUi(newLocationIds)

                val allCurrentUoms = gatherFinalUomDataFromUi()
                deleteAllUomsForProduct(sheetsService, spreadsheetId, currentProductData.product.barcode)
                addNewUnitsToSheet(sheetsService, spreadsheetId, allCurrentUoms, currentProductData.product.barcode, account)

                val mainUom = withContext(Dispatchers.Main) {
                    (unit_of_measure_populates.selectedItem as? UnitOfMeasureItem)?.description ?: ""
                }
                val currentQty = withContext(Dispatchers.Main) { qty.text.toString() }

                val updatedValues = listOf(
                    currentProductData.product.name,
                    finalImageUrl ?: "", // Use the new permanent URL
                    currentProductData.product.barcode,
                    currentProductData.product.categoryId,
                    mainUom,
                    currentQty,
                    currentProductData.product.minOrder,
                    currentProductData.product.unitCost,
                    finalLocationIds.joinToString(prefix = "['", postfix = "']", separator = "', '"),
                    account.email ?: "Unknown",
                    getCurrentTimestamp()
                )

                Log.d(TAG, "Final Update Values: $updatedValues")
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
                    Log.e(TAG, "Error during handleSaveChanges", e)
                    Toast.makeText(requireContext(), "Error saving changes: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private suspend fun uploadImageToDriveAndGetLink(driveService: Drive, imageUri: Uri): String? {
        return withContext(Dispatchers.IO) {
            try {
                val tempFile = File(requireContext().cacheDir, "upload_${System.currentTimeMillis()}.jpg")
                requireContext().contentResolver.openInputStream(imageUri)?.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                val fileMetadata = com.google.api.services.drive.model.File().setName(tempFile.name)
                val mediaContent = FileContent("image/jpeg", tempFile)

                Log.d(TAG, "Uploading new image to Google Drive...")
                // We only need the 'id' field now
                val file = driveService.files().create(fileMetadata, mediaContent)
                    .setFields("id")
                    .execute()

                val permission = com.google.api.services.drive.model.Permission()
                    .setType("anyone")
                    .setRole("reader")
                driveService.permissions().create(file.id, permission).execute()

                // Construct the direct download URL instead of using webViewLink
                val directImageUrl = "https://drive.google.com/uc?id=${file.id}"
                Log.d(TAG, "Image uploaded. New Direct Link: $directImageUrl")

                tempFile.delete() // Clean up temporary file
                return@withContext directImageUrl // Return the correct URL

            } catch (e: Exception) {
                Log.e(TAG, "Failed to upload image", e)
                return@withContext null
            }
        }
    }

    private suspend fun deleteImageFromDrive(driveService: Drive, fileUrl: String) {
        withContext(Dispatchers.IO) {
            try {
                // Handle both direct link and web view link formats
                val fileId = if (fileUrl.contains("uc?id=")) {
                    fileUrl.substringAfter("uc?id=")
                } else {
                    fileUrl.substringAfter("/d/").substringBefore("/")
                }

                if (fileId.isNotBlank()) {
                    Log.d(TAG, "Deleting old image from Drive. File ID: $fileId")
                    driveService.files().delete(fileId).execute()
                    Log.d(TAG, "Old image deleted successfully.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete old image from Drive. URL: $fileUrl", e)
            }
        }
    }

    private fun initializeViews(view: View) {
        locationContainer = view.findViewById(R.id.locationContainer)
        location_add = view.findViewById(R.id.location_add)
        locationCounterTextView = view.findViewById(R.id.location_counter)
        unitCounterTextView = view.findViewById(R.id.unit_counter)
        unit_of_measure_populates = view.findViewById(R.id.unit_of_measure_populates)
        unitContainer = view.findViewById(R.id.unitContainer)
        btnAddUnit = view.findViewById(R.id.btnAddUnit)
        qty = view.findViewById(R.id.cost_price)
        quantity_display = view.findViewById(R.id.quantity_display)
        btnSaveChanges = view.findViewById(R.id.btnSaveChanges)
        deleteProduct = view.findViewById(R.id.deleteProduct) // ✅ Initialize the delete button

        locationCounterTextView.text = "0"
        unitCounterTextView.text = "0"
        progressDialog = ProgressDialog(requireContext()).apply {
            setCancelable(false)
        }
    }

    private fun observeViewModel() {
        sharedViewModel.selectedProductData.observe(viewLifecycleOwner) { data ->
            pendingProductData = data
            tryPopulateUi()
        }
    }

    private fun tryPopulateUi() {
        if (isDynamicDataLoaded && pendingProductData != null) {
            populateUiWithData(pendingProductData!!)
            pendingProductData = null
        }
    }

    private fun populateUiWithData(data: SelectedProductData) {
        val logTag = "FragmentDataLog"
        Log.d(logTag, "==========================================================")
        Log.d(logTag, "Populating UI for location_and_uom with new data:")
        Log.d(logTag, "  - Product: ${data.product.name} (ID: ${data.product.id})")
        Log.d(logTag, "  - Product Barcode: ${data.product.barcode}")
        Log.d(logTag, "  - Image URL: ${data.product.imageUrl}")
        Log.d(logTag, "  - Total Locations Received: ${data.locations.size}")
        data.locations.forEachIndexed { index, location ->
            Log.d(logTag, "    - Location[${index}]: ID=${location.id}, Aisle='${location.aisle}', Rack='${location.rack}', Shelf='${location.shelf}'")
        }
        Log.d(logTag, "  - Total Units of Measure Received: ${data.units.size}")
        data.units.forEachIndexed { index, unit ->
            Log.d(logTag, "    - UOM[${index}]: Desc='${unit.quantityDescription}', Units='${unit.caseUnits}'")
        }
        Log.d(logTag, "==========================================================")

        clearAllViews()
        occupiedLocationIds.clear()
        newlySelectedLocationIds.clear()
        occupiedLocationIds.addAll(data.product.locationIds)

        // The dynamic data is now guaranteed to be loaded, so we can safely add the views.
        data.units.forEach { addUnitView(it) }
        data.locations.forEach { addUnitLocationView(it, isNew = false) }

        dynamicUnitsOfMeasure.clear()
        val spinnerItems = data.units.mapNotNull {
            it.caseUnits.toIntOrNull()?.let { caseUnits -> UnitOfMeasureItem(it.quantityDescription, caseUnits) }
        }
        dynamicUnitsOfMeasure.addAll(spinnerItems)
        uomAdapter.notifyDataSetChanged()

        // Find the unit of measure with the smallest quantity.
        val itemWithLowestQty = dynamicUnitsOfMeasure.minByOrNull { it.value }
        if (itemWithLowestQty != null) {
            val positionToSelect = dynamicUnitsOfMeasure.indexOf(itemWithLowestQty)
            if (positionToSelect != -1) {
                unit_of_measure_populates.setSelection(positionToSelect)
            }
        }

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
        btnAddUnit.setOnClickListener { addUnitView(null) }
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
        deleteProduct.setOnClickListener { confirmAndDeleteProduct() } // ✅ Set the listener for delete
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

    private fun addUnitView(unit: UnitOfMeasure?) {
        val inflater = LayoutInflater.from(requireContext())
        val unitView = inflater.inflate(R.layout.unit_of_measure_item, unitContainer, false)
        unitView.tag = unit // Store the original object
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

        // --- Data and Adapters ---
        val aislesWithPlaceholder = mutableListOf("Select Aisle").apply { addAll(dynamicAisles) }
        val racksWithPlaceholder = mutableListOf("Select Rack").apply { addAll(dynamicRacks) }
        val shelvesWithPlaceholder = mutableListOf("Select Shelf").apply { addAll(dynamicShelves) }
        val aisleAdapter = ArrayAdapter(requireContext(), R.layout.spinner_item, aislesWithPlaceholder)
        val rackAdapter = ArrayAdapter(requireContext(), R.layout.spinner_item, racksWithPlaceholder)
        val shelfAdapter = ArrayAdapter(requireContext(), R.layout.spinner_item, shelvesWithPlaceholder)
        aisleAdapter.setDropDownViewResource(R.layout.spinner_item)
        rackAdapter.setDropDownViewResource(R.layout.spinner_item)
        shelfAdapter.setDropDownViewResource(R.layout.spinner_item)

        // --- Validation and Listener Helpers ---
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

        // --- Assign everything for user interaction first ---
        aisleSpinner.adapter = aisleAdapter
        rackSpinner.adapter = ArrayAdapter(requireContext(), R.layout.spinner_item, listOf("Select Aisle First"))
        shelfSpinner.adapter = ArrayAdapter(requireContext(), R.layout.spinner_item, listOf("Select Rack First"))
        rackSpinner.isEnabled = false
        shelfSpinner.isEnabled = false
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

        // --- Population Logic ---
        location?.let { loc ->
            val logTag = "SpinnerPopulation"
            Log.d(logTag, "--- STARTING POPULATION FOR '${loc.aisle}', '${loc.rack}', '${loc.shelf}' ---")
            val aisleToFind = loc.aisle.trim()
            val rackToFind = loc.rack.trim()
            val shelfToFind = loc.shelf.trim()
            val aislePos = aislesWithPlaceholder.indexOfFirst { it.trim().equals(aisleToFind, ignoreCase = true) }
            val rackPos = racksWithPlaceholder.indexOfFirst { it.trim().equals(rackToFind, ignoreCase = true) }
            val shelfPos = shelvesWithPlaceholder.indexOfFirst { it.trim().equals(shelfToFind, ignoreCase = true) }
            Log.d(logTag, "Searching for Aisle: '$aisleToFind'. Found at position: $aislePos")
            Log.d(logTag, "Searching for Rack: '$rackToFind'. Found at position: $rackPos")
            Log.d(logTag, "Searching for Shelf: '$shelfToFind'. Found at position: $shelfPos")
            if (aislePos > 0 && rackPos > 0 && shelfPos > 0) {
                Log.d(logTag, "All positions are valid. Preparing to set spinners.")
                val aisleOriginalListener = aisleSpinner.onItemSelectedListener
                val rackOriginalListener = rackSpinner.onItemSelectedListener
                val shelfOriginalListener = shelfSpinner.onItemSelectedListener
                aisleSpinner.onItemSelectedListener = null
                rackSpinner.onItemSelectedListener = null
                shelfSpinner.onItemSelectedListener = null
                rackSpinner.adapter = rackAdapter
                shelfSpinner.adapter = shelfAdapter
                rackSpinner.isEnabled = true
                shelfSpinner.isEnabled = true
                locationView.post {
                    Log.d(logTag, "UI is ready. Setting selections now.")
                    aisleSpinner.setSelection(aislePos, false)
                    rackSpinner.setSelection(rackPos, false)
                    shelfSpinner.setSelection(shelfPos, false)
                    Log.d(logTag, "--- POPULATION COMPLETE ---")
                    aisleSpinner.onItemSelectedListener = aisleOriginalListener
                    rackSpinner.onItemSelectedListener = rackOriginalListener
                    shelfSpinner.onItemSelectedListener = shelfOriginalListener
                    Log.d(logTag, "Listeners restored.")
                }
            } else {
                Log.e(logTag, "ERROR: One or more positions were not found. Halting population.")
                if (aislePos <= 0) Log.e(logTag, "-> Aisle '${aisleToFind}' NOT FOUND in adapter list.")
                if (rackPos <= 0) Log.e(logTag, "-> Rack '${rackToFind}' NOT FOUND in adapter list.")
                if (shelfPos <= 0) Log.e(logTag, "-> Shelf '${shelfToFind}' NOT FOUND in adapter list.")
            }
        }

        // --- Add/Remove Listeners ---
        btnAddAisle.setOnClickListener { showAddItemDialog("Add New Aisle", aisleAdapter, aisleSpinner) }
        btnAddRack.setOnClickListener { showAddItemDialog("Add New Rack", rackAdapter, rackSpinner) }
        btnAddShelf.setOnClickListener { showAddItemDialog("Add New Shelf", shelfAdapter, shelfSpinner) }
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

    private fun updateUnitCount() { unitCounterTextView.text = unitContainer.childCount.toString() }
    private fun updateLocationCount() { locationCounterTextView.text = locationContainer.childCount.toString() }

    private suspend fun gatherFinalLocationIdsFromUi(newLocationIds: List<String>): List<String> {
        val finalLocationIds = mutableListOf<String>()
        withContext(Dispatchers.Main) {
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

    private suspend fun gatherFinalUomDataFromUi(): List<Map<String, String>> {
        val allUnits = mutableListOf<Map<String, String>>()
        withContext(Dispatchers.Main) {
            Log.d(TAG, "Starting to gather ALL Unit of Measure data from UI...")
            for (i in 0 until unitContainer.childCount) {
                val view = unitContainer.getChildAt(i)
                val originalUnit = view.tag as? UnitOfMeasure
                val descriptionField = view.findViewById<EditText>(R.id.unitQty)
                val caseUnitsField = view.findViewById<EditText>(R.id.unitShelf)
                val desc = descriptionField.text.toString().trim()
                val caseUnits = caseUnitsField.text.toString().trim()
                if (desc.isNotEmpty() && caseUnits.isNotEmpty()) {
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
        if (allUnits.isEmpty()) { Log.d(TAG, "No valid UOMs were found in the UI to save.") }
        return allUnits
    }

    private fun getNewLocationDataFromUi(): List<Map<String, String>> {
        val newLocations = mutableListOf<Map<String, String>>()
        for (i in 0 until locationContainer.childCount) {
            val view = locationContainer.getChildAt(i)
            if (view.tag == "new") {
                val aisleSpinner = view.findViewById<Spinner>(R.id.aisle_spinner)
                val rackSpinner = view.findViewById<Spinner>(R.id.rack_spinner)
                val shelfSpinner = view.findViewById<Spinner>(R.id.shelf_spinner)
                if (aisleSpinner.selectedItemPosition > 0 && rackSpinner.selectedItemPosition > 0 && shelfSpinner.selectedItemPosition > 0) {
                    val locationMap = mapOf("Aisle" to aisleSpinner.selectedItem.toString(), "Rack" to rackSpinner.selectedItem.toString(), "Shelf" to shelfSpinner.selectedItem.toString())
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
        sheetsService.spreadsheets().values().append(spreadsheetId, "$SHEET_LOCATIONS!A1", body).setValueInputOption("USER_ENTERED").execute()
        return newLocationIds
    }

    private suspend fun deleteAllUomsForProduct(sheetsService: Sheets, spreadsheetId: String, productBarcode: String) {
        Log.d(TAG, "Attempting to delete all existing UOMs for barcode: $productBarcode")
        val sheetId = getSheetId(sheetsService, spreadsheetId, SHEET_UNITS) ?: throw IllegalStateException("Could not find sheet ID for '$SHEET_UNITS'")
        val range = "$SHEET_UNITS!A:A"
        val response = sheetsService.spreadsheets().values().get(spreadsheetId, range).execute()
        val values = response.getValues() ?: return
        val deleteRequests = mutableListOf<Request>()
        for (i in values.indices.reversed()) {
            val row = values[i]
            if (row.isNotEmpty() && row[0].toString().trim() == productBarcode.trim()) {
                val rowIndex = i
                val deleteRequest = Request().setDeleteDimension(DeleteDimensionRequest().setRange(DimensionRange().setSheetId(sheetId).setDimension("ROWS").setStartIndex(rowIndex).setEndIndex(rowIndex + 1)))
                deleteRequests.add(deleteRequest)
                Log.d(TAG, "Prepared to delete UOM row at index: $rowIndex for barcode $productBarcode")
            }
        }
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
        val unitRows = units.map { u -> listOf(mainBarcode, u["barcode"] ?: mainBarcode, u["sellingPrice"] ?: "", u["caseUnits"] ?: "", u["description"] ?: "", u["cost"] ?: "", account.email ?: "Unknown", getCurrentTimestamp()) }
        Log.d(TAG, "Appending ${unitRows.size} new rows to '$SHEET_UNITS' sheet: $unitRows")
        val body = ValueRange().setValues(unitRows)
        sheetsService.spreadsheets().values().append(spreadsheetId, "$SHEET_UNITS!A1", body).setValueInputOption("USER_ENTERED").execute()
    }

    private suspend fun findProductRowIndex(sheetsService: Sheets, spreadsheetId: String, productId: String): Int {
        val range = "$SHEET_PRODUCTS!A:A"
        val response = sheetsService.spreadsheets().values().get(spreadsheetId, range).execute()
        val values = response.getValues() ?: return -1
        for ((index, row) in values.withIndex()) { // No drop(1)
            if (row.isNotEmpty() && row[0].toString() == productId) {
                return index + 1 // Return 1-based index for sheet operations
            }
        }
        return -1
    }

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
                val account = GoogleSignIn.getLastSignedInAccount(requireContext()) ?: throw IllegalStateException("User not signed in")
                val sheetsService = getSheetsService(account)
                val spreadsheetId = findSheetIdByName(getDriveService(account), SPREADSHEET_NAME) ?: throw IllegalStateException("Spreadsheet not found")
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
                    tryPopulateUi()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    isDynamicDataLoaded = true // Set to true anyway to avoid a deadlock
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
        val dialog = AlertDialog.Builder(requireContext()).setTitle(title).setView(container).setNegativeButton("Cancel", null).setPositiveButton("Add", null).create()
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

    private fun getCurrentTimestamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

    private fun getSheetsService(account: GoogleSignInAccount): Sheets {
        val credential = GoogleAccountCredential.usingOAuth2(requireContext(), listOf(SheetsScopes.SPREADSHEETS, DriveScopes.DRIVE_FILE))
            .apply { selectedAccountName = account.email }
        return Sheets.Builder(GoogleNetHttpTransport.newTrustedTransport(), GsonFactory.getDefaultInstance(), credential)
            .setApplicationName("Nia Bridge App").build()
    }

    private fun getDriveService(account: GoogleSignInAccount): Drive {
        val credential = GoogleAccountCredential.usingOAuth2(requireContext(), listOf(DriveScopes.DRIVE, DriveScopes.DRIVE_FILE))
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
