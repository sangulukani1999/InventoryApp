package com.example.zed

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.zed.databinding.ActivityCashReconciliationBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CashReconciliationActivity : AppCompatActivity() {

    private lateinit var binding:
            ActivityCashReconciliationBinding

    private val viewModel:
            CashReconciliationViewModel by viewModels()

    private lateinit var expenseAdapter:
            CashExpenseAdapter

    private var lastError: String? = null

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        binding =
            ActivityCashReconciliationBinding.inflate(
                layoutInflater
            )

        setContentView(binding.root)

        setupSystemBars()
        setupInsets()
        setupRecyclerView()
        setupListeners()
        observeState()
        loadCurrentPeriod()
    }

    private fun setupRecyclerView() {
        expenseAdapter =
            CashExpenseAdapter()

        binding.expensesRecyclerView.apply {
            layoutManager =
                LinearLayoutManager(
                    this@CashReconciliationActivity
                )

            adapter =
                expenseAdapter

            setHasFixedSize(false)
        }
    }

    private fun setupListeners() {
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }

        binding.actualCashInput.addTextChangedListener(
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
                    binding.actualCashInputLayout.error =
                        null

                    val text =
                        value
                            ?.toString()
                            ?.trim()
                            .orEmpty()

                    if (text.isBlank()) {
                        viewModel.clearActualDrawerCash()

                        binding.saveButton.isEnabled =
                            false

                        renderUnreconciled()

                        return
                    }

                    val actualCash =
                        text.toDoubleOrNull()

                    if (
                        actualCash == null ||
                        actualCash < 0.0
                    ) {
                        binding.actualCashInputLayout.error =
                            "Enter a valid cash amount"

                        binding.saveButton.isEnabled =
                            false

                        return
                    }

                    viewModel.setActualDrawerCash(
                        actualCash
                    )
                }

                override fun afterTextChanged(
                    value: Editable?
                ) = Unit
            }
        )

        binding.saveButton.setOnClickListener {
            viewModel.saveReconciliation()
        }
    }

    private fun loadCurrentPeriod() {
        val session =
            StockTakeRepository
                .session
                .value

        if (session == null) {
            Toast.makeText(
                this,
                "No active stock-take session.",
                Toast.LENGTH_LONG
            ).show()

            finish()
            return
        }

        binding.periodText.text =
            "${formatDateTime(session.periodStartMillis)} – " +
                    formatDateTime(session.periodEndMillis)

        viewModel.loadReconciliation(
            periodStartMillis =
                session.periodStartMillis,

            periodEndMillis =
                session.periodEndMillis
        )
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(
                Lifecycle.State.STARTED
            ) {
                viewModel.uiState.collect { state ->
                    renderState(state)
                }
            }
        }
    }

    private fun renderState(
        state: CashReconciliationUiState
    ) {
        val busy =
            state.loading || state.saving

        binding.loadingOverlay.visibility =
            if (busy) {
                View.VISIBLE
            } else {
                View.GONE
            }

        binding.loadingText.text =
            if (state.saving) {
                "Saving reconciliation..."
            } else {
                "Calculating expected cash..."
            }

        binding.actualCashInput.isEnabled =
            !busy

        binding.saveButton.isEnabled =
            !busy &&
                    state.summary.actualDrawerCash != null

        renderSummary(
            state.summary
        )

        val error =
            state.errorMessage

        if (
            !error.isNullOrBlank() &&
            error != lastError
        ) {
            lastError =
                error

            Toast.makeText(
                this,
                error,
                Toast.LENGTH_LONG
            ).show()

            viewModel.clearError()
        }

        if (state.saveSuccessful) {
            Toast.makeText(
                this,
                "Cash reconciliation saved successfully.",
                Toast.LENGTH_LONG
            ).show()

            viewModel.consumeSaveSuccessful()

            setResult(RESULT_OK)
            finish()
        }
    }

    private fun renderSummary(
        summary: CashReconciliationSummary
    ) {
        binding.openingCashText.text =
            "K${formatMoney(summary.openingAvailableCash)}"

        binding.cashSalesText.text =
            "+K${formatMoney(summary.cashSales)}"

        binding.investmentsText.text =
            "+K${formatMoney(summary.investments)}"

        binding.expensesText.text =
            "-K${formatMoney(summary.approvedCashExpenses)}"

        binding.expectedCashText.text =
            "K${formatMoney(summary.expectedCash)}"

        expenseAdapter.submitList(
            summary.expenseRows
        )

        binding.expenseCountText.text =
            when (summary.expenseRows.size) {
                1 ->
                    "1 approved cash expense"

                else ->
                    "${summary.expenseRows.size} approved cash expenses"
            }

        val hasExpenses =
            summary.expenseRows.isNotEmpty()

        binding.noExpensesText.visibility =
            if (hasExpenses) {
                View.GONE
            } else {
                View.VISIBLE
            }

        binding.expensesRecyclerView.visibility =
            if (hasExpenses) {
                View.VISIBLE
            } else {
                View.GONE
            }

        binding.investmentCountText.text =
            when (summary.investmentRows.size) {
                1 ->
                    "1 investment entry"

                else ->
                    "${summary.investmentRows.size} investment entries"
            }

        binding.investmentDetailsText.text =
            buildInvestmentDetails(
                summary.investmentRows
            )

        renderVariance(summary)
    }

    private fun buildInvestmentDetails(
        investments: List<CashInvestmentItem>
    ): String {
        if (investments.isEmpty()) {
            return "No investments in this period."
        }

        return investments.joinToString(
            separator = "\n\n"
        ) { investment ->

            val seller =
                investment.closedBy.ifBlank {
                    "Unknown seller"
                }

            val clockIn =
                investment.clockInMillis
                    ?.let {
                        formatDateTime(it)
                    }
                    ?: "Clock-in unavailable"

            val closeTime =
                investment.closeTimestampMillis
                    ?.let {
                        formatDateTime(it)
                    }
                    ?: "Close time unavailable"

            buildString {
                append("Investment: K")
                append(
                    formatMoney(
                        investment.amount
                    )
                )

                append("\nSeller: ")
                append(seller)

                append("\nClock in: ")
                append(clockIn)

                append("\nClosed: ")
                append(closeTime)
            }
        }
    }

    private fun renderVariance(
        summary: CashReconciliationSummary
    ) {
        val variance =
            summary.cashVariance

        if (
            variance == null ||
            summary.actualDrawerCash == null
        ) {
            renderUnreconciled()
            return
        }

        when {
            variance < -0.009 -> {
                binding.varianceValueText.text =
                    "-K${formatMoney(-variance)}"

                binding.varianceStatusText.text =
                    "Cash short"

                applyVarianceStyle(
                    textColor =
                        Color.parseColor("#D92525"),

                    backgroundColor =
                        Color.parseColor("#FFF0F0"),

                    strokeColor =
                        Color.parseColor("#F4B8B8")
                )
            }

            variance > 0.009 -> {
                binding.varianceValueText.text =
                    "+K${formatMoney(variance)}"

                binding.varianceStatusText.text =
                    "Cash excess"

                applyVarianceStyle(
                    textColor =
                        Color.parseColor("#F57C00"),

                    backgroundColor =
                        Color.parseColor("#FFF8E8"),

                    strokeColor =
                        Color.parseColor("#FFD98A")
                )
            }

            else -> {
                binding.varianceValueText.text =
                    "K0.00"

                binding.varianceStatusText.text =
                    "Cash matches expected drawer cash"

                applyVarianceStyle(
                    textColor =
                        Color.parseColor("#19A65A"),

                    backgroundColor =
                        Color.parseColor("#EAF8F0"),

                    strokeColor =
                        Color.parseColor("#A9DFC1")
                )
            }
        }
    }

    private fun renderUnreconciled() {
        binding.varianceValueText.text =
            "Not reconciled"

        binding.varianceStatusText.text =
            "Enter the actual drawer cash"

        applyVarianceStyle(
            textColor =
                Color.parseColor("#F57C00"),

            backgroundColor =
                Color.parseColor("#FFF8E8"),

            strokeColor =
                Color.parseColor("#FFD98A")
        )
    }

    private fun applyVarianceStyle(
        textColor: Int,
        backgroundColor: Int,
        strokeColor: Int
    ) {
        binding.varianceValueText.setTextColor(
            textColor
        )

        binding.varianceStatusText.setTextColor(
            textColor
        )

        binding.varianceCard.setCardBackgroundColor(
            backgroundColor
        )

        binding.varianceCard.strokeColor =
            strokeColor
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

            binding.saveContainer.setPadding(
                binding.saveContainer.paddingLeft,
                binding.saveContainer.paddingTop,
                binding.saveContainer.paddingRight,
                systemBars.bottom +
                        dpToPx(10)
            )

            insets
        }
    }

    private fun setupSystemBars() {
        window.statusBarColor =
            Color.parseColor("#0071C1")

        window.navigationBarColor =
            Color.parseColor("#FFFFFF")

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

    private fun formatDate(
        millis: Long
    ): String {
        return SimpleDateFormat(
            "dd MMM yyyy",
            Locale.getDefault()
        ).format(
            Date(millis)
        )
    }

    private fun formatDateTime(
        millis: Long
    ): String {
        return SimpleDateFormat(
            "dd MMM yyyy HH:mm",
            Locale.getDefault()
        ).format(
            Date(millis)
        )
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

    private fun dpToPx(
        dp: Int
    ): Int {
        return (
                dp *
                        resources.displayMetrics.density
                ).toInt()
    }
}