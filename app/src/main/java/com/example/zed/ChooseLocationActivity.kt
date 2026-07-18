package com.example.zed

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
import com.example.zed.databinding.ActivityChooseLocationBinding
import java.util.Locale

class ChooseLocationActivity : AppCompatActivity() {

    private lateinit var binding:
            ActivityChooseLocationBinding

    private lateinit var locationAdapter:
            CountingLocationAdapter

    private var currentLevel =
        LocationSelectionLevel.AISLE

    private var selectedAisle: String? = null
    private var selectedRack: String? = null

    private var unfilteredItems:
            List<CountingLocationItem> = emptyList()

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        binding =
            ActivityChooseLocationBinding.inflate(
                layoutInflater
            )

        setContentView(binding.root)

        setupSystemBars()
        setupInsets()
        setupRecyclerView()
        setupListeners()

        validateStockTakeData()
    }

    private fun setupRecyclerView() {
        locationAdapter =
            CountingLocationAdapter { item ->
                handleLocationClick(item)
            }

        binding.locationRecyclerView.apply {
            layoutManager =
                LinearLayoutManager(
                    this@ChooseLocationActivity
                )

            adapter = locationAdapter

            setHasFixedSize(true)
        }
    }

    private fun setupListeners() {
        binding.toolbar.setNavigationOnClickListener {
            handleBackNavigation()
        }

        binding.aisleBreadcrumbButton
            .setOnClickListener {
                showAisles()
            }

        binding.rackBreadcrumbButton
            .setOnClickListener {
                val aisle =
                    selectedAisle ?: return@setOnClickListener

                showRacks(aisle)
            }

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
                    filterLocations(
                        value?.toString().orEmpty()
                    )
                }

                override fun afterTextChanged(
                    value: Editable?
                ) = Unit
            }
        )
    }

    private fun validateStockTakeData() {
        val session =
            StockTakeRepository.session.value

        val products =
            StockTakeRepository.products.value

        when {
            session == null -> {
                showFatalError(
                    "No active stock-take session was found."
                )
            }

            products.isEmpty() -> {
                showFatalError(
                    "There are no products in the current stock take."
                )
            }

            StockTakeRepository
                .getAllLocations()
                .isEmpty() -> {
                showFatalError(
                    "No product locations were found. " +
                            "Check Products.Location_IDs and " +
                            "product_location.Location_ID."
                )
            }

            else -> {
                showAisles()
            }
        }
    }

    private fun showAisles() {
        currentLevel =
            LocationSelectionLevel.AISLE

        selectedAisle = null
        selectedRack = null

        clearSearch()

        binding.screenInstructionText.text =
            "Choose an aisle"

        binding.screenSubtitleText.text =
            "Select the area where you are currently standing."

        binding.aisleBreadcrumbButton.text =
            "Aisle"

        binding.rackBreadcrumbArrow.visibility =
            View.GONE

        binding.rackBreadcrumbButton.visibility =
            View.GONE

        binding.shelfBreadcrumbArrow.visibility =
            View.GONE

        binding.shelfBreadcrumbText.visibility =
            View.GONE

        val products =
            StockTakeRepository.products.value

        val aisleNames =
            products
                .flatMap { product ->
                    product.locations.map {
                        it.aisle.trim()
                    }
                }
                .filter { it.isNotBlank() }
                .distinctBy {
                    it.lowercase(Locale.getDefault())
                }
                .sortedWith(::naturalTextCompare)

        val items = aisleNames.map { aisle ->
            val aisleProducts =
                productsForAisle(aisle)

            CountingLocationItem(
                id = "AISLE:${aisle.lowercase()}",
                name = aisle,
                level =
                    LocationSelectionLevel.AISLE,

                totalProducts =
                    aisleProducts.size,

                countedProducts =
                    aisleProducts.count {
                        it.isCounted
                    },

                remainingProducts =
                    aisleProducts.count {
                        !it.isCounted
                    }
            )
        }

        submitItems(items)
    }

    private fun showRacks(
        aisle: String
    ) {
        currentLevel =
            LocationSelectionLevel.RACK

        selectedAisle = aisle
        selectedRack = null

        clearSearch()

        binding.screenInstructionText.text =
            "Choose a rack"

        binding.screenSubtitleText.text =
            "Select the rack you are standing beside."

        binding.aisleBreadcrumbButton.text =
            aisle

        binding.rackBreadcrumbArrow.visibility =
            View.VISIBLE

        binding.rackBreadcrumbButton.apply {
            text = "Rack"
            visibility = View.VISIBLE
        }

        binding.shelfBreadcrumbArrow.visibility =
            View.GONE

        binding.shelfBreadcrumbText.visibility =
            View.GONE

        val products =
            productsForAisle(aisle)

        val rackNames =
            products
                .flatMap { product ->
                    product.locations
                        .filter {
                            it.aisle.equals(
                                aisle,
                                ignoreCase = true
                            )
                        }
                        .map {
                            it.rack.trim()
                        }
                }
                .filter {
                    it.isNotBlank()
                }
                .distinctBy {
                    it.lowercase(Locale.getDefault())
                }
                .sortedWith(::naturalTextCompare)

        val items =
            rackNames.map { rack ->
                val rackProducts =
                    productsForRack(
                        aisle = aisle,
                        rack = rack
                    )

                CountingLocationItem(
                    id =
                        "RACK:${aisle.lowercase()}:" +
                                rack.lowercase(),

                    name = rack,

                    level =
                        LocationSelectionLevel.RACK,

                    totalProducts =
                        rackProducts.size,

                    countedProducts =
                        rackProducts.count {
                            it.isCounted
                        },

                    remainingProducts =
                        rackProducts.count {
                            !it.isCounted
                        }
                )
            }

        submitItems(items)
    }

    private fun showShelves(
        aisle: String,
        rack: String
    ) {
        currentLevel =
            LocationSelectionLevel.SHELF

        selectedAisle = aisle
        selectedRack = rack

        clearSearch()

        binding.screenInstructionText.text =
            "Choose a shelf"

        binding.screenSubtitleText.text =
            "Select your exact shelf to begin counting."

        binding.aisleBreadcrumbButton.text =
            aisle

        binding.rackBreadcrumbArrow.visibility =
            View.VISIBLE

        binding.rackBreadcrumbButton.apply {
            text = rack
            visibility = View.VISIBLE
        }

        binding.shelfBreadcrumbArrow.visibility =
            View.VISIBLE

        binding.shelfBreadcrumbText.apply {
            text = "Shelf"
            visibility = View.VISIBLE
        }

        val matchingLocations =
            StockTakeRepository
                .getAllLocations()
                .filter { location ->
                    location.aisle.equals(
                        aisle,
                        ignoreCase = true
                    ) &&
                            location.rack.equals(
                                rack,
                                ignoreCase = true
                            )
                }
                .sortedWith(
                    compareBy<ProductLocation>(
                        { extractNumber(it.shelf) },
                        { it.shelf.lowercase() }
                    )
                )

        val items =
            matchingLocations.map { location ->
                val products =
                    StockTakeRepository
                        .productsAtLocation(
                            location.locationId
                        )

                CountingLocationItem(
                    id =
                        location.locationId,

                    name =
                        location.shelf.ifBlank {
                            "Unnamed shelf"
                        },

                    level =
                        LocationSelectionLevel.SHELF,

                    totalProducts =
                        products.size,

                    countedProducts =
                        products.count {
                            it.isCounted
                        },

                    remainingProducts =
                        products.count {
                            !it.isCounted
                        },

                    location =
                        location
                )
            }

        submitItems(items)
    }

    private fun handleLocationClick(
        item: CountingLocationItem
    ) {
        when (item.level) {
            LocationSelectionLevel.AISLE -> {
                showRacks(item.name)
            }

            LocationSelectionLevel.RACK -> {
                val aisle =
                    selectedAisle ?: return

                showShelves(
                    aisle = aisle,
                    rack = item.name
                )
            }

            LocationSelectionLevel.SHELF -> {
                val location =
                    item.location ?: return

                confirmShelfSelection(
                    location = location,
                    item = item
                )
            }
        }
    }

    private fun confirmShelfSelection(
        location: ProductLocation,
        item: CountingLocationItem
    ) {
        if (
            item.totalProducts > 0 &&
            item.remainingProducts == 0
        ) {
            AlertDialog.Builder(this)
                .setTitle("Shelf already completed")
                .setMessage(
                    "${location.displayName} has already " +
                            "been completely counted.\n\n" +
                            "Do you want to review it?"
                )
                .setNegativeButton(
                    "Cancel",
                    null
                )
                .setPositiveButton(
                    "Review"
                ) { _, _ ->
                    openCountingScreen(location)
                }
                .show()

            return
        }

        val message =
            buildString {
                append(location.displayName)
                append("\n\n")
                append(item.remainingProducts)
                append(
                    if (item.remainingProducts == 1) {
                        " product remains to be counted."
                    } else {
                        " products remain to be counted."
                    }
                )
            }

        AlertDialog.Builder(this)
            .setTitle("Start counting here?")
            .setMessage(message)
            .setNegativeButton(
                "Cancel",
                null
            )
            .setPositiveButton(
                "Start Counting"
            ) { _, _ ->
                openCountingScreen(location)
            }
            .show()
    }

    private fun openCountingScreen(
        location: ProductLocation
    ) {
        StockTakeRepository.updateCurrentLocation(
            location
        )

        val nextProduct =
            StockTakeRepository
                .productsAtLocation(
                    location.locationId
                )
                .firstOrNull {
                    !it.isCounted
                }
                ?: StockTakeRepository
                    .productsAtLocation(
                        location.locationId
                    )
                    .firstOrNull()

        if (nextProduct == null) {
            Toast.makeText(
                this,
                "No products were found at this location.",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        val intent =
            Intent(
                this,
                ToCountActivity::class.java
            ).apply {
                putExtra(
                    "locationId",
                    location.locationId
                )

                putExtra(
                    "barcode",
                    nextProduct.barcode
                )
            }

        startActivity(intent)
    }

    private fun productsForAisle(
        aisle: String
    ): List<StockTakeProduct> {
        return StockTakeRepository
            .products
            .value
            .filter { product ->
                product.locations.any { location ->
                    location.aisle.equals(
                        aisle,
                        ignoreCase = true
                    )
                }
            }
            .distinctBy {
                it.productId.ifBlank {
                    it.barcode
                }
            }
    }

    private fun productsForRack(
        aisle: String,
        rack: String
    ): List<StockTakeProduct> {
        return StockTakeRepository
            .products
            .value
            .filter { product ->
                product.locations.any { location ->
                    location.aisle.equals(
                        aisle,
                        ignoreCase = true
                    ) &&
                            location.rack.equals(
                                rack,
                                ignoreCase = true
                            )
                }
            }
            .distinctBy {
                it.productId.ifBlank {
                    it.barcode
                }
            }
    }

    private fun submitItems(
        items: List<CountingLocationItem>
    ) {
        unfilteredItems = items

        locationAdapter.submitList(items)

        binding.locationRecyclerView.visibility =
            if (items.isEmpty()) {
                View.GONE
            } else {
                View.VISIBLE
            }

        binding.emptyStateContainer.visibility =
            if (items.isEmpty()) {
                View.VISIBLE
            } else {
                View.GONE
            }

        binding.emptyStateTitle.text =
            when (currentLevel) {
                LocationSelectionLevel.AISLE ->
                    "No aisles found"

                LocationSelectionLevel.RACK ->
                    "No racks found"

                LocationSelectionLevel.SHELF ->
                    "No shelves found"
            }

        binding.emptyStateMessage.text =
            "No required stock-take products were found here."
    }

    private fun filterLocations(
        query: String
    ) {
        val cleanQuery =
            query.trim()

        if (cleanQuery.isBlank()) {
            locationAdapter.submitList(
                unfilteredItems
            )

            updateEmptyState(
                unfilteredItems.isEmpty()
            )

            return
        }

        val filtered =
            unfilteredItems.filter {
                it.name.contains(
                    cleanQuery,
                    ignoreCase = true
                )
            }

        locationAdapter.submitList(filtered)
        updateEmptyState(filtered.isEmpty())
    }

    private fun updateEmptyState(
        empty: Boolean
    ) {
        binding.locationRecyclerView.visibility =
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

        if (empty) {
            binding.emptyStateTitle.text =
                "No matching locations"

            binding.emptyStateMessage.text =
                "Try searching with a different name."
        }
    }

    private fun clearSearch() {
        binding.searchInput.setText("")
        binding.searchInputLayout.error = null
    }

    private fun handleBackNavigation() {
        when (currentLevel) {
            LocationSelectionLevel.AISLE -> {
                finish()
            }

            LocationSelectionLevel.RACK -> {
                showAisles()
            }

            LocationSelectionLevel.SHELF -> {
                val aisle =
                    selectedAisle

                if (aisle == null) {
                    showAisles()
                } else {
                    showRacks(aisle)
                }
            }
        }
    }

    @Deprecated(
        "Deprecated in Java"
    )
    override fun onBackPressed() {
        handleBackNavigation()
    }

    private fun showFatalError(
        message: String
    ) {
        AlertDialog.Builder(this)
            .setTitle("Locations unavailable")
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(
                "Return"
            ) { _, _ ->
                finish()
            }
            .show()
    }

    private fun naturalTextCompare(
        first: String,
        second: String
    ): Int {
        val firstNumber =
            extractNumber(first)

        val secondNumber =
            extractNumber(second)

        return when {
            firstNumber != Int.MAX_VALUE &&
                    secondNumber != Int.MAX_VALUE &&
                    firstNumber != secondNumber ->
                firstNumber.compareTo(
                    secondNumber
                )

            else ->
                first.compareTo(
                    second,
                    ignoreCase = true
                )
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

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(
            binding.main
        ) { view, insets ->

            val systemBars =
                insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
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
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = true
        }
    }

    override fun onResume() {
        super.onResume()

        /*
         * Refresh progress when returning from ToCountActivity.
         */
        if (
            ::locationAdapter.isInitialized &&
            StockTakeRepository.products.value.isNotEmpty()
        ) {
            when (currentLevel) {
                LocationSelectionLevel.AISLE ->
                    showAisles()

                LocationSelectionLevel.RACK -> {
                    selectedAisle?.let {
                        showRacks(it)
                    } ?: showAisles()
                }

                LocationSelectionLevel.SHELF -> {
                    val aisle =
                        selectedAisle

                    val rack =
                        selectedRack

                    if (
                        aisle != null &&
                        rack != null
                    ) {
                        showShelves(
                            aisle,
                            rack
                        )
                    } else {
                        showAisles()
                    }
                }
            }
        }
    }
}