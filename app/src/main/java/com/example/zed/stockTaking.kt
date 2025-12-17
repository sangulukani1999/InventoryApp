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

        // Correct Order: Initialize binding FIRST to prevent crash.
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
        setupSearchView() // Setup for live search and suggestions

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

        // ✅ CRITICAL FIX: The ID must match your layout. Using 'scannerBtn' now.
        // This was crashing the app because 'barcodeScanner3' does not exist.
        binding.barcodeScanner3.setOnClickListener {
            val scannerDialog = BarcodeScannerDialogFragment { scannedBarcode ->
                validateBarcodeAndNavigate(scannedBarcode)
            }
            scannerDialog.show(supportFragmentManager, "StockTakingScannerDialog")
        }


    }

    /**
     * ✅ ADDED: Signs the user out of Firebase and Google, then returns to the login screen.
     */
    private fun signOut() {
        // Show a confirmation dialog first
        AlertDialog.Builder(this)
            .setTitle("Confirm Logout")
            .setMessage("Are you sure you want to log out?")
            .setPositiveButton("Logout") { _, _ ->
                // Sign out from Firebase
                Firebase.auth.signOut()

                // Sign out from Google
                val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build()
                val googleSignInClient = GoogleSignIn.getClient(this, gso)
                googleSignInClient.signOut().addOnCompleteListener {
                    Toast.makeText(this, "Logged out successfully.", Toast.LENGTH_SHORT).show()
                    // Navigate back to the signup/login activity
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
        pageAdapter = MyPageAdapter(this)
        binding.ViewPager.adapter = pageAdapter

        val tabs = listOf("Inventory", "Not Found")
        binding.tabLayout.removeAllTabs()

        tabs.forEach { tabName ->
            val tab = binding.tabLayout.newTab()
            val view = LayoutInflater.from(this).inflate(R.layout.custom_tab_layout, null)
            view.findViewById<TextView>(R.id.tabText).text = tabName
            tab.customView = view
            binding.tabLayout.addTab(tab)
        }

        binding.ViewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                binding.tabLayout.getTabAt(position)?.select()
            }
        })

        // RESTORED: Your original, detailed tab styling logic.
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                binding.ViewPager.currentItem = tab.position
                tab.customView?.let { view ->
                    view.setBackgroundResource(R.drawable.tab_active)
                    val params = view.layoutParams as ViewGroup.MarginLayoutParams
                    params.topMargin = -35
                    when (tab.position) {
                        0 -> {
                            params.marginStart = dpToPx(binding.tabLayout.context, 0)
                            params.marginEnd = 0
                        }
                        binding.tabLayout.tabCount - 1 -> {
                            params.marginStart = 0
                            params.marginEnd = dpToPx(binding.tabLayout.context, 3)
                        }
                        else -> {
                            params.marginStart = 0
                            params.marginEnd = 0
                        }
                    }
                    view.layoutParams = params
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {
                tab.customView?.let { view ->
                    view.setBackgroundResource(0)
                    val params = view.layoutParams as ViewGroup.MarginLayoutParams
                    params.topMargin = 0
                    when (tab.position) {
                        0 -> {
                            params.marginStart = dpToPx(binding.tabLayout.context, 20)
                            params.marginEnd = 0
                        }
                        binding.tabLayout.tabCount - 1 -> {
                            params.marginStart = 0
                            params.marginEnd = dpToPx(binding.tabLayout.context, 20)
                        }
                        else -> {
                            params.marginStart = 0
                            params.marginEnd = 0
                        }
                    }
                    view.layoutParams = params
                }
            }

            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        if (binding.tabLayout.tabCount > 0) {
            binding.tabLayout.getTabAt(0)?.select()
        }
    }

    /**
     * Sets up the SearchView for both live filtering of fragments and showing clickable suggestions.
     */
    private fun setupSearchView() {
        // Initialize the suggestion adapter with an empty cursor
        suggestionAdapter = SuggestionAdapter(this, MatrixCursor(arrayOf("_id", "productName", "productBarcode")))
        binding.searchView.suggestionsAdapter = suggestionAdapter

        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                // When user presses enter, navigate to the details page of the first matching suggestion
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
                // 1. Live filter the currently visible fragment's list
                val currentFragment = supportFragmentManager.findFragmentByTag("f" + binding.ViewPager.currentItem)
                if (currentFragment is SearchableFragment) {
                    currentFragment.filterData(newText)
                }

                // 2. Update the dropdown suggestions based on the full product list
                updateSearchSuggestions(newText)
                return true
            }
        })

        binding.searchView.setOnSuggestionListener(object : SearchView.OnSuggestionListener {
            override fun onSuggestionSelect(position: Int): Boolean = true

            override fun onSuggestionClick(position: Int): Boolean {
                val cursor = suggestionAdapter.getItem(position) as Cursor
                val barcodeIndex = cursor.getColumnIndex("productBarcode")
                if (barcodeIndex != -1) {
                    val barcode = cursor.getString(barcodeIndex)
                    validateBarcodeAndNavigate(barcode)
                }
                binding.searchView.setQuery("", false) // Clear search text
                binding.searchView.clearFocus() // Hide keyboard
                return true
            }
        })
    }

    /**
     * Fetches the full product list from the "Products" sheet to power search suggestions.
     */
    private fun fetchProductsForSearch() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val account = GoogleSignIn.getLastSignedInAccount(this@stockTaking) ?: return@launch
                val sheetsService = getSheetsService(account)
                val driveService = getDriveService(account)
                val spreadsheetId = findSheetIdByName(driveService, "nia-bridge data") ?: return@launch

                val productsRange = "Products!A:D" // Only need ID, Name, Image, Barcode for search
                val productsResponse = sheetsService.spreadsheets().values().get(spreadsheetId, productsRange).execute()
                val allProductRows = productsResponse.getValues()?.drop(1) ?: emptyList()

                val productList = allProductRows.mapNotNull { row ->
                    if (row.size < 4) null
                    else Product(
                        id = row.getOrNull(0)?.toString() ?: "",
                        name = row.getOrNull(1)?.toString() ?: "",
                        imageUrl = row.getOrNull(2)?.toString() ?: "",
                        barcode = row.getOrNull(3)?.toString() ?: "",
                        // Other fields are not needed for search suggestions
                        categoryId = "", unit = "", caseQty = "", minOrder = "", unitCost = "", locationIds = emptyList()
                    )
                }
                allProductsForSearch.clear()
                allProductsForSearch.addAll(productList)
                Log.d("LiveSearch", "Fetched ${allProductsForSearch.size} products for search suggestions.")
            } catch (e: Exception) {
                Log.e("FetchSearchProducts", "Failed to fetch product list for search", e)
            }
        }
    }

    /**
     * Filters the `allProductsForSearch` list and updates the suggestion adapter's cursor.
     */
    private fun updateSearchSuggestions(query: String?) {
        val newCursor = MatrixCursor(arrayOf("_id", "productName", "productBarcode"))
        if (!query.isNullOrBlank()) {
            val filteredProducts = allProductsForSearch.filter {
                it.name.contains(query, ignoreCase = true) || it.barcode.contains(query, ignoreCase = true)
            }.take(5) // Show up to 5 suggestions

            filteredProducts.forEachIndexed { index, product ->
                newCursor.addRow(arrayOf(index, product.name, product.barcode))
            }
        }
        suggestionAdapter.changeCursor(newCursor)
    }

    // --- Other Helper Functions (Unchanged) ---

    private fun handleVarianceAddClick() {
        val currentUser = Firebase.auth.currentUser
        if (currentUser?.email == null) {
            Toast.makeText(this, "Cannot add item: User not signed in.", Toast.LENGTH_SHORT).show()
            return
        }
        val userEmail = currentUser.email!!
        checkUserRole(userEmail) { exists, parentEmail ->
            if (exists) {
                bottom_sheet_commit(userEmail, parentEmail) {
                    // Optional: Refresh data after adding a new product
                }.show(supportFragmentManager, "AddProductBottomSheet")
            } else {
                Toast.makeText(this, "Access denied. User not found in registry.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun validateBarcodeAndNavigate(barcode: String) {
        if (barcode.isBlank()) {
            Toast.makeText(this, "Scanned an empty barcode.", Toast.LENGTH_SHORT).show()
            return
        }

        // Since fetchProductsForSearch() runs when the activity starts,
        // allProductsForSearch should be populated.
        // We can now check against this list directly.
        val productExists = allProductsForSearch.any { it.barcode.trim() == barcode.trim() }

        if (productExists) {
            // --- PRODUCT FOUND ---
            // Now that we've confirmed it exists, show the toast and navigate.
            Toast.makeText(this, "Product found. Loading details...", Toast.LENGTH_SHORT).show()
            val intent = Intent(this, InventoryItemDetails::class.java).apply {
                putExtra("inventoryBarcodes", barcode)
                // Using CLEAR_TASK is good practice to prevent a long back-stack of detail pages.
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
        } else {
            // --- PRODUCT NOT FOUND ---
            // Show an alert dialog here without navigating away.
            AlertDialog.Builder(this)
                .setTitle("Not Found")
                .setMessage("Product with barcode '$barcode' was not found in your products list.")
                .setPositiveButton("OK", null) // 'null' listener just closes the dialog
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
                        if (email.equals(mainEmail, ignoreCase = true)) { runOnUiThread { callback(true, null) }; return }
                        if (subEmail.isNotEmpty() && email.equals(subEmail, ignoreCase = true)) { runOnUiThread { callback(true, mainEmail) }; return }
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
