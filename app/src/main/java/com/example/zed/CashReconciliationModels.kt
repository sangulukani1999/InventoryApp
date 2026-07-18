package com.example.zed

data class CashExpenseItem(
    val uniqueId: String,
    val item: String,
    val description: String,
    val amount: Double,
    val approved: Boolean,
    val paymentMethod: String,
    val user: String,
    val timestamp: Long?
)

data class CashInvestmentItem(
    val amount: Double,
    val closedBy: String,
    val clockInMillis: Long?,
    val closeTimestampMillis: Long?
)

data class CashReconciliationSummary(
    /*
     * Latest Available Cash from Column D
     * before the selected reconciliation period.
     */
    val openingAvailableCash: Double = 0.0,

    /*
     * Cash transactions within the selected timeframe.
     */
    val cashSales: Double = 0.0,

    /*
     * Investment amounts from Column G
     * within the selected timeframe.
     */
    val investments: Double = 0.0,

    /*
     * Approved expenses and purchases paid by cash.
     */
    val approvedCashExpenses: Double = 0.0,

    /*
     * openingAvailableCash
     * + cashSales
     * + investments
     * - approvedCashExpenses
     */
    val expectedCash: Double = 0.0,

    /*
     * Cash physically counted by the user.
     */
    val actualDrawerCash: Double? = null,

    /*
     * actualDrawerCash - expectedCash
     */
    val cashVariance: Double? = null,

    val expenseRows: List<CashExpenseItem> =
        emptyList(),

    val investmentRows: List<CashInvestmentItem> =
        emptyList(),

    val periodStartMillis: Long = 0L,
    val periodEndMillis: Long = 0L
) {

    val isReconciled: Boolean
        get() = actualDrawerCash != null

    val statusText: String
        get() = when {
            cashVariance == null ->
                "Not reconciled"

            cashVariance < 0.0 ->
                "Cash short"

            cashVariance > 0.0 ->
                "Cash excess"

            else ->
                "Cash matches"
        }

    val shortageAmount: Double
        get() = if (
            cashVariance != null &&
            cashVariance < 0.0
        ) {
            -cashVariance
        } else {
            0.0
        }

    val excessAmount: Double
        get() = if (
            cashVariance != null &&
            cashVariance > 0.0
        ) {
            cashVariance
        } else {
            0.0
        }

    val hasInvestments: Boolean
        get() = investments > 0.0

    val hasExpenses: Boolean
        get() = approvedCashExpenses > 0.0

    val hasMaterialVariance: Boolean
        get() = cashVariance?.let {
            kotlin.math.abs(it) > 0.01
        } ?: false
}

data class CashReconciliationUiState(
    val loading: Boolean = false,
    val saving: Boolean = false,

    val errorMessage: String? = null,
    val saveSuccessful: Boolean = false,

    val summary: CashReconciliationSummary =
        CashReconciliationSummary()
)