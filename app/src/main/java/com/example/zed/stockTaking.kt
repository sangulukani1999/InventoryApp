package com.example.zed

import MyPageAdapter
import android.annotation.SuppressLint
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView // Correct import
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.example.zed.databinding.ActivityStockTakingBinding
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.SheetsScopes
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONArray
import org.json.JSONException
import java.io.IOException

class stockTaking : AppCompatActivity() {

    private lateinit var binding: ActivityStockTakingBinding
    private lateinit var pageAdapter: MyPageAdapter

    // --- Properties for Live Search Suggestions ---
    private val allProductsForSearch = mutableListOf<Product>()
    private lateinit var suggestionAdapter: androidx.cursoradapter.widget.CursorAdapter

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivityStockTakingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Call all setup functions
        setupClickListeners()
        setupTabsAndViewPager()
        setupSearchView()

        // Fetch the product list needed for search suggestions
        fetchProductsForSearch()
    }

    private fun setupClickListeners() {
        binding.backBtnPhysicalInventory.setOnClickListener {
            startActivity(Intent(this, PhysicalInventory::class.java))
            finish()
        }

        binding.varianceAdd.setOnClickListener {
            handleVarianceAddClick()
        }

        binding.barcodeScanner3.setOnClickListener {
            val scannerDialog = BarcodeScannerDialogFragment { scannedBarcode ->
                validateBarcodeAndNavigate(scannedBarcode)
            }
            scannerDialog.show(supportFragmentManager, "StockTakingScannerDialog")
        }
    }

    private fun signOut() {
        AlertDialog.Builder(this)
            .setTitle("Confirm Logout")
            .setMessage("Are you sure you want to log out?")
            .setPositiveButton("Logout") { _, _ ->
                Firebase.auth.signOut()
                val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build()
                val googleSignInClient = GoogleSignIn.getClient(this, gso)
                googleSignInClient.signOut().addOnCompleteListener {
                    Toast.makeText(this, "Logged out successfully.", Toast.LENGTH_SHORT).show()
                    val intent = Intent(this, signup_dashboard_activity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    startActivity(intent)
                    finish()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupTabsAndViewPager() {
        // 1. Set up the adapter for the ViewPager
        pageAdapter = MyPageAdapter(this)
        binding.ViewPager.adapter = pageAdapter

        val tabs = listOf("Inventory", "Not Found")

        // 2. Use TabLayoutMediator to link the TabLayout and ViewPager
        // This is the modern, correct, and simple way to do it.
        // It handles selecting, swiping, and updating tab text all in one.
        TabLayoutMediator(binding.tabLayout, binding.ViewPager) { tab, position ->
            tab.text = tabs[position]

            // Apply custom view styling here if you still need it.
            // For simplicity, we are using the default text tab style first.
            // If you use a custom view, you must inflate it and set it for each tab.
            val view = LayoutInflater.from(this).inflate(R.layout.custom_tab_layout, null)
            val tabTextView = view.findViewById<TextView>(R.id.tabText)
            tabTextView.text = tabs[position]
            tab.customView = view

        }.attach() // Don't forget to call attach()!

        // 3. Add a listener to apply your custom active/inactive styles
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                // Update background and margins for the selected tab
                tab.customView?.let { view ->
                    view.setBackgroundResource(R.drawable.tab_active)
                    val params = view.layoutParams as ViewGroup.MarginLayoutParams
                    params.topMargin = -35 // Move up
                    view.layoutParams = params
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {
                // Reset background and margins for the unselected tab
                tab.customView?.let { view ->
                    view.setBackgroundResource(0) // No background
                    val params = view.layoutParams as ViewGroup.MarginLayoutParams
                    params.topMargin = 0 // Reset position
                    view.layoutParams = params
                }
            }

            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        // 4. Manually select the first tab to ensure it's active on initial load
        // This is necessary because the onTabSelected listener isn't always called for the first item.
        binding.ViewPager.post {
            binding.tabLayout.getTabAt(0)?.customView?.let { view ->
                view.setBackgroundResource(R.drawable.tab_active)
                val params = view.layoutParams as ViewGroup.MarginLayoutParams
                params.topMargin = -35
                view.layoutParams = params
            }
        }
    }


    private fun setupSearchView() {
        suggestionAdapter = SuggestionAdapter(this, MatrixCursor(arrayOf("_id", "productName", "productBarcode")))
        binding.searchView.suggestionsAdapter = suggestionAdapter

        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                val firstMatch = allProductsForSearch.firstOrNull { it.name.contains(query ?: "", ignoreCase = true) }
                if (firstMatch != null) {
                    validateBarcodeAndNavigate(firstMatch.barcode)
                } else {
                    Toast.makeText(this@stockTaking, "No product found for '$query'", Toast.LENGTH_SHORT).show()
                }
                binding.searchView.clearFocus()
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                (supportFragmentManager.findFragmentByTag("f" + binding.ViewPager.currentItem) as? SearchableFragment)?.filterData(newText)
                updateSearchSuggestions(newText)
                return true
            }
        })

        binding.searchView.setOnSuggestionListener(object : SearchView.OnSuggestionListener {
            override fun onSuggestionSelect(position: Int): Boolean = true

            override fun onSuggestionClick(position: Int): Boolean {
                (suggestionAdapter.getItem(position) as? Cursor)?.let {
                    val barcodeIndex = it.getColumnIndex("productBarcode")
                    if (barcodeIndex != -1) {
                        validateBarcodeAndNavigate(it.getString(barcodeIndex))
                    }
                }
                binding.searchView.setQuery("", false)
                binding.searchView.clearFocus()
                return true
            }
        })
    }

    private fun fetchProductsForSearch() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val account = GoogleSignIn.getLastSignedInAccount(this@stockTaking) ?: return@launch
                val sheetsService = getSheetsService(account)
                val driveService = getDriveService(account)
                val spreadsheetId = findSheetIdByName(driveService, "nia-bridge data") ?: return@launch

                val productsRange = "Products!A:D"
                val productsResponse = sheetsService.spreadsheets().values().get(spreadsheetId, productsRange).execute()
                val productList = productsResponse.getValues()?.drop(1)?.mapNotNull { row ->
                    if (row.size < 4) null
                    else Product(
                        id = row.getOrNull(0)?.toString() ?: "",
                        name = row.getOrNull(1)?.toString() ?: "",
                        imageUrl = row.getOrNull(2)?.toString() ?: "",
                        barcode = row.getOrNull(3)?.toString() ?: "",
                        categoryId = "", unit = "", caseQty = "", minOrder = "", unitCost = "", locationIds = emptyList(),
                        expiryDate = ""
                    )
                } ?: emptyList()

                withContext(Dispatchers.Main) {
                    allProductsForSearch.clear()
                    allProductsForSearch.addAll(productList)
                    Log.d("LiveSearch", "Fetched ${allProductsForSearch.size} products for search suggestions.")
                }
            } catch (e: Exception) {
                Log.e("FetchSearchProducts", "Failed to fetch product list for search", e)
            }
        }
    }

    private fun updateSearchSuggestions(query: String?) {
        val newCursor = MatrixCursor(arrayOf("_id", "productName", "productBarcode"))
        if (!query.isNullOrBlank()) {
            allProductsForSearch.filter {
                it.name.contains(query, ignoreCase = true) || it.barcode.contains(query, ignoreCase = true)
            }.take(5).forEachIndexed { index, product ->
                newCursor.addRow(arrayOf(index, product.name, product.barcode))
            }
        }
        suggestionAdapter.changeCursor(newCursor)
    }

    // In stockTaking.kt

    private fun handleVarianceAddClick() {
        val currentUser = Firebase.auth.currentUser
        if (currentUser?.email == null) {
            Toast.makeText(this, "Cannot commit: User not signed in.", Toast.LENGTH_SHORT).show()
            return
        }
        val userEmail = currentUser.email!!

        val progressDialog = ProgressDialog(this).apply {
            setMessage("Preparing commit data...")
            setCancelable(false)
            show()
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val account = GoogleSignIn.getLastSignedInAccount(this@stockTaking)
                    ?: throw IllegalStateException("User is not signed in.")

                val sheetsService = getSheetsService(account)
                val driveService = getDriveService(account)
                val spreadsheetId = findSheetIdByName(driveService, "nia-bridge data")
                    ?: throw IllegalStateException("Spreadsheet 'nia-bridge data' not found.")

                // Fetch all data needed for the review sheet
                val products = fetchProductsForCommit(sheetsService, spreadsheetId)
                val countedQuantities = fetchCountedQuantitiesForCommit(sheetsService, spreadsheetId)
                val productsMap = products.associateBy { it.barcode }
                val allReviewItems = mutableListOf<CommitItem>()

                for ((barcode, countedData) in countedQuantities) {
                    val product = productsMap[barcode] ?: continue
                    val countedQty = countedData.first
                    val countedBy = countedData.second
                    val systemStock = product.caseQty.toDoubleOrNull() ?: 0.0
                    val variance = countedQty.toDouble() - systemStock

                    allReviewItems.add(
                        CommitItem(
                            productName = product.name,
                            barcode = product.barcode,
                            imageUrl = product.imageUrl,
                            variance = variance,
                            countedQty = countedQty,
                            unitCost = product.unitCost.toDoubleOrNull() ?: 0.0,
                            locations = emptyList(),
                            countedBy = countedBy
                        )
                    )
                }

                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    if (allReviewItems.isEmpty()) {
                        Toast.makeText(this@stockTaking, "No counted items found to review.", Toast.LENGTH_SHORT).show()
                    } else {
                        // This function provides the parentEmail, which can be null
                        checkUserRole(userEmail) { exists, parentEmail ->
                            if (exists) {
                                bottom_sheet_commit(
                                    userEmail = userEmail,
                                    parentEmail = parentEmail, // Pass the parentEmail, which can be null
                                    initialItems = allReviewItems,
                                    onStockAdded = {
                                        // Refresh data after commit
                                        // You might need to call a function here to reload the inventory list
                                    }
                                ).show(supportFragmentManager, "CommitBottomSheet")
                            } else {
                                Toast.makeText(this@stockTaking, "Access denied. User not found in registry.", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Log.e("handleVarianceAddClick", "Error preparing commit: ${e.message}", e)
                    Toast.makeText(this@stockTaking, "Error preparing commit: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // You also need to add these helper functions to stockTaking.kt
    private suspend fun fetchProductsForCommit(sheetsService: Sheets, spreadsheetId: String): List<Product> {
        val productsRange = "Products!A:K"
        val response = sheetsService.spreadsheets().values().get(spreadsheetId, productsRange).execute()
        val values = response.getValues() ?: return emptyList()
        return values.drop(1).mapNotNull { row ->
            val barcode = row.getOrNull(3)?.toString()?.trim()
            if (barcode.isNullOrBlank()) return@mapNotNull null
            Product(
                id = row.getOrNull(0)?.toString() ?: "", name = row.getOrNull(1)?.toString() ?: "",
                imageUrl = row.getOrNull(2)?.toString(), barcode = barcode,
                caseQty = row.getOrNull(6)?.toString() ?: "0", unitCost = row.getOrNull(8)?.toString() ?: "0.00",
                categoryId = "", unit = "", minOrder = "", expiryDate = "", locationIds = emptyList()
            )
        }
    }

    private suspend fun fetchCountedQuantitiesForCommit(sheetsService: Sheets, spreadsheetId: String): Map<String, Pair<Int, String>> {
        val map = mutableMapOf<String, Pair<Int, String>>()
        val countDataRange = "countData!B:F"
        try {
            val response = sheetsService.spreadsheets().values().get(spreadsheetId, countDataRange).execute()
            val values = response.getValues()?.drop(1)
            values?.forEach { row ->
                val barcode = row.getOrNull(0)?.toString()?.trim()
                val quantity = row.getOrNull(3)?.toString()?.toIntOrNull() ?: 0
                val user = row.getOrNull(4)?.toString()?.trim() ?: "unknown"
                if (!barcode.isNullOrBlank()) {
                    val current = map.getOrDefault(barcode, Pair(0, user))
                    map[barcode] = Pair(current.first + quantity, user)
                }
            }
        } catch (e: Exception) {
            Log.w("fetchCountedQuantities", "Could not fetch from countData: ${e.message}")
        }
        return map
    }


    private fun validateBarcodeAndNavigate(barcode: String) {
        if (barcode.isBlank()) {
            Toast.makeText(this, "Scanned an empty barcode.", Toast.LENGTH_SHORT).show()
            return
        }
        val productExists = allProductsForSearch.any { it.barcode.trim() == barcode.trim() }
        if (productExists) {
            Toast.makeText(this, "Product found. Loading details...", Toast.LENGTH_SHORT).show()
            val intent = Intent(this, InventoryItemDetails::class.java).apply {
                putExtra("inventoryBarcodes", barcode)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
        } else {
            AlertDialog.Builder(this)
                .setTitle("Not Found")
                .setMessage("Product with barcode '$barcode' was not found.")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun checkUserRole(email: String, callback: (exists: Boolean, parentEmail: String?) -> Unit) {
        val client = OkHttpClient()
        val request = Request.Builder()
            .url("https://opensheet.elk.sh/1W-LOkSgPPrfhZ_kqfycUvOcGviQplMng6xpc6KBQ9Ik/users")
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { runOnUiThread { callback(false, null) } }
            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) { runOnUiThread { callback(false, null) }; return }
                try {
                    val jsonArray = JSONArray(response.body?.string() ?: "")
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        val mainEmail = obj.optString("email").trim()
                        val subEmail = obj.optString("email sub user").trim()
                        if (email.equals(mainEmail, ignoreCase = true) || (subEmail.isNotEmpty() && email.equals(subEmail, ignoreCase = true))) {
                            runOnUiThread { callback(true, if (email.equals(subEmail, ignoreCase = true)) mainEmail else null) }
                            return
                        }
                    }
                    runOnUiThread { callback(false, null) }
                } catch (e: JSONException) {
                    Log.e("UserRoleCheck", "JSON parsing error", e)
                    runOnUiThread { callback(false, null) }
                }
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

    private fun dpToPx(context: Context, dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}
