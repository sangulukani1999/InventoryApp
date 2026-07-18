package com.example.zed

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.zed.databinding.ActivityStockProductListBinding

class StockProductListActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_MODE =
            "stock_product_list_mode"

        fun newIntent(
            context: Context,
            mode: StockProductListMode
        ): Intent {
            return Intent(
                context,
                StockProductListActivity::class.java
            ).apply {
                putExtra(
                    EXTRA_MODE,
                    mode.name
                )
            }
        }
    }

    private lateinit var binding:
            ActivityStockProductListBinding

    private lateinit var adapter:
            StockProductListAdapter

    private var mode =
        StockProductListMode.COUNTED

    private var unfilteredProducts:
            List<StockTakeProduct> = emptyList()

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        binding =
            ActivityStockProductListBinding.inflate(
                layoutInflater
            )

        setContentView(binding.root)

        mode = readMode()

        setupSystemBars()
        setupInsets()
        setupToolbar()
        setupRecyclerView()
        setupSearch()
        loadProducts()
    }

    private fun readMode():
            StockProductListMode {

        val value =
            intent.getStringExtra(
                EXTRA_MODE
            )

        return try {
            StockProductListMode.valueOf(
                value ?: StockProductListMode
                    .COUNTED
                    .name
            )
        } catch (_: Exception) {
            StockProductListMode.COUNTED
        }
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }

        when (mode) {
            StockProductListMode.COUNTED -> {
                binding.toolbar.title =
                    "Counted Items"

                binding.summaryTitleText.text =
                    "Counted products"
            }

            StockProductListMode.REMAINING -> {
                binding.toolbar.title =
                    "Remaining Items"

                binding.summaryTitleText.text =
                    "Products still to count"
            }

            StockProductListMode.VARIANCE -> {
                binding.toolbar.title =
                    "Stock Variances"

                binding.summaryTitleText.text =
                    "Mismatched products"
            }
        }
    }

    private fun setupRecyclerView() {
        adapter =
            StockProductListAdapter(
                mode = mode,
                onProductClicked = {
                        product ->
                    handleProductClick(product)
                }
            )

        binding.productsRecyclerView.apply {
            layoutManager =
                LinearLayoutManager(
                    this@StockProductListActivity
                )

            adapter =
                this@StockProductListActivity
                    .adapter

            setHasFixedSize(true)
        }
    }

    private fun setupSearch() {
        binding.searchInput.addTextChangedListener(
            object : TextWatcher {

                override fun beforeTextChanged(
                    value: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) = Unit

                override fun onTextChanged(
                    value: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int
                ) {
                    filterProducts(
                        value?.toString().orEmpty()
                    )
                }

                override fun afterTextChanged(
                    value: Editable?
                ) = Unit
            }
        )
    }

    private fun loadProducts() {
        val products =
            when (mode) {
                StockProductListMode.COUNTED ->
                    StockTakeRepository
                        .countedProducts()

                StockProductListMode.REMAINING ->
                    StockTakeRepository
                        .remainingProducts()

                StockProductListMode.VARIANCE ->
                    StockTakeRepository
                        .mismatchedProducts()
            }
                .sortedWith(
                    compareBy<StockTakeProduct>(
                        {
                            it.aisle.lowercase()
                        },
                        {
                            it.rack.lowercase()
                        },
                        {
                            extractNumber(
                                it.shelf
                            )
                        },
                        {
                            it.shelf.lowercase()
                        },
                        {
                            it.productName
                                .lowercase()
                        }
                    )
                )

        unfilteredProducts =
            products

        adapter.submitList(products)

        binding.summarySubtitleText.text =
            when (products.size) {
                1 -> "1 product"
                else ->
                    "${products.size} products"
            }

        updateEmptyState(
            products.isEmpty(),
            searchEmpty = false
        )
    }

    private fun filterProducts(
        query: String
    ) {
        val cleanQuery =
            query.trim()

        if (cleanQuery.isBlank()) {
            adapter.submitList(
                unfilteredProducts
            )

            updateEmptyState(
                unfilteredProducts.isEmpty(),
                searchEmpty = false
            )

            return
        }

        val filtered =
            unfilteredProducts.filter {
                    product ->

                product.productName.contains(
                    cleanQuery,
                    ignoreCase = true
                ) ||
                        product.barcode.contains(
                            cleanQuery,
                            ignoreCase = true
                        ) ||
                        product.primaryLocation
                            ?.displayName
                            .orEmpty()
                            .contains(
                                cleanQuery,
                                ignoreCase = true
                            )
            }

        adapter.submitList(filtered)

        updateEmptyState(
            filtered.isEmpty(),
            searchEmpty = true
        )
    }

    private fun handleProductClick(
        product: StockTakeProduct
    ) {
        when (mode) {
            StockProductListMode.REMAINING -> {
                openProductForCounting(
                    product
                )
            }

            StockProductListMode.COUNTED,
            StockProductListMode.VARIANCE -> {
                showProductOptions(
                    product
                )
            }
        }
    }

    private fun showProductOptions(
        product: StockTakeProduct
    ) {
        val varianceText =
            when {
                product.variance < 0.0 ->
                    "Short by " +
                            formatQuantity(
                                -product.variance
                            )

                product.variance > 0.0 ->
                    "Excess of " +
                            formatQuantity(
                                product.variance
                            )

                else ->
                    "Stock matches"
            }

        val message =
            buildString {
                append(
                    product.productName
                )
                append("\n\nExpected: ")
                append(
                    formatQuantity(
                        product.expectedQuantity
                    )
                )
                append("\nCounted: ")
                append(
                    product.countedQuantity
                        ?.let {
                            formatQuantity(it)
                        }
                        ?: "Not counted"
                )
                append("\nVariance: ")
                append(varianceText)
                append("\n\nLocation: ")
                append(
                    product.primaryLocation
                        ?.displayName
                        ?.ifBlank {
                            "Not assigned"
                        }
                        ?: "Not assigned"
                )
            }

        AlertDialog.Builder(this)
            .setTitle("Product Count")
            .setMessage(message)
            .setNegativeButton(
                "Close",
                null
            )
            .setPositiveButton(
                "Recount"
            ) { _, _ ->
                openProductForCounting(
                    product
                )
            }
            .show()
    }

    private fun openProductForCounting(
        product: StockTakeProduct
    ) {
        val location =
            product.primaryLocation

        if (location == null) {
            Toast.makeText(
                this,
                "This product has no assigned location.",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        StockTakeRepository
            .updateCurrentLocation(
                location
            )

        startActivity(
            Intent(
                this,
                ToCountActivity::class.java
            ).apply {
                putExtra(
                    "barcode",
                    product.barcode
                )

                putExtra(
                    "locationId",
                    location.locationId
                )
            }
        )
    }

    private fun updateEmptyState(
        empty: Boolean,
        searchEmpty: Boolean
    ) {
        binding.productsRecyclerView.visibility =
            if (empty) {
                View.GONE
            } else {
                View.VISIBLE
            }

        binding.emptyStateContainer.visibility =
            if (empty) {
                View.VISIBLE
            } else {
                View.GONE
            }

        if (!empty) {
            return
        }

        if (searchEmpty) {
            binding.emptyStateTitle.text =
                "No matching products"

            binding.emptyStateMessage.text =
                "Try a different product name, " +
                        "barcode or location."

            return
        }

        when (mode) {
            StockProductListMode.COUNTED -> {
                binding.emptyStateTitle.text =
                    "No counted products"

                binding.emptyStateMessage.text =
                    "Products will appear here " +
                            "after they are counted."
            }

            StockProductListMode.REMAINING -> {
                binding.emptyStateTitle.text =
                    "Nothing remaining"

                binding.emptyStateMessage.text =
                    "All required products have " +
                            "already been counted."
            }

            StockProductListMode.VARIANCE -> {
                binding.emptyStateTitle.text =
                    "No stock variances"

                binding.emptyStateMessage.text =
                    "All counted products match " +
                            "their expected quantities."
            }
        }
    }

    private fun extractNumber(
        text: String
    ): Int {
        return Regex("\\d+")
            .find(text)
            ?.value
            ?.toIntOrNull()
            ?: Int.MAX_VALUE
    }

    private fun formatQuantity(
        quantity: Double
    ): String {
        return if (
            quantity % 1.0 == 0.0
        ) {
            quantity.toLong().toString()
        } else {
            String.format(
                java.util.Locale.getDefault(),
                "%.2f",
                quantity
            )
        }
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(
            binding.main
        ) {
                view,
                insets ->

            val systemBars =
                insets.getInsets(
                    WindowInsetsCompat
                        .Type
                        .systemBars()
                )

            view.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                systemBars.bottom
            )

            insets
        }
    }

    private fun setupSystemBars() {
        window.statusBarColor =
            Color.parseColor("#0071C1")

        window.navigationBarColor =
            Color.parseColor("#F4F6FA")

        WindowCompat.getInsetsController(
            window,
            window.decorView
        ).apply {
            isAppearanceLightStatusBars =
                false

            isAppearanceLightNavigationBars =
                true
        }
    }

    override fun onResume() {
        super.onResume()

        if (
            ::adapter.isInitialized
        ) {
            loadProducts()
        }
    }
}