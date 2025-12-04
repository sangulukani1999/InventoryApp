package com.example.zed

import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.example.zed.databinding.ActivityInventoryItemDetailsBinding
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.SheetsScopes
import com.google.api.services.sheets.v4.model.*
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import com.example.zed.BarcodeScannerDialogFragment
import com.google.mlkit.vision.barcode.common.Barcode

class InventoryItemDetails : AppCompatActivity() {

    private lateinit var binding: ActivityInventoryItemDetailsBinding
    private val countList = mutableListOf<CountEntry>()
    private val locationList = mutableListOf<LocationItem>()
    private val unitList = mutableListOf<UnitOfMeasure>()
    private val productLocations = mutableListOf<LocationItem>()

    private lateinit var countAdapter: CountEntryAdapter
    private lateinit var unitAdapter: UnitOfMeasureAdapter

    private var isAdmin = false
    private var selectedUnit: UnitOfMeasure? = null

    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var firebaseAuth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInventoryItemDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        firebaseAuth = FirebaseAuth.getInstance()
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        val inventoryBarcode = intent.getStringExtra("inventoryBarcodes")
        if (inventoryBarcode.isNullOrBlank()) {
            Toast.makeText(this, "Error: Product barcode not provided.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Call setup functions once
        setupRecyclerViews()
        setupClickListeners()

        val currentUserEmail = GoogleSignIn.getLastSignedInAccount(this)?.email
        if (currentUserEmail != null) {
            checkUserRole(currentUserEmail) { isAdminResult, _, _ ->
                isAdmin = isAdminResult
            }
        }

        fetchProductDetails(inventoryBarcode)
    }

    /**
     * Sets up click listeners for the UI elements on THIS screen.
     */
    private fun setupClickListeners() {
        // Listener for the primary action: adding a new count entry.
        binding.addConstraint.setOnClickListener {
            showAddEntryDialog()
        }

        // ✅ --- START: CORRECTED SCANNER LOGIC ---
        // **Make sure your scanner ImageView in the XML has this ID: `barcodeScannerIcon`**
        binding.barcodeScanner.setOnClickListener {
            val scannerDialog = BarcodeScannerDialogFragment { scannedBarcode ->
                // When a barcode is found, call the new validation function instead of navigating directly.
                validateBarcodeAndNavigate(scannedBarcode)
            }
            // Show the dialog using the Activity's supportFragmentManager
            scannerDialog.show(supportFragmentManager, "DetailsScannerDialog")
        }
        // ✅ --- END: CORRECTED SCANNER LOGIC ---
    }

    /**
     * ✅ NEW FUNCTION: Validates the scanned barcode against the Google Sheet before navigating.
     * This prevents the app from crashing if an unknown barcode is scanned.
     */
    private fun validateBarcodeAndNavigate(barcode: String) {
        val progressDialog = showLoader("Verifying product...")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val account = GoogleSignIn.getLastSignedInAccount(this@InventoryItemDetails)
                    ?: throw IllegalStateException("User not signed in.")
                val sheetsService = getSheetsService(account)
                val driveService = getDriveService(account)
                val spreadsheetId = findSheetIdByName(driveService, "nia-bridge data")
                    ?: throw IllegalStateException("Spreadsheet 'nia-bridge data' not found.")

                // Only fetch the barcode column (D) for an efficient check
                val range = "Products!D:D"
                val response = sheetsService.spreadsheets().values().get(spreadsheetId, range).execute()
                val barcodesInSheet = response.getValues()?.flatMap { it.map { cell -> cell.toString().trim() } } ?: emptyList()

                val productExists = barcodesInSheet.any { it == barcode.trim() }

                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    if (productExists) {
                        // SUCCESS: Product found. Restart the activity with the new barcode.
                        Toast.makeText(this@InventoryItemDetails, "Product found. Loading details...", Toast.LENGTH_SHORT).show()
                        val intent = Intent(this@InventoryItemDetails, InventoryItemDetails::class.java).apply {
                            putExtra("inventoryBarcodes", barcode)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        }
                        startActivity(intent)
                    } else {
                        // FAILURE: Product not found. Show an error and stay on the current screen.
                        AlertDialog.Builder(this@InventoryItemDetails)
                            .setTitle("Not Found")
                            .setMessage("Product with barcode '$barcode' was not found in your products list.")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Log.e("ValidateBarcode", "Error during validation", e)
                    Toast.makeText(this@InventoryItemDetails, "Error verifying product: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }


    private fun setupRecyclerViews() {
        countAdapter = CountEntryAdapter(countList) { position ->
            val entry = countList[position]
            val currentUserEmail = GoogleSignIn.getLastSignedInAccount(this)?.email
            if (isAdmin || entry.user == currentUserEmail) {
                showDeleteConfirmationDialog(entry)
            } else {
                Toast.makeText(this, "You do not have permission to delete this entry.", Toast.LENGTH_SHORT).show()
            }
        }
        binding.itemCountEntry.apply {
            layoutManager = LinearLayoutManager(this@InventoryItemDetails)
            adapter = countAdapter
        }

        unitAdapter = UnitOfMeasureAdapter(unitList) { clickedUnit ->
            updateUnitDetails(clickedUnit)
            selectedUnit = clickedUnit
        }
        binding.productQtyRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@InventoryItemDetails, LinearLayoutManager.HORIZONTAL, false)
            adapter = unitAdapter
        }
    }

    private fun fetchProductDetails(barcode: String) {
        val progressDialog = showLoader("Loading Product Details...")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val account = GoogleSignIn.getLastSignedInAccount(this@InventoryItemDetails)
                    ?: throw IllegalStateException("User is not signed in.")

                val sheetsService = getSheetsService(account)
                val driveService = getDriveService(account)
                val spreadsheetId = findSheetIdByName(driveService, "nia-bridge data")
                    ?: throw IllegalStateException("Spreadsheet 'nia-bridge data' not found.")

                coroutineScope {
                    launch { fetchLocationData(sheetsService, spreadsheetId) }
                    launch { fetchStockEntriesFromGoogleSheet(sheetsService, spreadsheetId, barcode) }
                    launch { fetchUnitData(sheetsService, spreadsheetId, barcode) }
                }

                val range = "Products!A:L"
                val response = sheetsService.spreadsheets().values().get(spreadsheetId, range).execute()
                val values = response.getValues()?.drop(1) ?: emptyList()

                // This check is now a fallback, as validation should happen before we even get here.
                val productRow = values.firstOrNull { it.getOrNull(3)?.toString()?.trim() == barcode }
                if (productRow == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@InventoryItemDetails, "Product details could not be loaded.", Toast.LENGTH_LONG).show()
                        finish() // Close gracefully
                    }
                    return@launch
                }

                val productName = productRow.getOrNull(1)?.toString() ?: "N/A"
                val imageUrl = productRow.getOrNull(2)?.toString()
                val stockInCases = productRow.getOrNull(6)?.toString()?.toDoubleOrNull() ?: 0.0
                val rawLocationString = productRow.getOrNull(9)?.toString() ?: ""

                val highestUnit = unitList.mapNotNull { it.caseUnits.toIntOrNull() }.maxOrNull() ?: 1
                val totalStockInUnits = stockInCases * highestUnit

                val assignedLocationIds = Regex("LOC-[A-Z0-9]+", RegexOption.IGNORE_CASE)
                    .findAll(rawLocationString)
                    .map { it.value.uppercase() }
                    .toSet()

                productLocations.clear()
                productLocations.addAll(locationList.filter { it.id in assignedLocationIds })

                withContext(Dispatchers.Main) {
                    binding.itemName.text = productName
                    binding.itemBarcode.text = barcode
                    binding.itemStock.text = "Units: " + String.format("%.0f", totalStockInUnits)

                    val directImageUrl = convertDriveUrlToDirect(imageUrl)
                    binding.itemImage.load(directImageUrl) {
                        placeholder(R.drawable.ic_placeholder)
                        error(R.drawable.ic_placeholder)
                    }

                    countAdapter.notifyDataSetChanged()
                    unitAdapter.notifyDataSetChanged()

                    if (unitList.isNotEmpty()) {
                        selectedUnit = unitList.maxByOrNull { it.caseUnits.toIntOrNull() ?: 0 }
                        selectedUnit?.let { updateUnitDetails(it) }
                    }

                    updateCountAndDiff()
                    progressDialog.dismiss()
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Log.e("FetchProductDetails", "Failed to fetch product data", e)
                    Toast.makeText(this@InventoryItemDetails, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    //<editor-fold desc="The rest of your functions remain the same.">
    private suspend fun fetchUnitData(sheetsService: Sheets, spreadsheetId: String, productBarcode: String) {
        try {
            val range = "unit_measure!A:H"
            val response = sheetsService.spreadsheets().values().get(spreadsheetId, range).execute()
            val values = response.getValues()?.drop(1) ?: return

            val fetchedUnits = values.mapNotNull { row ->
                val sheetProductId = row.getOrNull(0)?.toString()?.trim()
                if (sheetProductId.isNullOrBlank() || !sheetProductId.equals(productBarcode, ignoreCase = true)) {
                    null
                } else {
                    UnitOfMeasure(
                        productId = sheetProductId,
                        unitBarcode = row.getOrNull(1)?.toString(),
                        sellingPrice = row.getOrNull(2)?.toString() ?: "0.00",
                        caseUnits = row.getOrNull(3)?.toString() ?: "1",
                        quantityDescription = row.getOrNull(4)?.toString() ?: "Unit",
                        cost = row.getOrNull(5)?.toString() ?: "0.00",
                        updatedBy = row.getOrNull(6)?.toString() ?: "",
                        timestamp = row.getOrNull(7)?.toString() ?: ""
                    )
                }
            }
            unitList.clear()
            unitList.addAll(fetchedUnits)
        } catch (e: Exception) {
            Log.e("UnitFetchError", "Error fetching unit data", e)
        }
    }

    private fun updateUnitDetails(unit: UnitOfMeasure) {
        binding.quantityDesc.text = unit.quantityDescription
        binding.quantityDescQty.text = "Contains: ${unit.caseUnits}"
        binding.quantityTypePrice.text = "ZMW " + unit.sellingPrice
    }

    private fun showAddEntryDialog() {
        val inventoryBarcode = binding.itemBarcode.text.toString()

        if (unitList.isEmpty()) {
            Toast.makeText(this, "No units of measure available for this product.", Toast.LENGTH_LONG).show()
            return
        }

        val dialogView = LayoutInflater.from(this).inflate(R.layout.inventory_dialog_alert, null)
        val spinnerAisle = dialogView.findViewById<Spinner>(R.id.aisleDialog)
        val spinnerRack = dialogView.findViewById<Spinner>(R.id.rackDialog)
        val spinnerShelf = dialogView.findViewById<Spinner>(R.id.shelfDialog)
        val inputDisplay = dialogView.findViewById<EditText>(R.id.editTextText2)
        val unitQtySpinner = dialogView.findViewById<Spinner>(R.id.unitQty)

        val unitDescriptions = unitList.map { it.quantityDescription }
        val unitSpinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, unitDescriptions)
        unitQtySpinner.adapter = unitSpinnerAdapter

        val previouslySelectedIndex = unitList.indexOf(selectedUnit)
        if (previouslySelectedIndex != -1) {
            unitQtySpinner.setSelection(previouslySelectedIndex)
        }

        setupNumpad(dialogView, inputDisplay)

        spinnerRack.isEnabled = false
        spinnerShelf.isEnabled = false
        val countedLocationIds = countList.map { it.locationId.uppercase() }.toSet()
        val availableAisles = productLocations
            .filter { it.id?.uppercase() !in countedLocationIds && !it.aisle.isNullOrBlank() }
            .mapNotNull { it.aisle }
            .distinct()

        val aisleOptions = mutableListOf("Select an Aisle").apply { addAll(availableAisles) }
        spinnerAisle.adapter = NothingSelectedSpinnerAdapter(this, android.R.layout.simple_spinner_dropdown_item, aisleOptions)

        spinnerAisle.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (position == 0) {
                    spinnerRack.adapter = null; spinnerRack.isEnabled = false
                    spinnerShelf.adapter = null; spinnerShelf.isEnabled = false
                    return
                }
                val selectedAisle = parent.getItemAtPosition(position).toString()
                val racks = productLocations
                    .filter { it.aisle == selectedAisle && it.id?.uppercase() !in countedLocationIds && !it.rack.isNullOrBlank() }
                    .mapNotNull { it.rack }
                    .distinct()
                val rackOptions = mutableListOf("Select a Rack").apply { addAll(racks) }
                spinnerRack.adapter = NothingSelectedSpinnerAdapter(this@InventoryItemDetails, android.R.layout.simple_spinner_dropdown_item, rackOptions)
                spinnerRack.isEnabled = true
                spinnerShelf.adapter = null; spinnerShelf.isEnabled = false
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        spinnerRack.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (position == 0) {
                    spinnerShelf.adapter = null; spinnerShelf.isEnabled = false
                    return
                }
                val selectedAisle = spinnerAisle.selectedItem.toString()
                val selectedRack = parent.getItemAtPosition(position).toString()
                val shelves = productLocations
                    .filter { it.aisle == selectedAisle && it.rack == selectedRack && it.id?.uppercase() !in countedLocationIds && !it.shelf.isNullOrBlank() }
                    .mapNotNull { it.shelf }
                    .distinct()
                val shelfOptions = mutableListOf("Select a Shelf").apply { addAll(shelves) }
                spinnerShelf.adapter = NothingSelectedSpinnerAdapter(this@InventoryItemDetails, android.R.layout.simple_spinner_dropdown_item, shelfOptions)
                spinnerShelf.isEnabled = true
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        val dialog = AlertDialog.Builder(this).setTitle("Add Count").setView(dialogView).create()
        dialogView.findViewById<CardView>(R.id.OkView)?.setOnClickListener {
            val quantityEntered = inputDisplay.text.toString().toIntOrNull()
            if (quantityEntered == null || quantityEntered <= 0) {
                Toast.makeText(this, "Please enter a valid quantity", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val selectedUnitFromSpinner = unitList[unitQtySpinner.selectedItemPosition]
            val selectedAisle = spinnerAisle.selectedItem?.toString()
            val selectedRack = spinnerRack.selectedItem?.toString()
            val selectedShelf = spinnerShelf.selectedItem?.toString()

            if (selectedAisle == "Select an Aisle" || selectedRack == "Select a Rack" || selectedShelf == "Select a Shelf" || selectedAisle.isNullOrEmpty() || selectedRack.isNullOrEmpty() || selectedShelf.isNullOrEmpty()) {
                Toast.makeText(this, "Please select Aisle, Rack, and Shelf", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val selectedLocation = productLocations.firstOrNull { it.aisle == selectedAisle && it.rack == selectedRack && it.shelf == selectedShelf }
            if (selectedLocation?.id == null) {
                Toast.makeText(this, "Could not find a valid Location ID.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            saveCountEntry(inventoryBarcode, selectedLocation.id, quantityEntered, selectedUnitFromSpinner, dialog)
        }
        dialog.show()
    }

    private fun saveCountEntry(barcode: String, locationId: String, quantityEntered: Int, unit: UnitOfMeasure, dialog: AlertDialog) {
        val progress = showLoader("Saving entry...")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val unitsPerType = unit.caseUnits.toIntOrNull() ?: 1
                val totalQuantityToSave = quantityEntered * unitsPerType

                Log.d("SaveEntryDebug", "Saving: $quantityEntered of '${unit.quantityDescription}' ($unitsPerType units each). Total: $totalQuantityToSave")

                val account = GoogleSignIn.getLastSignedInAccount(this@InventoryItemDetails) ?: throw Exception("Not signed in")
                val sheetsService = getSheetsService(account)
                val spreadsheetId = findSheetIdByName(getDriveService(account), "nia-bridge data") ?: throw Exception("Sheet not found")
                val sheetName = "countData"
                ensureSheetExists(sheetsService, spreadsheetId, sheetName)

                val values = listOf(
                    listOf(
                        SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date()),
                        barcode,
                        binding.itemName.text.toString(),
                        locationId,
                        totalQuantityToSave,
                        account.email
                    )
                )
                val body = ValueRange().setValues(values)
                sheetsService.spreadsheets().values()
                    .append(spreadsheetId, "$sheetName!A1", body)
                    .setValueInputOption("USER_ENTERED")
                    .execute()

                fetchStockEntriesFromGoogleSheet(sheetsService, spreadsheetId, barcode)

                withContext(Dispatchers.Main) {
                    progress.dismiss()
                    countAdapter.notifyDataSetChanged()
                    updateCountAndDiff()
                    Toast.makeText(this@InventoryItemDetails, "Entry saved!", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progress.dismiss()
                    Toast.makeText(this@InventoryItemDetails, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    Log.e("SaveEntryError", "Error in saveCountEntry", e)
                }
            }
        }
    }

    private fun updateCountAndDiff() {
        val totalCounted = countList.sumOf { it.quantity }
        val masterCaseSize = unitList.mapNotNull { it.caseUnits.toIntOrNull() }.maxOrNull()
        val countedDisplayText: String
        if (masterCaseSize != null && masterCaseSize > 0) {
            val displayValueInCases = totalCounted.toDouble() / masterCaseSize.toDouble()
            countedDisplayText = String.format("%.2f", displayValueInCases)
        } else {
            countedDisplayText = totalCounted.toString()
        }
        binding.itemCounted.text = "Counted: " + totalCounted
        val stock = binding.itemStock.text.toString().replace(Regex("[^0-9.]"), "").toDoubleOrNull() ?: 0.0
        val diff = stock - totalCounted
        binding.itemDiff.text = "Variance: " + String.format("%.0f", diff)
        binding.itemDiff.setTextColor(
            when {
                diff < 0 -> Color.RED
                diff > 0 -> Color.parseColor("#34A853")
                else -> Color.GRAY
            }
        )
    }

    private fun showDeleteConfirmationDialog(entry: CountEntry) {
        AlertDialog.Builder(this)
            .setTitle("Delete Entry")
            .setMessage("Are you sure you want to delete the count of ${entry.quantity} for location ${entry.aisle}-${entry.rack}-${entry.shelf}?")
            .setPositiveButton("Delete") { _, _ -> deleteCountEntry(entry) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteCountEntry(entry: CountEntry) {
        val progressDialog = showLoader("Deleting entry...")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val account = GoogleSignIn.getLastSignedInAccount(this@InventoryItemDetails) ?: throw Exception("User not signed in")
                val sheetsService = getSheetsService(account)
                val spreadsheetId = findSheetIdByName(getDriveService(account), "nia-bridge data") ?: throw Exception("Spreadsheet not found")
                val sheetName = "countData"

                val response = sheetsService.spreadsheets().values().get(spreadsheetId, "$sheetName!A:F").execute()
                val values = response.getValues()
                var rowIndexToDelete = -1
                values?.drop(1)?.forEachIndexed { index, row ->
                    val rowBarcode = row.getOrNull(1)?.toString()
                    val rowLocationId = row.getOrNull(3)?.toString()
                    val rowQuantity = row.getOrNull(4)?.toString()?.toIntOrNull()
                    val rowUser = row.getOrNull(5)?.toString()
                    if (rowBarcode == binding.itemBarcode.text.toString() &&
                        rowLocationId == entry.locationId &&
                        rowQuantity == entry.quantity &&
                        rowUser == entry.user) {
                        rowIndexToDelete = index + 2
                        return@forEachIndexed
                    }
                }

                if (rowIndexToDelete != -1) {
                    val deleteRequest = DeleteDimensionRequest().setRange(
                        DimensionRange()
                            .setSheetId(getSheetId(sheetsService, spreadsheetId, sheetName))
                            .setDimension("ROWS")
                            .setStartIndex(rowIndexToDelete - 1)
                            .setEndIndex(rowIndexToDelete)
                    )
                    val batchUpdateRequest = BatchUpdateSpreadsheetRequest().setRequests(listOf(Request().setDeleteDimension(deleteRequest)))
                    sheetsService.spreadsheets().batchUpdate(spreadsheetId, batchUpdateRequest).execute()

                    fetchStockEntriesFromGoogleSheet(sheetsService, spreadsheetId, binding.itemBarcode.text.toString())

                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@InventoryItemDetails, "Entry deleted.", Toast.LENGTH_SHORT).show()
                        countAdapter.notifyDataSetChanged()
                        updateCountAndDiff()
                    }
                } else {
                    throw Exception("Could not find the specific entry to delete.")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@InventoryItemDetails, "Error deleting entry: ${e.message}", Toast.LENGTH_LONG).show()
                    Log.e("DeleteEntry", "Failed to delete entry", e)
                }
            } finally {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                }
            }
        }
    }

    private fun setupNumpad(dialogView: View, inputDisplay: EditText) {
        inputDisplay.showSoftInputOnFocus = false
        val numpadButtons = mapOf(
            R.id.oneView to "1", R.id.twoView to "2", R.id.threeView to "3",
            R.id.fourView to "4", R.id.fiveView to "5", R.id.sixView to "6",
            R.id.sevenView to "7", R.id.eightView to "8", R.id.nineView to "9",
            R.id.zeroView to "0", R.id.dotView to "."
        )
        numpadButtons.forEach { (id, value) ->
            dialogView.findViewById<CardView>(id)?.setOnClickListener { inputDisplay.append(value) }
        }
        dialogView.findViewById<CardView>(R.id.backArrow)?.setOnClickListener {
            val currentText = inputDisplay.text
            if (currentText.isNotEmpty()) {
                inputDisplay.text.delete(currentText.length - 1, currentText.length)
            }
        }
    }

    private suspend fun fetchLocationData(sheetsService: Sheets, spreadsheetId: String) {
        try {
            val range = "product_location!A:D"
            val response = sheetsService.spreadsheets().values().get(spreadsheetId, range).execute()
            val values = response.getValues()?.drop(1) ?: return
            val fetchedLocations = values.mapNotNull { row ->
                if (row.size < 4) null
                else LocationItem(
                    id = row.getOrNull(0)?.toString()?.trim()?.uppercase(),
                    aisle = row.getOrNull(1)?.toString()?.trim(),
                    rack = row.getOrNull(2)?.toString()?.trim(),
                    shelf = row.getOrNull(3)?.toString()?.trim(),
                    barcode = null
                )
            }
            locationList.clear()
            locationList.addAll(fetchedLocations)
        } catch (e: Exception) {
            Log.e("fetchLocationData", "Error", e)
        }
    }

    private suspend fun fetchStockEntriesFromGoogleSheet(sheetsService: Sheets, spreadsheetId: String, currentBarcode: String) {
        val sheetName = "countData"
        try {
            ensureSheetExists(sheetsService, spreadsheetId, sheetName)
            val range = "$sheetName!A:F"
            val response = sheetsService.spreadsheets().values().get(spreadsheetId, range).execute()
            val values = response.getValues()?.drop(1) ?: return
            val entries = values.mapNotNull { row ->
                if (row.getOrNull(1)?.toString()?.trim() != currentBarcode) null
                else {
                    val locationId = row.getOrNull(3)?.toString() ?: ""
                    val correspondingLocation = locationList.firstOrNull { it.id.equals(locationId, ignoreCase = true) }
                    CountEntry(
                        productName = row.getOrNull(2)?.toString() ?: "",
                        locationId = locationId,
                        aisle = correspondingLocation?.aisle ?: "N/A",
                        rack = correspondingLocation?.rack ?: "N/A",
                        shelf = correspondingLocation?.shelf ?: "N/A",
                        quantity = row.getOrNull(4)?.toString()?.toIntOrNull() ?: 0,
                        user = row.getOrNull(5)?.toString()
                    )
                }
            }
            countList.clear()
            countList.addAll(entries)
        } catch (e: Exception) {
            Log.e("FetchEntriesError", "Failed", e)
        }
    }

    private fun getSheetId(sheetsService: Sheets, spreadsheetId: String, sheetName: String): Int? {
        val spreadsheet = sheetsService.spreadsheets().get(spreadsheetId).execute()
        return spreadsheet.sheets.firstOrNull { it.properties.title == sheetName }?.properties?.sheetId
    }

    private fun checkUserRole(email: String, callback: (isAdmin: Boolean, exists: Boolean, parentEmail: String?) -> Unit) {
        val client = OkHttpClient()
        val request = Request.Builder()
            .url("https://opensheet.elk.sh/1W-LOkSgPPrfhZ_kqfycUvOcGviQplMng6xpc6KBQ9Ik/users")
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { callback(false, false, null) }
            }
            override fun onResponse(call: Call, response: Response) {
                var isFound = false
                if (response.isSuccessful) {
                    try {
                        val jsonArray = JSONArray(response.body?.string() ?: "")
                        for (i in 0 until jsonArray.length()) {
                            val obj = jsonArray.getJSONObject(i)
                            val mainEmail = obj.optString("email").trim()
                            val subEmail = obj.optString("email sub user").trim()
                            val adminFlag = obj.optString("admin") == "1"
                            if (email.equals(mainEmail, ignoreCase = true)) {
                                runOnUiThread { callback(adminFlag, true, null) }
                                isFound = true; break
                            }
                            if (subEmail.isNotEmpty() && email.equals(subEmail, ignoreCase = true)) {
                                runOnUiThread { callback(false, true, mainEmail) }
                                isFound = true; break
                            }
                        }
                    } catch (e: JSONException) { /* Handle error */ }
                }
                if (!isFound) runOnUiThread { callback(false, false, null) }
            }
        })
    }

    private fun getDriveService(account: GoogleSignInAccount): Drive {
        val credential = GoogleAccountCredential.usingOAuth2(this, listOf(DriveScopes.DRIVE)).apply { selectedAccountName = account.email }
        return Drive.Builder(GoogleNetHttpTransport.newTrustedTransport(), GsonFactory.getDefaultInstance(), credential).setApplicationName("Nia Bridge App").build()
    }

    private fun getSheetsService(account: GoogleSignInAccount): Sheets {
        val credential = GoogleAccountCredential.usingOAuth2(this, listOf(SheetsScopes.SPREADSHEETS)).apply { selectedAccountName = account.email }
        return Sheets.Builder(GoogleNetHttpTransport.newTrustedTransport(), GsonFactory.getDefaultInstance(), credential).setApplicationName("Nia Bridge App").build()
    }

    private suspend fun findSheetIdByName(driveService: Drive, name: String): String? = withContext(Dispatchers.IO) {
        driveService.files().list()
            .setQ("name='$name' and mimeType='application/vnd.google-apps.spreadsheet' and trashed=false")
            .setSpaces("drive").setCorpus("user").setFields("files(id)").execute()
            .files.firstOrNull()?.id
    }

    private suspend fun ensureSheetExists(sheetsService: Sheets, spreadsheetId: String, sheetName: String) {
        withContext(Dispatchers.IO) {
            val spreadsheet = sheetsService.spreadsheets().get(spreadsheetId).execute()
            if (spreadsheet.sheets.any { it.properties.title.equals(sheetName, ignoreCase = true) }) return@withContext
            val addSheetRequest = AddSheetRequest().setProperties(SheetProperties().setTitle(sheetName))
            val batchUpdateRequest = BatchUpdateSpreadsheetRequest().setRequests(listOf(Request().setAddSheet(addSheetRequest)))
            sheetsService.spreadsheets().batchUpdate(spreadsheetId, batchUpdateRequest).execute()
            val headers = listOf(listOf("Timestamp", "Barcode", "Product Name", "Location ID", "Quantity", "User"))
            val valueRange = ValueRange().setValues(headers)
            sheetsService.spreadsheets().values().update(spreadsheetId, "$sheetName!A1", valueRange).setValueInputOption("USER_ENTERED").execute()
        }
    }

    private fun showLoader(message: String): AlertDialog {
        val progressView = LayoutInflater.from(this).inflate(R.layout.page_loader, null)
        progressView.findViewById<TextView>(R.id.loaderMessage).text = message
        return AlertDialog.Builder(this).setView(progressView).setCancelable(false).create().apply { show() }
    }

    private fun convertDriveUrlToDirect(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val fileId = Regex("""/d/([a-zA-Z0-9_-]+)|id=([a-zA-Z0-9_-]+)""").find(url)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
        return if (fileId != null) "https://drive.google.com/uc?export=download&id=$fileId" else url
    }

    private class NothingSelectedSpinnerAdapter(context: Context, resource: Int, objects: List<String>) : ArrayAdapter<String>(context, resource, objects) {
        override fun isEnabled(position: Int): Boolean = position != 0
        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = super.getDropDownView(position, convertView, parent) as TextView
            view.setTextColor(if (position == 0) Color.GRAY else Color.BLACK)
            return view
        }
    }
    //</editor-fold>
}
