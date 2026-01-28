package com.example.zed

import android.annotation.SuppressLint
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import android.widget.Toast.makeText
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.graphics.values
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.size
import androidx.lifecycle.lifecycleScope
import com.example.zed.databinding.ActivityStockListBinding
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.SheetsScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ✅ Added missing imports
import com.example.zed.Product // Assuming Product is in this package
import com.example.zed.SuggestionAdapter // Assuming SuggestionAdapter is in this package

class stockList : AppCompatActivity() {

    private lateinit var binding: ActivityStockListBinding
    private lateinit var suggestionAdapter: androidx.cursoradapter.widget.CursorAdapter

    // Keep a reference to the ViewPager's adapter to access fragments
    private lateinit var stockPagerAdapter: stockAdapter

    private val allProductsForSearch = mutableListOf<Product>()

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStockListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        binding.backBtnPhysicalInventory.setOnClickListener {
            startActivity(Intent(this, PhysicalInventory::class.java))
            finish()
        }

        setupTabs()
        setupSearchView()
        fetchProductsForSearch()
    }

    private fun setupSearchView() {
        suggestionAdapter = SuggestionAdapter(this, MatrixCursor(arrayOf("_id", "productName", "productBarcode")))
        binding.searchView.suggestionsAdapter = suggestionAdapter

        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                val firstMatch = allProductsForSearch.firstOrNull { it.name.contains(query ?: "", ignoreCase = true) }
                if (firstMatch != null) {
                    // Use the new highlighting logic instead of navigating
                    findAndHighlightProduct(firstMatch.barcode)
                } else {
                    makeText(this@stockList, "No product found for '$query'", Toast.LENGTH_SHORT).show()
                }
                binding.searchView.clearFocus()
                binding.searchView.setQuery("", false)
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                // This logic is for live filtering within a fragment that implements SearchableFragment
                val currentFragment = stockPagerAdapter.getFragment(binding.tabContent.currentItem)
                if (currentFragment is SearchableFragment) {
                    currentFragment.filterData(newText)
                }
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
                        // Use the new highlighting logic instead of navigating
                        findAndHighlightProduct(it.getString(barcodeIndex))
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
                val account = GoogleSignIn.getLastSignedInAccount(this@stockList) ?: return@launch
                val sheetsService = getSheetsService(account)
                val driveService = getDriveService(account)
                val spreadsheetId = findSheetIdByName(driveService, "nia-bridge data") ?: return@launch

                val productsRange = "Products!A:D"
                val productsResponse = sheetsService.spreadsheets().values().get(spreadsheetId, productsRange).execute()

                // ✅ CORRECTED: Using getValues() method instead of property access
                val productList = productsResponse.getValues()?.drop(1)?.mapNotNull { row ->
                    // The rest of your code remains the same
                    if (row.size < 4) return@mapNotNull null
                    Product(
                        id = row.getOrNull(0)?.toString() ?: "",
                        name = row.getOrNull(1)?.toString() ?: "",
                        imageUrl = row.getOrNull(2)?.toString(),
                        barcode = row.getOrNull(3)?.toString() ?: "",
                        categoryId = "",
                        unit = "",
                        caseQty = "",
                        minOrder = "",
                        unitCost = "",
                        locationIds = emptyList(),
                        expiryDate = null
                    )
                } ?: emptyList()

                withContext(Dispatchers.Main) {
                    allProductsForSearch.clear()
                    allProductsForSearch.addAll(productList)
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

    /**
     * Replaces the old navigation logic. This function now switches to the products tab
     * and tells the fragment to find and highlight the specified item.
     */
    private fun findAndHighlightProduct(barcode: String) {
        if (barcode.isBlank()) {
            makeText(this, "Cannot search for an empty barcode.", Toast.LENGTH_SHORT).show()
            return
        }

        // 1. Switch the ViewPager2 to the "Products" tab (index 0).
        binding.tabContent.setCurrentItem(0, true)

        // 2. Use .post to ensure the fragment is ready before we interact with it.
        binding.tabContent.post {
            // Get the fragment instance from our adapter.
            val stockFragment = stockPagerAdapter.getFragment(0) as? stock_fragment
            if (stockFragment != null) {
                // Call a public method on the fragment to perform the highlighting.
                // IMPORTANT: You must add this `findAndHighlightItem` method to your `stock_fragment`.
                stockFragment.findAndHighlightItem(barcode)
            } else {
                Log.e("Highlight", "Could not get instance of stock_fragment at position 0.")
                makeText(this, "Products fragment is not ready.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupTabs() {
        // Instantiate and store the adapter for the ViewPager2.
        stockPagerAdapter = stockAdapter(this)
        binding.tabContent.adapter = stockPagerAdapter

        val tabs = listOf("Products", "Details", "Locations")

        // Use TabLayoutMediator to link the TabLayout and ViewPager2.
        TabLayoutMediator(binding.tabLayoutStock, binding.tabContent) { tab, position ->
            val customTabView = LayoutInflater.from(this).inflate(R.layout.custom_tab_layout, null)
            val tabText = customTabView.findViewById<TextView>(R.id.tabText)
            tabText.text = tabs[position]
            tab.customView = customTabView
        }.attach()

        binding.tabLayoutStock.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                tab.customView?.let { view ->
                    view.setBackgroundResource(R.drawable.tab_active)
                    (view.layoutParams as ViewGroup.MarginLayoutParams).also {
                        it.topMargin = -35
                        view.layoutParams = it
                    }
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {
                tab.customView?.let { view ->
                    view.setBackgroundResource(0)
                    (view.layoutParams as ViewGroup.MarginLayoutParams).also {
                        it.topMargin = 0
                        view.layoutParams = it
                    }
                }
            }

            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        // Manually style the first tab on initial load.
        binding.tabLayoutStock.post {
            binding.tabLayoutStock.getTabAt(0)?.customView?.let { view ->
                view.setBackgroundResource(R.drawable.tab_active)
                (view.layoutParams as ViewGroup.MarginLayoutParams).also {
                    it.topMargin = -35
                    view.layoutParams = it
                }
            }
        }
    }

    // --- Google API Helper Functions ---

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
}

