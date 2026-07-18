package com.example.zed

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.example.zed.databinding.ActivityToCountBinding
import com.google.android.gms.auth.api.signin.GoogleSignIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class ToCountActivity : AppCompatActivity() {

    private lateinit var binding: ActivityToCountBinding

    private lateinit var sheetsService: GoogleSheetsStockTakeService

    private var currentProduct: StockTakeProduct? = null

    private var currentCountMode = CountMode.SINGLES

    private var caseMultiplier = 1.0

    private enum class CountMode {
        SINGLES,
        CASES
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        binding = ActivityToCountBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sheetsService = GoogleSheetsStockTakeService(this)

        setupSystemBars()
        setupInsets()
        setupListeners()

        loadRequestedLocation()
        loadNextProduct()
    }

    private fun setupListeners() {
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }

        binding.countTypeToggle.addOnButtonCheckedListener {
                _,
                checkedId,
                isChecked ->

            if (!isChecked) {
                return@addOnButtonCheckedListener
            }

            currentCountMode = when (checkedId) {
                R.id.casesButton -> CountMode.CASES
                else -> CountMode.SINGLES
            }

            updateConversionHelp()
            updateCalculatedCount()
        }

        binding.quantityInput.addTextChangedListener(
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
                    binding.quantityInputLayout.error = null
                    updateCalculatedCount()
                }

                override fun afterTextChanged(
                    value: Editable?
                ) = Unit
            }
        )

        binding.saveCountButton.setOnClickListener {
            saveCurrentCount()
        }

        binding.skipButton.setOnClickListener {
            skipCurrentProduct()
        }
    }

    /**
     * Reads an optional location passed by StockHomeFragment.
     *
     * If no location is passed, the repository's existing current
     * location is used.
     */
    private fun loadRequestedLocation() {
        val requestedLocationId =
            intent.getStringExtra("locationId")
                ?.trim()
                .orEmpty()

        if (requestedLocationId.isBlank()) {
            return
        }

        val requestedLocation =
            StockTakeRepository
                .getAllLocations()
                .firstOrNull {
                    it.locationId.equals(
                        requestedLocationId,
                        ignoreCase = true
                    )
                }

        if (requestedLocation != null) {
            StockTakeRepository.updateCurrentLocation(
                requestedLocation
            )
        }
    }

    private fun loadNextProduct() {
        var nextProduct =
            StockTakeRepository
                .nextRemainingProductAtCurrentLocation()

        if (nextProduct == null) {
            val nextLocation =
                StockTakeRepository
                    .moveToNextIncompleteLocation()

            if (nextLocation != null) {
                Toast.makeText(
                    this,
                    "Move to ${nextLocation.displayName}",
                    Toast.LENGTH_LONG
                ).show()

                nextProduct =
                    StockTakeRepository
                        .nextRemainingProductAtCurrentLocation()
            }
        }

        if (nextProduct == null) {
            showStockTakeCompleteDialog()
            return
        }

        currentProduct = nextProduct
        displayProduct(nextProduct)
        updateLocationProgress()
    }

    private fun displayProduct(
        product: StockTakeProduct
    ) {
        binding.productNameText.text =
            product.productName.ifBlank {
                "Unnamed product"
            }

        binding.barcodeText.text =
            "Barcode: ${product.barcode}"

        binding.expectedQuantityText.text =
            formatQuantity(product.expectedQuantity)

        binding.productUnitText.text =
            "Unit: ${product.unit.ifBlank { "Single" }}"

        caseMultiplier =
            extractCaseMultiplier(product.unit)

        binding.casesButton.isEnabled =
            caseMultiplier > 1.0

        if (caseMultiplier <= 1.0) {
            binding.countTypeToggle.check(
                R.id.singlesButton
            )

            currentCountMode =
                CountMode.SINGLES
        }

        val imageUrl =
            product.imageUrl.orEmpty()

        if (imageUrl.isNotBlank()) {
            Glide.with(this)
                .load(imageUrl)
                .placeholder(R.drawable.barcode_icon)
                .error(R.drawable.barcode_icon)
                .into(binding.productImage)
        } else {
            binding.productImage.setImageResource(
                R.drawable.barcode_icon
            )
        }

        val location =
            product.primaryLocation

        binding.locationText.text =
            location?.displayName
                ?.ifBlank {
                    "Location not assigned"
                }
                ?: "Location not assigned"

        if (product.isCounted) {
            binding.previousCountCard.visibility =
                View.VISIBLE

            binding.previousCountText.text =
                "Previously counted: " +
                        "${formatQuantity(product.countedQuantity ?: 0.0)} units"

            binding.quantityInput.setText(
                when (currentCountMode) {
                    CountMode.SINGLES ->
                        formatQuantity(
                            product.countedQuantity ?: 0.0
                        )

                    CountMode.CASES ->
                        formatQuantity(
                            (product.countedQuantity ?: 0.0) /
                                    caseMultiplier
                        )
                }
            )
        } else {
            binding.previousCountCard.visibility =
                View.GONE

            binding.quantityInput.setText("")
        }

        updateConversionHelp()
        updateCalculatedCount()

        binding.quantityInput.requestFocus()
        showKeyboard()
    }

    private fun updateConversionHelp() {
        binding.conversionHelpText.text =
            when (currentCountMode) {
                CountMode.SINGLES ->
                    "Enter the number of individual units counted"

                CountMode.CASES ->
                    "1 case = ${formatQuantity(caseMultiplier)} units"
            }
    }

    private fun updateCalculatedCount() {
        val enteredQuantity =
            binding.quantityInput.text
                ?.toString()
                ?.trim()
                ?.toDoubleOrNull()
                ?: 0.0

        val countedUnits =
            convertEnteredQuantityToUnits(
                enteredQuantity
            )

        binding.calculatedCountText.text =
            formatQuantity(countedUnits)

        updateVariancePreview(countedUnits)
    }

    private fun updateVariancePreview(
        countedUnits: Double
    ) {
        val product =
            currentProduct ?: return

        if (
            binding.quantityInput.text
                ?.toString()
                ?.trim()
                .isNullOrBlank()
        ) {
            binding.varianceValueText.text =
                "0 units"

            binding.varianceStatusText.text =
                "Enter a quantity to calculate variance"

            binding.varianceValueText.setTextColor(
                Color.parseColor("#687386")
            )

            binding.varianceStatusText.setTextColor(
                Color.parseColor("#687386")
            )

            return
        }

        val variance =
            countedUnits - product.expectedQuantity

        when {
            variance < 0 -> {
                binding.varianceValueText.text =
                    "-${formatQuantity(-variance)} units"

                binding.varianceStatusText.text =
                    "${formatQuantity(-variance)} units short"

                binding.varianceValueText.setTextColor(
                    Color.parseColor("#D92525")
                )

                binding.varianceStatusText.setTextColor(
                    Color.parseColor("#D92525")
                )
            }

            variance > 0 -> {
                binding.varianceValueText.text =
                    "+${formatQuantity(variance)} units"

                binding.varianceStatusText.text =
                    "${formatQuantity(variance)} units excess"

                binding.varianceValueText.setTextColor(
                    Color.parseColor("#F57C00")
                )

                binding.varianceStatusText.setTextColor(
                    Color.parseColor("#F57C00")
                )
            }

            else -> {
                binding.varianceValueText.text =
                    "0 units"

                binding.varianceStatusText.text =
                    "Stock matches expected quantity"

                binding.varianceValueText.setTextColor(
                    Color.parseColor("#19A65A")
                )

                binding.varianceStatusText.setTextColor(
                    Color.parseColor("#19A65A")
                )
            }
        }
    }

    private fun saveCurrentCount() {
        val product =
            currentProduct ?: return

        val session =
            StockTakeRepository.session.value

        if (session == null) {
            Toast.makeText(
                this,
                "No active stock-take session.",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        val enteredText =
            binding.quantityInput.text
                ?.toString()
                ?.trim()
                .orEmpty()

        if (enteredText.isBlank()) {
            binding.quantityInputLayout.error =
                "Enter the quantity counted"

            binding.quantityInput.requestFocus()
            return
        }

        val enteredQuantity =
            enteredText.toDoubleOrNull()

        if (enteredQuantity == null) {
            binding.quantityInputLayout.error =
                "Enter a valid quantity"

            return
        }

        if (enteredQuantity < 0) {
            binding.quantityInputLayout.error =
                "Quantity cannot be negative"

            return
        }

        val countedUnits =
            convertEnteredQuantityToUnits(
                enteredQuantity
            )

        val variance =
            countedUnits - product.expectedQuantity

        if (variance != 0.0) {
            showVarianceConfirmation(
                product = product,
                countedUnits = countedUnits,
                variance = variance
            )
        } else {
            commitCount(
                product = product,
                countedUnits = countedUnits
            )
        }
    }

    private fun showVarianceConfirmation(
        product: StockTakeProduct,
        countedUnits: Double,
        variance: Double
    ) {
        val message =
            when {
                variance < 0 ->
                    "Expected: " +
                            "${formatQuantity(product.expectedQuantity)}\n" +
                            "Counted: " +
                            "${formatQuantity(countedUnits)}\n\n" +
                            "Short by " +
                            "${formatQuantity(-variance)} units.\n\n" +
                            "Have you recounted this product?"

                else ->
                    "Expected: " +
                            "${formatQuantity(product.expectedQuantity)}\n" +
                            "Counted: " +
                            "${formatQuantity(countedUnits)}\n\n" +
                            "Excess of " +
                            "${formatQuantity(variance)} units.\n\n" +
                            "Have you recounted this product?"
            }

        AlertDialog.Builder(this)
            .setTitle("Confirm Stock Variance")
            .setMessage(message)
            .setNegativeButton("Recount", null)
            .setPositiveButton("Confirm") { _, _ ->
                commitCount(
                    product = product,
                    countedUnits = countedUnits
                )
            }
            .show()
    }

    private fun commitCount(
        product: StockTakeProduct,
        countedUnits: Double
    ) {
        val session =
            StockTakeRepository.session.value
                ?: return

        val account =
            GoogleSignIn.getLastSignedInAccount(this)

        if (account == null) {
            Toast.makeText(
                this,
                "Google account not available.",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        val countedBy =
            account.email.orEmpty()

        /*
         * Save to the in-memory repository immediately so that
         * the dashboard and progress update without waiting.
         */
        StockTakeRepository.saveCountLocally(
            barcode = product.barcode,
            countedQuantity = countedUnits,
            countedBy = countedBy
        )

        setLoading(
            loading = true,
            message = "Saving count..."
        )

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    sheetsService.saveOrUpdateCount(
                        account = account,
                        session = session,
                        product = product,
                        countedQuantity = countedUnits,
                        countedBy = countedBy
                    )
                }

                Toast.makeText(
                    this@ToCountActivity,
                    "Count saved successfully.",
                    Toast.LENGTH_SHORT
                ).show()

                clearCountInput()
                loadNextProduct()
            } catch (exception: Exception) {
                /*
                 * The count remains in the repository, but this
                 * first version does not yet persist unsynced
                 * counts after the app process closes.
                 */
                AlertDialog.Builder(this@ToCountActivity)
                    .setTitle("Unable to sync")
                    .setMessage(
                        "The count was kept in the current app session, " +
                                "but it could not be saved to Google Sheets.\n\n" +
                                "${exception.message.orEmpty()}"
                    )
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Retry") { _, _ ->
                        commitCount(
                            product = product,
                            countedUnits = countedUnits
                        )
                    }
                    .show()
            } finally {
                setLoading(false)
            }
        }
    }

    private fun skipCurrentProduct() {
        val current =
            currentProduct ?: return

        val locationProducts =
            StockTakeRepository
                .productsAtCurrentLocation()
                .filter { !it.isCounted }

        val currentIndex =
            locationProducts.indexOfFirst {
                it.barcode.equals(
                    current.barcode,
                    ignoreCase = true
                )
            }

        val nextAtLocation =
            when {
                locationProducts.size <= 1 ->
                    null

                currentIndex >= 0 &&
                        currentIndex < locationProducts.lastIndex ->
                    locationProducts[currentIndex + 1]

                else ->
                    locationProducts.firstOrNull {
                        !it.barcode.equals(
                            current.barcode,
                            ignoreCase = true
                        )
                    }
            }

        if (nextAtLocation != null) {
            currentProduct = nextAtLocation
            displayProduct(nextAtLocation)
            updateLocationProgress()
            return
        }

        val nextLocation =
            StockTakeRepository
                .moveToNextIncompleteLocation()

        if (nextLocation == null) {
            Toast.makeText(
                this,
                "No other remaining products.",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        Toast.makeText(
            this,
            "Move to ${nextLocation.displayName}",
            Toast.LENGTH_LONG
        ).show()

        loadNextProduct()
    }

    private fun updateLocationProgress() {
        val locationProducts =
            StockTakeRepository
                .productsAtCurrentLocation()

        val countedProducts =
            locationProducts.count {
                it.isCounted
            }

        val totalProducts =
            locationProducts.size

        val percentage =
            if (totalProducts > 0) {
                (
                        countedProducts.toDouble() /
                                totalProducts.toDouble() *
                                100.0
                        )
                    .toInt()
                    .coerceIn(0, 100)
            } else {
                0
            }

        binding.locationProgressText.text =
            "$countedProducts of $totalProducts products counted"

        binding.locationProgressBar.setProgressCompat(
            percentage,
            true
        )
    }

    private fun convertEnteredQuantityToUnits(
        enteredQuantity: Double
    ): Double {
        return when (currentCountMode) {
            CountMode.SINGLES ->
                enteredQuantity

            CountMode.CASES ->
                enteredQuantity * caseMultiplier
        }
    }

    /**
     * Examples:
     *
     * "1 x 24" -> 24
     * "1x12"   -> 12
     * "single" -> 1
     * "24"     -> 24
     */
    private fun extractCaseMultiplier(
        unitText: String
    ): Double {
        if (unitText.isBlank()) {
            return 1.0
        }

        val lower =
            unitText.trim().lowercase()

        if (
            lower.contains("single") ||
            lower == "1 x 1" ||
            lower == "1x1"
        ) {
            return 1.0
        }

        val multiplicationMatch =
            Regex(
                """(?:\d+(?:\.\d+)?)\s*[x×]\s*(\d+(?:\.\d+)?)""",
                RegexOption.IGNORE_CASE
            ).find(lower)

        val secondNumber =
            multiplicationMatch
                ?.groupValues
                ?.getOrNull(1)
                ?.toDoubleOrNull()

        if (secondNumber != null && secondNumber > 0) {
            return secondNumber
        }

        val finalNumber =
            Regex("""\d+(?:\.\d+)?""")
                .findAll(lower)
                .mapNotNull {
                    it.value.toDoubleOrNull()
                }
                .lastOrNull()

        return finalNumber
            ?.takeIf { it > 1.0 }
            ?: 1.0
    }

    private fun clearCountInput() {
        binding.quantityInput.setText("")
        binding.quantityInputLayout.error = null
        binding.calculatedCountText.text = "0"
    }

    private fun showStockTakeCompleteDialog() {
        hideKeyboard()

        AlertDialog.Builder(this)
            .setTitle("Stock Count Complete")
            .setMessage(
                "All required products in this stock-take session " +
                        "have been counted."
            )
            .setCancelable(false)
            .setPositiveButton("Return to Dashboard") { _, _ ->
                markSessionCompleted()
                finish()
            }
            .show()
    }

    private fun markSessionCompleted() {
        val session =
            StockTakeRepository.session.value
                ?: return

        StockTakeRepository.setSession(
            session.copy(
                status = StockTakeStatus.COMPLETED
            )
        )
    }

    private fun setLoading(
        loading: Boolean,
        message: String = "Please wait..."
    ) {
        binding.loadingText.text = message

        binding.loadingOverlay.visibility =
            if (loading) {
                View.VISIBLE
            } else {
                View.GONE
            }

        binding.saveCountButton.isEnabled =
            !loading

        binding.skipButton.isEnabled =
            !loading
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
                0
            )

            binding.bottomActionContainer.setPadding(
                binding.bottomActionContainer.paddingLeft,
                binding.bottomActionContainer.paddingTop,
                binding.bottomActionContainer.paddingRight,
                systemBars.bottom + dpToPx(10)
            )

            insets
        }
    }

    private fun setupSystemBars() {
        window.statusBarColor =
            Color.parseColor("#0071C1")

        window.navigationBarColor =
            Color.parseColor("#FFFFFF")

        val controller =
            WindowCompat.getInsetsController(
                window,
                window.decorView
            )

        controller.isAppearanceLightStatusBars =
            false

        controller.isAppearanceLightNavigationBars =
            true
    }

    private fun showKeyboard() {
        binding.quantityInput.postDelayed(
            {
                val inputMethodManager =
                    getSystemService(
                        Context.INPUT_METHOD_SERVICE
                    ) as InputMethodManager

                inputMethodManager.showSoftInput(
                    binding.quantityInput,
                    InputMethodManager.SHOW_IMPLICIT
                )
            },
            200
        )
    }

    private fun hideKeyboard() {
        val inputMethodManager =
            getSystemService(
                Context.INPUT_METHOD_SERVICE
            ) as InputMethodManager

        inputMethodManager.hideSoftInputFromWindow(
            binding.quantityInput.windowToken,
            0
        )
    }

    private fun formatQuantity(
        quantity: Double
    ): String {
        return if (quantity % 1.0 == 0.0) {
            quantity.toLong().toString()
        } else {
            String.format(
                Locale.getDefault(),
                "%.2f",
                quantity
            )
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (
                dp * resources.displayMetrics.density
                ).toInt()
    }
}