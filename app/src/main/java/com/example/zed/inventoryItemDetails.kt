package com.example.zed

import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cursoradapter.widget.CursorAdapter
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
import com.google.api.client.http.FileContent
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.Permission
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
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*

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

    // --- Properties for Search ---
    private val allProductsForSearch = mutableListOf<Product>()
    private lateinit var suggestionAdapter: androidx.cursoradapter.widget.CursorAdapter
    private val suggestionsCursor = MatrixCursor(arrayOf("_id", "productName", "productBarcode"))

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

        // Initialize the suggestion adapter for the search view
        suggestionAdapter = SuggestionAdapter(this, suggestionsCursor)
        // This now works because the SearchView in XML is the AppCompat version
        binding.searchView.suggestionsAdapter = suggestionAdapter

        // Call setup functions once
        setupRecyclerViews()
        setupClickListeners()
        setupSearchView()

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
        binding.backBtnPhysicalInventory.setOnClickListener { finish() }
        // Listener for the primary action: adding a new count entry.
        binding.addConstraint.setOnClickListener {
            showAddEntryDialog()
        }

        // ✅ CRITICAL FIX: The ID is 'barcodeScannerIcon', not 'barcodeScanner'. This prevents the crash.
        binding.barcodeScanner.setOnClickListener {
            val scannerDialog = BarcodeScannerDialogFragment { scannedBarcode ->
                validateBarcodeAndNavigate(scannedBarcode)
            }
            scannerDialog.show(supportFragmentManager, "DetailsScannerDialog")
        }
        binding.reloadBtn.setOnClickListener {
            // Get the current barcode from the TextView on the screen.
            val currentBarcode = binding.itemBarcode.text.toString()
            if (currentBarcode.isNotBlank()) {
                Toast.makeText(this, "Refreshing counted entries...", Toast.LENGTH_SHORT).show()

                // Launch a coroutine to re-fetch only the counted data.
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val account = GoogleSignIn.getLastSignedInAccount(this@InventoryItemDetails)
                            ?: throw IllegalStateException("User is not signed in.")
                        val sheetsService = getSheetsService(account)
                        val spreadsheetId = findSheetIdByName(getDriveService(account), "nia-bridge data")
                            ?: throw IllegalStateException("Spreadsheet 'nia-bridge data' not found.")

                        // Call the existing function to fetch the counts.
                        fetchStockEntriesFromGoogleSheet(sheetsService, spreadsheetId, currentBarcode)

                        // Switch back to the main thread to update the UI.
                        withContext(Dispatchers.Main) {
                            countAdapter.notifyDataSetChanged() // Refresh the RecyclerView
                            updateCountAndDiff() // Recalculate the variance
                            Toast.makeText(this@InventoryItemDetails, "Data reloaded!", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Log.e("ReloadCountData", "Failed to refresh counted data", e)
                            Toast.makeText(this@InventoryItemDetails, "Error reloading: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } else {
                Toast.makeText(this, "Cannot reload: No product is loaded.", Toast.LENGTH_SHORT).show()
            }
        }
        // Listener for the image search icon, using the object detection flow.
        binding.imageSearchIcon.setOnClickListener {
            val imageScannerDialog = ImageScannerDialogFragment { capturedImageUri ->
                // This is the callback that receives the Uri of the captured image
                findProductByImage(capturedImageUri)
            }
            imageScannerDialog.show(supportFragmentManager, "DetailsImageScannerDialog")
        }
        binding.reloadBtn.setOnClickListener {
            // Get the current barcode from the TextView on the screen.
            val currentBarcode = binding.itemBarcode.text.toString()
            if (currentBarcode.isNotBlank()) {
                Toast.makeText(this, "Reloading data...", Toast.LENGTH_SHORT).show()
                // Just call the main data fetching function again.
                fetchProductDetails(currentBarcode)
            } else {
                Toast.makeText(this, "Cannot reload: No product is loaded.", Toast.LENGTH_SHORT).show()
            }
        }
        // ✅ --- START: ADD THE PREV/NEXT LISTENERS ---

        binding.PrevBtn.setOnClickListener {
            navigateToAdjacentProduct(direction = -1) // -1 for previous
        }

        binding.NextBtn.setOnClickListener {
            navigateToAdjacentProduct(direction = +1) // +1 for next
        }
        // ✅ --- END: ADD THE PREV/NEXT LISTENERS ---

    }

    /**
     * Navigates to the previous or next product in the list.
     * @param direction -1 for previous, +1 for next.
     */
    private fun navigateToAdjacentProduct(direction: Int) {
        val currentBarcode = binding.itemBarcode.text.toString()

        if (allProductsForSearch.isEmpty()) {
            Toast.makeText(this, "Product list not loaded yet.", Toast.LENGTH_SHORT).show()
            return
        }

        // Find the index of the current product in our master list.
        val currentIndex = allProductsForSearch.indexOfFirst { it.barcode == currentBarcode }

        if (currentIndex == -1) {
            Toast.makeText(this, "Could not find current product in the list.", Toast.LENGTH_SHORT).show()
            return
        }

        // Calculate the index of the next product.
        val nextIndex = currentIndex + direction

        // Check if the new index is valid (within the bounds of the list).
        if (nextIndex in allProductsForSearch.indices) {
            val nextProductBarcode = allProductsForSearch[nextIndex].barcode
            Toast.makeText(this, "Loading ${if(direction > 0) "next" else "previous"} product...", Toast.LENGTH_SHORT).show()

            // Use your existing navigation function to load the new product.
            validateBarcodeAndNavigate(nextProductBarcode)
        } else {
            // Let the user know they are at the beginning or end of the list.
            if (direction > 0) {
                Toast.makeText(this, "You are at the last product.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "You are at the first product.", Toast.LENGTH_SHORT).show()
            }
        }
    }
    // ✅ --- END: ADD THE NAVIGATION LOGIC ---


    /**
     * Sets up the listeners for the SearchView.
     */
    private fun setupSearchView() {
        binding.searchView.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                if (!query.isNullOrBlank()) {
                    // User pressed search. Open the main fragment with a filter.
                    val intent = Intent(this@InventoryItemDetails, dashboardActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        putExtra("SEARCH_QUERY", query)
                    }
                    startActivity(intent)
                    finish()
                }
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                updateSearchSuggestions(newText)
                return true
            }
        })

        binding.searchView.setOnSuggestionListener(object : androidx.appcompat.widget.SearchView.OnSuggestionListener {
            override fun onSuggestionSelect(position: Int): Boolean {
                return true
            }

            override fun onSuggestionClick(position: Int): Boolean {
                // User clicks a suggestion. Open that product's details.
                val cursor = suggestionAdapter.getItem(position) as Cursor
                val barcode = cursor.getString(2) // "productBarcode" is at column index 2

                validateBarcodeAndNavigate(barcode)

                binding.searchView.setQuery("", false)
                binding.searchView.clearFocus()
                return true
            }
        })
    }

    /**
     * Filters the `allProductsForSearch` list and updates the `MatrixCursor` for the suggestions.
     */
    private fun updateSearchSuggestions(query: String?) {
        // A hack to clear previous suggestions from the MatrixCursor
        val dummyCursor = MatrixCursor(arrayOf("_id", "productName", "productBarcode"))
        suggestionAdapter.changeCursor(dummyCursor)

        if (query.isNullOrBlank()) {
            return
        }

        val filteredProducts = allProductsForSearch.filter {
            it.name.contains(query, ignoreCase = true) || it.barcode.contains(query, ignoreCase = true)
        }.take(5) // Limit to 5 suggestions

        // Re-populate the cursor for the adapter
        val newCursor = MatrixCursor(arrayOf("_id", "productName", "productBarcode"))
        filteredProducts.forEachIndexed { index, product ->
            newCursor.addRow(arrayOf(index, product.name, product.barcode))
        }

        suggestionAdapter.changeCursor(newCursor)
    }

    /**
     * Validates a scanned barcode against the local product list before navigating.
     */
    // In inventoryItemDetails.kt

    /**
     * Validates a scanned barcode against the local product list before navigating.
     */
    // In inventoryItemDetails.kt

    private fun validateBarcodeAndNavigate(barcode: String) {
        if (barcode == binding.itemBarcode.text.toString()) {
            Toast.makeText(this, "This product is already loaded.", Toast.LENGTH_SHORT).show()
            binding.searchView.clearFocus()
            return
        }

        val progressDialog = showLoader("Verifying product...")

        lifecycleScope.launch { // No need for Dispatchers.IO for a simple list search
            val productExists = allProductsForSearch.any { it.barcode.trim() == barcode.trim() }

            progressDialog.dismiss()
            if (productExists) {
                Toast.makeText(this@InventoryItemDetails, "Product found. Loading details...", Toast.LENGTH_SHORT).show()
                val intent = Intent(this@InventoryItemDetails, InventoryItemDetails::class.java).apply {
                    putExtra("inventoryBarcodes", barcode)
                }
                startActivity(intent)

                // ✅ FIX: By removing this line, the previous activity is kept in the history.
                // finish()

            } else {
                AlertDialog.Builder(this@InventoryItemDetails)
                    .setTitle("Not Found")
                    .setMessage("Product with barcode '$barcode' was not found in your products list.")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }


    /**
     * Fetches all product data from the sheet, finds the current product to display,
     * and then fetches related data like locations and units.
     */
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

                // --- 1. EFFICIENTLY FETCH ALL PRODUCT DATA AT ONCE ---
                val productsRange = "Products!A:L"
                val productsResponse = sheetsService.spreadsheets().values().get(spreadsheetId, productsRange).execute()
                val allProductRows = productsResponse.getValues()?.drop(1) ?: emptyList()

                // --- 2. Populate the list for searching ---
                val productListForSearch = allProductRows.mapNotNull { row ->
                    if (row.size < 10) return@mapNotNull null
                    Product(
                        id = row.getOrNull(0)?.toString() ?: "",
                        name = row.getOrNull(1)?.toString() ?: "",
                        imageUrl = row.getOrNull(2)?.toString() ?: "",
                        barcode = row.getOrNull(3)?.toString() ?: "",
                        categoryId = row.getOrNull(4)?.toString() ?: "",
                        unit = row.getOrNull(5)?.toString() ?: "",
                        caseQty = row.getOrNull(6)?.toString() ?: "0",
                        minOrder = row.getOrNull(7)?.toString() ?: "0",
                        unitCost = row.getOrNull(8)?.toString() ?: "0.00",
                        locationIds = row.getOrNull(9)?.toString()?.removeSurrounding("['", "']")?.split("', '")?.filter { it.isNotBlank() } ?: emptyList(),
                        expiryDate = row.getOrNull(10)?.toString() ?: ""
                    )
                }
                allProductsForSearch.clear()
                allProductsForSearch.addAll(productListForSearch)

                // --- 3. Find the CURRENT product row to display from the fetched data ---
                val productRow = allProductRows.firstOrNull { it.getOrNull(3)?.toString()?.trim() == barcode }
                if (productRow == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@InventoryItemDetails, "Product details could not be loaded.", Toast.LENGTH_LONG).show()
                        finish()
                    }
                    return@launch
                }

                // --- 4. Fetch other related data ---
                coroutineScope {
                    launch { fetchLocationData(sheetsService, spreadsheetId) }
                    launch { fetchStockEntriesFromGoogleSheet(sheetsService, spreadsheetId, barcode) }
                    launch { fetchUnitData(sheetsService, spreadsheetId, barcode) }
                }

                // --- 5. Process and Display UI ---
                val productName = productRow.getOrNull(1)?.toString() ?: "N/A"
                val imageUrl = productRow.getOrNull(2)?.toString()
                val stockInCases = productRow.getOrNull(6)?.toString()?.toDoubleOrNull() ?: 0.0
                val rawLocationString = productRow.getOrNull(9)?.toString() ?: ""
                val highestUnit = unitList.mapNotNull { it.caseUnits.toIntOrNull() }.maxOrNull() ?: 1
                val totalStockInUnits = stockInCases

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


    //<editor-fold desc="Helper functions for Image Search, UI and Data Fetching">

    /**
     * Uploads the captured image to Google Drive to get a public URL,
     * then searches the local product list for a matching URL.
     */
    private fun findProductByImage(imageUri: Uri) {
        val progress = showLoader("Analyzing image...")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val account = GoogleSignIn.getLastSignedInAccount(this@InventoryItemDetails)
                    ?: throw Exception("Not signed in")
                val driveService = getDriveService(account)

                // Step 1: Create a temporary file from the captured image Uri
                val tempImageFile = createTempFileFromUri(imageUri)
                // Step 2: Find or create the "nia-bridge" folder in Google Drive
                val folderId = getOrCreateNiaBridgeFolder(driveService)

                // Step 3: Upload the image to that folder
                val fileMetadata = com.google.api.services.drive.model.File().apply {
                    name = tempImageFile.name
                    mimeType = "image/jpeg"
                    parents = listOf(folderId)
                }
                val mediaContent = FileContent("image/jpeg", tempImageFile)
                withContext(Dispatchers.Main) { progress.setMessage("Uploading for analysis...") }

                val uploadedFile = driveService.files().create(fileMetadata, mediaContent)
                    .setFields("id")
                    .execute()

                // Step 4: Make the uploaded file public
                driveService.permissions().create(uploadedFile.id, Permission().apply { type = "anyone"; role = "reader" }).execute()
                val publicUrl = "https://drive.google.com/uc?id=${uploadedFile.id}"
                Log.d("ImageSearch", "Uploaded image public URL: $publicUrl")

                // Step 5: Search the local product list for a match
                val matchedProducts = allProductsForSearch.filter {
                    // This comparison is very strict. It requires the URLs to be identical.
                    it.imageUrl?.trim() == publicUrl
                }

                withContext(Dispatchers.Main) {
                    progress.dismiss()
                    handleProductMatches(matchedProducts)
                }
                // Clean up the temporary uploaded file if no match was found.
                if (matchedProducts.isEmpty()) {
                    deleteFileFromDrive(driveService, uploadedFile.id)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progress.dismiss()
                    Toast.makeText(this@InventoryItemDetails, "Error analyzing image: ${e.message}", Toast.LENGTH_LONG).show()
                    Log.e("ImageSearch", "Error during image search", e)
                }
            }
        }
    }

    private fun handleProductMatches(matchedProducts: List<Product>) {
        when {
            matchedProducts.isEmpty() -> {
                Toast.makeText(this, "No product found for the scanned image.", Toast.LENGTH_LONG).show()
            }
            matchedProducts.size == 1 -> {
                val barcode = matchedProducts[0].barcode
                if (barcode.isNotBlank()) {
                    validateBarcodeAndNavigate(barcode)
                } else {
                    Toast.makeText(this, "Found product has no barcode.", Toast.LENGTH_SHORT).show()
                }
            }
            else -> {
                showProductSelectionDialog(matchedProducts)
            }
        }
    }

    private fun showProductSelectionDialog(products: List<Product>) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_product_selection, null)
        val container = dialogView.findViewById<LinearLayout>(R.id.productSearchContainer) // ✅ Corrected ID
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setTitle("Select Product")
            .setNegativeButton("Cancel", null)
            .create()

        products.forEach { product ->
            val itemView = LayoutInflater.from(this).inflate(R.layout.product_item_row, container, false)
            val nameView = itemView.findViewById<TextView>(R.id.productNameInventory)
            val imageView = itemView.findViewById<ImageView>(R.id.productImage)

            nameView.text = product.name
            imageView.load(convertDriveUrlToDirect(product.imageUrl)) {
                placeholder(R.drawable.ic_placeholder)
                error(R.drawable.ic_placeholder)
            }

            itemView.setOnClickListener {
                if (product.barcode.isNotBlank()) {
                    validateBarcodeAndNavigate(product.barcode)
                } else {
                    Toast.makeText(this, "Selected product has no barcode.", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            container.addView(itemView)
        }
        dialog.show()
    }

    private suspend fun getOrCreateNiaBridgeFolder(driveService: Drive): String = withContext(Dispatchers.IO) {
        val folderQuery = "mimeType='application/vnd.google-apps.folder' and name='nia-bridge' and trashed=false and 'me' in owners"
        val folderList = driveService.files().list().setQ(folderQuery).execute()
        if (folderList.files.isNotEmpty()) {
            return@withContext folderList.files[0].id
        }
        val folderMetadata = com.google.api.services.drive.model.File().apply {
            name = "nia-bridge"
            mimeType = "application/vnd.google-apps.folder"
        }
        val folder = driveService.files().create(folderMetadata).setFields("id").execute()
        folder.id
    }

    private fun createTempFileFromUri(uri: Uri): java.io.File {
        val inputStream: InputStream = contentResolver.openInputStream(uri)
            ?: throw IOException("Unable to open input stream for URI: $uri")
        val tempFile = java.io.File.createTempFile("upload_", ".jpg", cacheDir)
        FileOutputStream(tempFile).use { outputStream ->
            inputStream.use { it.copyTo(outputStream) }
        }
        return tempFile
    }

    private suspend fun deleteFileFromDrive(driveService: Drive, fileId: String) = withContext(Dispatchers.IO) {
        try {
            driveService.files().delete(fileId).execute()
            Log.d("ImageSearch", "Cleaned up unmatched image file: $fileId")
        } catch (e: Exception) {
            Log.e("ImageSearch", "Failed to delete temporary file $fileId", e)
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

        val dialog = AlertDialog.Builder(this).setView(dialogView).create()
        dialogView.findViewById<View>(R.id.OkView)?.setOnClickListener {
            val quantityEntered = inputDisplay.text.toString().toIntOrNull()
            if (quantityEntered == null || quantityEntered < 0) {
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
        binding.itemCounted.text = "Counted: $totalCounted"
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
            dialogView.findViewById<View>(id)?.setOnClickListener { inputDisplay.append(value) }
        }
        dialogView.findViewById<View>(R.id.backArrow)?.setOnClickListener {
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
        return try {
            val spreadsheet = sheetsService.spreadsheets().get(spreadsheetId).execute()
            spreadsheet.sheets.firstOrNull { it.properties.title == sheetName }?.properties?.sheetId
        } catch (e: Exception) {
            null
        }
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
                    } catch (e: JSONException) {
                        /* Handle error */
                    }
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
        return AlertDialog.Builder(this)
            .setView(progressView)
            .setCancelable(false)
            .create().apply { show() }
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

// ✅ This adapter is a top-level class. It has no secret link to any single activity instance.
// Now this line will work in ANY file, not just InventoryItemDetails.kt

