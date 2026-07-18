package com.example.zed

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.zed.databinding.ActivityStockTakingBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class StockHomeFragment : Fragment() {

    private var _binding: ActivityStockTakingBinding? = null

    private val binding: ActivityStockTakingBinding
        get() = _binding
            ?: error("Binding accessed after onDestroyView().")

    private val viewModel: StockHomeViewModel by viewModels()

    private var lastDisplayedError: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ActivityStockTakingBinding.inflate(
            inflater,
            container,
            false
        )

        return binding.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)

        setupInsets()
        setupBottomNavigation()
        setupButtons()
        observeState()
        loadStockTakeIfRequired()
    }

    private fun loadStockTakeIfRequired() {
        val currentSession =
            StockTakeRepository.session.value

        val currentProducts =
            StockTakeRepository.products.value

        if (
            currentSession == null &&
            currentProducts.isEmpty()
        ) {
            val weekRange =
                getCurrentWeekRange()

            viewModel.loadWeeklyStockTake(
                periodStartMillis = weekRange.first,
                periodEndMillis = weekRange.second
            )
        } else {
            updateDateFromExistingSession()
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(
                Lifecycle.State.STARTED
            ) {
                viewModel.uiState.collect { state ->
                    renderState(state)
                }
            }
        }
    }

    private fun renderState(
        state: StockHomeUiState
    ) {
        renderOverallProgress(state)
        renderStockVariance(state)
        renderCashVariance(state)
        renderLocation(state)
        renderStatus(state)

        val message =
            state.errorMessage

        if (
            !message.isNullOrBlank() &&
            message != lastDisplayedError
        ) {
            lastDisplayedError = message

            Toast.makeText(
                requireContext(),
                message,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun renderOverallProgress(
        state: StockHomeUiState
    ) {
        binding.stockProgressCircle.setProgressCompat(
            state.progressPercentage,
            true
        )

        binding.stockProgressBar.setProgressCompat(
            state.progressPercentage,
            true
        )

        binding.progressPercentage.text =
            "${state.progressPercentage}%"

        binding.productsCountedText.text =
            "${state.countedProducts} of " +
                    "${state.totalProducts} products counted"

        binding.productsRemainingText.text =
            when (state.remainingProducts) {
                1 -> "1 remaining"
                else -> "${state.remainingProducts} remaining"
            }

        binding.countedItemsBadge.text =
            state.countedProducts.toString()

        binding.remainingItemsBadge.text =
            state.remainingProducts.toString()
    }

    private fun renderStockVariance(
        state: StockHomeUiState
    ) {
        val variance =
            state.stockVarianceUnits

        binding.stockVarianceValue.text =
            when {
                variance < 0.0 ->
                    "-${formatQuantity(-variance)} units"

                variance > 0.0 ->
                    "+${formatQuantity(variance)} units"

                else ->
                    "0 units"
            }

        binding.stockVarianceStatus.text =
            when {
                variance < 0.0 ->
                    "${formatQuantity(-variance)} units short"

                variance > 0.0 ->
                    "${formatQuantity(variance)} units excess"

                state.countedProducts == 0 ->
                    "No products counted yet"

                else ->
                    "Stock matches"
            }

        val colour =
            when {
                state.countedProducts == 0 ->
                    0xFF77808E.toInt()

                variance == 0.0 ->
                    0xFF19A65A.toInt()

                variance < 0.0 ->
                    0xFFD92525.toInt()

                else ->
                    0xFFF57C00.toInt()
            }

        binding.stockVarianceValue.setTextColor(
            colour
        )

        binding.stockVarianceStatus.setTextColor(
            colour
        )
    }

    private fun renderCashVariance(
        state: StockHomeUiState
    ) {
        val variance =
            state.cashVariance

        if (
            !state.cashReconciled ||
            variance == null
        ) {
            binding.cashVarianceValue.text =
                "Not reconciled"

            binding.cashVarianceStatus.text =
                "Enter drawer cash"

            binding.cashVarianceValue.setTextColor(
                0xFFF57C00.toInt()
            )

            binding.cashVarianceStatus.setTextColor(
                0xFFF57C00.toInt()
            )

            return
        }

        binding.cashVarianceValue.text =
            when {
                variance < 0.0 ->
                    "-K${formatMoney(-variance)}"

                variance > 0.0 ->
                    "+K${formatMoney(variance)}"

                else ->
                    "K0.00"
            }

        binding.cashVarianceStatus.text =
            when {
                variance < 0.0 ->
                    "Cash short"

                variance > 0.0 ->
                    "Cash excess"

                else ->
                    "Cash matches"
            }

        val colour =
            when {
                variance == 0.0 ->
                    0xFF19A65A.toInt()

                variance < 0.0 ->
                    0xFFD92525.toInt()

                else ->
                    0xFFF57C00.toInt()
            }

        binding.cashVarianceValue.setTextColor(
            colour
        )

        binding.cashVarianceStatus.setTextColor(
            colour
        )
    }

    private fun renderLocation(
        state: StockHomeUiState
    ) {
        val locationText =
            listOf(
                state.currentAisle,
                state.currentRack,
                state.currentShelf
            )
                .filter {
                    it.isNotBlank()
                }
                .joinToString("  ›  ")

        binding.currentLocationText.text =
            locationText.ifBlank {
                "No counting location selected"
            }

        binding.locationProgressText.text =
            if (state.currentLocationId.isBlank()) {
                "Choose an aisle, rack and shelf to begin"
            } else {
                "${state.locationCounted} of " +
                        "${state.locationTotal} products counted " +
                        "at this location"
            }

        binding.locationProgressBar.setProgressCompat(
            state.locationProgressPercentage,
            true
        )
    }

    private fun renderStatus(
        state: StockHomeUiState
    ) {
        when (state.status) {

            StockTakeStatus.LOADING -> {
                binding.statusTitle.text =
                    "Loading stock take"

                binding.statusSubtitle.text =
                    "Reading products, transactions and locations"

                binding.continueTopButton.text =
                    "Loading"

                binding.continueCountingButton.text =
                    "Loading"

                binding.continueTopButton.isEnabled =
                    false

                binding.continueCountingButton.isEnabled =
                    false

                binding.startLocationButton.isEnabled =
                    false
            }

            StockTakeStatus.IN_PROGRESS -> {
                binding.statusTitle.text =
                    "Stock take is in progress"

                binding.statusSubtitle.text =
                    when (state.remainingProducts) {
                        1 ->
                            "1 product remaining"

                        else ->
                            "${state.remainingProducts} products remaining"
                    }

                binding.continueTopButton.text =
                    "Continue"

                binding.continueCountingButton.text =
                    "Continue counting"

                val hasProducts =
                    state.totalProducts > 0

                binding.continueTopButton.isEnabled =
                    hasProducts

                binding.continueCountingButton.isEnabled =
                    hasProducts

                binding.startLocationButton.isEnabled =
                    hasProducts
            }

            StockTakeStatus.COMPLETED -> {
                binding.statusTitle.text =
                    "Stock take is complete"

                binding.statusSubtitle.text =
                    "All required products have been counted"

                binding.continueTopButton.text =
                    "Review"

                binding.continueCountingButton.text =
                    "Review stock take"

                binding.continueTopButton.isEnabled =
                    true

                binding.continueCountingButton.isEnabled =
                    true

                binding.startLocationButton.isEnabled =
                    true
            }

            StockTakeStatus.FAILED -> {
                binding.statusTitle.text =
                    "Unable to load stock take"

                binding.statusSubtitle.text =
                    state.errorMessage
                        ?: "Check your internet connection"

                binding.continueTopButton.text =
                    "Retry"

                binding.continueCountingButton.text =
                    "Continue counting"

                binding.continueTopButton.isEnabled =
                    true

                binding.continueCountingButton.isEnabled =
                    false

                binding.startLocationButton.isEnabled =
                    false
            }

            StockTakeStatus.NOT_STARTED -> {
                binding.statusTitle.text =
                    "No active stock take"

                binding.statusSubtitle.text =
                    "Start a weekly stock take"

                binding.continueTopButton.text =
                    "Start"

                binding.continueCountingButton.text =
                    "Start stock take"

                binding.continueTopButton.isEnabled =
                    true

                binding.continueCountingButton.isEnabled =
                    true

                binding.startLocationButton.isEnabled =
                    false
            }
        }
    }

    private fun setupButtons() {
        binding.continueTopButton.setOnClickListener {
            handleContinueButton()
        }

        binding.continueCountingButton.setOnClickListener {
            handleContinueButton()
        }

        binding.startLocationButton.setOnClickListener {
            openChooseLocationScreen()
        }

        binding.currentLocationCard.setOnClickListener {
            openChooseLocationScreen()
        }

        binding.viewCountedItemsButton.setOnClickListener {
            openProductList(
                StockProductListMode.COUNTED
            )
        }

        binding.viewRemainingItemsButton.setOnClickListener {
            openProductList(
                StockProductListMode.REMAINING
            )
        }

        binding.stockVarianceCard.setOnClickListener {
            openProductList(
                StockProductListMode.VARIANCE
            )
        }

        binding.cashVarianceCard.setOnClickListener {
            openCashReconciliation()
        }
    }

    private fun openCashReconciliation() {
        val session =
            StockTakeRepository.session.value

        if (session == null) {
            Toast.makeText(
                requireContext(),
                "No active stock-take session.",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        if (
            session.status ==
            StockTakeStatus.LOADING
        ) {
            Toast.makeText(
                requireContext(),
                "Please wait while the stock take loads.",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        startActivity(
            Intent(
                requireContext(),
                CashReconciliationActivity::class.java
            )
        )
    }

    private fun openProductList(
        mode: StockProductListMode
    ) {
        val products =
            StockTakeRepository.products.value

        if (products.isEmpty()) {
            Toast.makeText(
                requireContext(),
                "Stock-take products have not loaded yet.",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        startActivity(
            StockProductListActivity.newIntent(
                context = requireContext(),
                mode = mode
            )
        )
    }

    private fun handleContinueButton() {
        when (
            StockTakeRepository.session.value?.status
        ) {
            StockTakeStatus.FAILED -> {
                viewModel.retry()
            }

            StockTakeStatus.NOT_STARTED,
            null -> {
                val weekRange =
                    getCurrentWeekRange()

                viewModel.loadWeeklyStockTake(
                    periodStartMillis =
                        weekRange.first,

                    periodEndMillis =
                        weekRange.second
                )
            }

            StockTakeStatus.LOADING -> {
                Toast.makeText(
                    requireContext(),
                    "Please wait while the stock take loads.",
                    Toast.LENGTH_SHORT
                ).show()
            }

            StockTakeStatus.IN_PROGRESS,
            StockTakeStatus.COMPLETED -> {
                openContinueCounting()
            }
        }
    }

    private fun openChooseLocationScreen() {
        val session =
            StockTakeRepository.session.value

        val products =
            StockTakeRepository.products.value

        val locations =
            StockTakeRepository.getAllLocations()

        when {
            session == null -> {
                Toast.makeText(
                    requireContext(),
                    "No active stock-take session.",
                    Toast.LENGTH_SHORT
                ).show()
            }

            session.status ==
                    StockTakeStatus.LOADING -> {
                Toast.makeText(
                    requireContext(),
                    "Please wait while products and locations load.",
                    Toast.LENGTH_SHORT
                ).show()
            }

            products.isEmpty() -> {
                Toast.makeText(
                    requireContext(),
                    "The stock-take products have not loaded yet.",
                    Toast.LENGTH_SHORT
                ).show()
            }

            locations.isEmpty() -> {
                Toast.makeText(
                    requireContext(),
                    "No product locations were found. " +
                            "Check Products.Location_IDs and " +
                            "product_location.Location_ID.",
                    Toast.LENGTH_LONG
                ).show()
            }

            else -> {
                startActivity(
                    Intent(
                        requireContext(),
                        ChooseLocationActivity::class.java
                    )
                )
            }
        }
    }

    private fun openContinueCounting() {
        val products =
            StockTakeRepository.products.value

        if (products.isEmpty()) {
            Toast.makeText(
                requireContext(),
                "No stock-take products are available.",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        val currentLocationId =
            StockTakeRepository
                .session
                .value
                ?.currentLocationId
                .orEmpty()

        if (currentLocationId.isBlank()) {
            openChooseLocationScreen()
            return
        }

        var nextProduct =
            StockTakeRepository
                .nextRemainingProductAtCurrentLocation()

        if (nextProduct == null) {
            val nextLocation =
                StockTakeRepository
                    .moveToNextIncompleteLocation()

            if (nextLocation != null) {
                nextProduct =
                    StockTakeRepository
                        .nextRemainingProductAtCurrentLocation()
            }
        }

        if (nextProduct == null) {
            Toast.makeText(
                requireContext(),
                "All required products have been counted. " +
                        "Opening counted products.",
                Toast.LENGTH_LONG
            ).show()

            openProductList(
                StockProductListMode.COUNTED
            )

            return
        }

        startActivity(
            Intent(
                requireContext(),
                ToCountActivity::class.java
            ).apply {
                putExtra(
                    "barcode",
                    nextProduct.barcode
                )

                putExtra(
                    "locationId",
                    nextProduct.locationId
                )
            }
        )
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation.selectedItemId =
            R.id.navigation_home

        binding.bottomNavigation
            .setOnItemSelectedListener { item ->

                when (item.itemId) {
                    R.id.navigation_home -> {
                        true
                    }

                    R.id.navigation_to_count -> {
                        openContinueCounting()
                        false
                    }

                    R.id.navigation_counted -> {
                        openProductList(
                            StockProductListMode.COUNTED
                        )

                        false
                    }

                    R.id.navigation_more -> {
                        Toast.makeText(
                            requireContext(),
                            "More page will be connected next.",
                            Toast.LENGTH_SHORT
                        ).show()

                        false
                    }

                    else -> false
                }
            }
    }

    private fun setupInsets() {
        val originalLeft =
            binding.bottomNavigation.paddingLeft

        val originalTop =
            binding.bottomNavigation.paddingTop

        val originalRight =
            binding.bottomNavigation.paddingRight

        ViewCompat.setOnApplyWindowInsetsListener(
            binding.bottomNavigation
        ) { navigationView, insets ->

            val navigationInsets =
                insets.getInsets(
                    WindowInsetsCompat.Type.navigationBars()
                )

            navigationView.setPadding(
                originalLeft,
                originalTop,
                originalRight,
                navigationInsets.bottom + 4.dp()
            )

            insets
        }

        ViewCompat.requestApplyInsets(
            binding.bottomNavigation
        )
    }

    private fun getCurrentWeekRange():
            Pair<Long, Long> {

        val start =
            Calendar.getInstance().apply {
                firstDayOfWeek =
                    Calendar.MONDAY

                set(
                    Calendar.DAY_OF_WEEK,
                    Calendar.MONDAY
                )

                set(
                    Calendar.HOUR_OF_DAY,
                    0
                )

                set(
                    Calendar.MINUTE,
                    0
                )

                set(
                    Calendar.SECOND,
                    0
                )

                set(
                    Calendar.MILLISECOND,
                    0
                )
            }

        val end =
            (start.clone() as Calendar).apply {
                add(
                    Calendar.DAY_OF_MONTH,
                    6
                )

                set(
                    Calendar.HOUR_OF_DAY,
                    23
                )

                set(
                    Calendar.MINUTE,
                    59
                )

                set(
                    Calendar.SECOND,
                    59
                )

                set(
                    Calendar.MILLISECOND,
                    999
                )
            }

        binding.stockTakeDate.text =
            "${formatDate(start.time)} – " +
                    formatDate(end.time)

        return start.timeInMillis to
                end.timeInMillis
    }

    private fun updateDateFromExistingSession() {
        val session =
            StockTakeRepository
                .session
                .value
                ?: return

        binding.stockTakeDate.text =
            "${formatDate(Date(session.periodStartMillis))} – " +
                    formatDate(
                        Date(session.periodEndMillis)
                    )
    }

    private fun formatDate(
        date: Date
    ): String {
        return SimpleDateFormat(
            "dd MMM yyyy",
            Locale.getDefault()
        ).format(date)
    }

    private fun formatQuantity(
        value: Double
    ): String {
        return if (
            value % 1.0 == 0.0
        ) {
            value.toLong().toString()
        } else {
            String.format(
                Locale.getDefault(),
                "%.2f",
                value
            )
        }
    }

    private fun formatMoney(
        amount: Double
    ): String {
        return String.format(
            Locale.getDefault(),
            "%,.2f",
            amount
        )
    }

    private fun Int.dp(): Int {
        return (
                this *
                        resources.displayMetrics.density
                ).toInt()
    }

    override fun onResume() {
        super.onResume()

        if (_binding == null) {
            return
        }

        binding.bottomNavigation.selectedItemId =
            R.id.navigation_home

        updateDateFromExistingSession()
    }

    override fun onDestroyView() {
        if (_binding != null) {
            ViewCompat.setOnApplyWindowInsetsListener(
                binding.bottomNavigation,
                null
            )
        }

        _binding = null

        super.onDestroyView()
    }
}