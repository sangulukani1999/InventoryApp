package com.example.zed

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.SheetsScopes
import com.google.api.services.sheets.v4.model.ValueRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class GoogleSheetsCashService(
    context: Context,
    private val spreadsheetName: String = "nia-bridge data",
    private val applicationName: String = "Nia Bridge App"
) {

    private val appContext =
        context.applicationContext

    companion object {

        private const val TRANSACTIONS_RANGE =
            "Transactions!A:Z"

        private const val EXPENSES_RANGE =
            "Expenses!A:Z"

        /*
         * Change this only when your closing-balance sheet
         * has a different tab name.
         */
        private const val CLOSING_BALANCE_SHEET_NAME =
            "Closing Balance"

        /*
         * Read A:I so the column indexes are fixed:
         *
         * A = index 0
         * B = index 1
         * C = index 2
         * D = index 3  Available Cash
         * E = index 4
         * F = index 5  Timestamp
         * G = index 6  Investment
         * H = index 7  Closed By
         * I = index 8  Clock In
         */
        private const val CLOSING_BALANCE_RANGE =
            "'$CLOSING_BALANCE_SHEET_NAME'!A:I"

        private const val CASH_RECONCILIATION_RANGE =
            "CashReconciliation!A:N"
    }

    private data class PreparedServices(
        val sheets: Sheets,
        val spreadsheetId: String
    )

    private data class ClosingBalanceResult(
        val openingAvailableCash: Double,
        val investments: List<CashInvestmentItem>
    )

    suspend fun calculateCashReconciliation(
        account: GoogleSignInAccount,
        periodStartMillis: Long,
        periodEndMillis: Long
    ): CashReconciliationSummary =
        withContext(Dispatchers.IO) {

            require(
                periodEndMillis >= periodStartMillis
            ) {
                "The period end cannot be before the period start."
            }

            val services =
                prepareServices(account)

            val closingBalanceResult =
                fetchAvailableCashAndInvestments(
                    sheets = services.sheets,
                    spreadsheetId = services.spreadsheetId,
                    periodStartMillis = periodStartMillis,
                    periodEndMillis = periodEndMillis
                )

            val cashSales =
                fetchCashSales(
                    sheets = services.sheets,
                    spreadsheetId = services.spreadsheetId,
                    periodStartMillis = periodStartMillis,
                    periodEndMillis = periodEndMillis
                )

            val expenseRows =
                fetchApprovedCashExpenses(
                    sheets = services.sheets,
                    spreadsheetId = services.spreadsheetId,
                    periodStartMillis = periodStartMillis,
                    periodEndMillis = periodEndMillis
                )

            val totalExpenses =
                expenseRows.sumOf {
                    it.amount
                }

            val totalInvestments =
                closingBalanceResult
                    .investments
                    .sumOf {
                        it.amount
                    }

            val expectedCash =
                closingBalanceResult.openingAvailableCash +
                        cashSales +
                        totalInvestments -
                        totalExpenses

            CashReconciliationSummary(
                openingAvailableCash =
                    closingBalanceResult.openingAvailableCash,

                cashSales =
                    cashSales,

                investments =
                    totalInvestments,

                approvedCashExpenses =
                    totalExpenses,

                expectedCash =
                    expectedCash,

                actualDrawerCash =
                    null,

                cashVariance =
                    null,

                expenseRows =
                    expenseRows,

                investmentRows =
                    closingBalanceResult.investments,

                periodStartMillis =
                    periodStartMillis,

                periodEndMillis =
                    periodEndMillis
            )
        }

    private fun fetchAvailableCashAndInvestments(
        sheets: Sheets,
        spreadsheetId: String,
        periodStartMillis: Long,
        periodEndMillis: Long
    ): ClosingBalanceResult {

        val values =
            sheets.spreadsheets()
                .values()
                .get(
                    spreadsheetId,
                    CLOSING_BALANCE_RANGE
                )
                .execute()
                .getValues()
                .orEmpty()

        if (values.isEmpty()) {
            return ClosingBalanceResult(
                openingAvailableCash = 0.0,
                investments = emptyList()
            )
        }

        val rows =
            if (looksLikeHeader(values.first())) {
                values.drop(1)
            } else {
                values
            }

        var latestOpeningTimestamp =
            Long.MIN_VALUE

        var openingAvailableCash =
            0.0

        val investmentRows =
            mutableListOf<CashInvestmentItem>()

        rows.forEach { row ->

            /*
             * Column D = Available Cash.
             *
             * Because the range starts at A,
             * Column D is index 3.
             */
            val availableCash =
                parseDouble(
                    cell(row, 3)
                )

            /*
             * Column F = close timestamp.
             * Column F is index 5.
             */
            val closeTimestampMillis =
                parseDateToMillis(
                    cell(row, 5)
                )

            /*
             * Column G = investment.
             * Column G is index 6.
             */
            val investmentAmount =
                parseDouble(
                    cell(row, 6)
                )

            /*
             * Column H = closed by.
             * Column H is index 7.
             */
            val closedBy =
                cell(row, 7)

            /*
             * Column I = clock in.
             * Column I is index 8.
             */
            val clockInMillis =
                parseDateToMillis(
                    cell(row, 8)
                )

            /*
             * Find the latest Available Cash before
             * the reconciliation period starts.
             */
            if (
                closeTimestampMillis != null &&
                closeTimestampMillis < periodStartMillis &&
                closeTimestampMillis > latestOpeningTimestamp
            ) {
                latestOpeningTimestamp =
                    closeTimestampMillis

                openingAvailableCash =
                    availableCash
            }

            /*
             * Include investments for shifts that fall
             * within the selected timeframe.
             *
             * Clock In is preferred because it identifies
             * when the seller's shift began.
             */
            val investmentTimestamp =
                clockInMillis ?: closeTimestampMillis

            if (
                investmentAmount > 0.0 &&
                investmentTimestamp != null &&
                investmentTimestamp in
                periodStartMillis..periodEndMillis
            ) {
                investmentRows.add(
                    CashInvestmentItem(
                        amount =
                            investmentAmount,

                        closedBy =
                            closedBy,

                        clockInMillis =
                            clockInMillis,

                        closeTimestampMillis =
                            closeTimestampMillis
                    )
                )
            }
        }

        return ClosingBalanceResult(
            openingAvailableCash =
                openingAvailableCash,

            investments =
                investmentRows.sortedBy {
                    it.clockInMillis
                        ?: it.closeTimestampMillis
                        ?: Long.MAX_VALUE
                }
        )
    }

    private fun fetchCashSales(
        sheets: Sheets,
        spreadsheetId: String,
        periodStartMillis: Long,
        periodEndMillis: Long
    ): Double {

        val values =
            sheets.spreadsheets()
                .values()
                .get(
                    spreadsheetId,
                    TRANSACTIONS_RANGE
                )
                .execute()
                .getValues()
                .orEmpty()

        if (values.isEmpty()) {
            return 0.0
        }

        val headers =
            values.first().map {
                normalizeHeader(
                    it.toString()
                )
            }

        val rows =
            values.drop(1)

        val paymentMethodIndex =
            findHeaderIndex(
                headers,
                "payment method",
                "payment",
                "payment type",
                "method",
                "tender type"
            )

        val totalIndex =
            findHeaderIndex(
                headers,
                "total",
                "total amount",
                "transaction total",
                "grand total",
                "sale total",
                "net total"
            )

        val timestampIndex =
            findHeaderIndex(
                headers,
                "timestamp",
                "transaction date",
                "date",
                "created at",
                "transaction time"
            )

        var totalCashSales =
            0.0

        rows.forEach { row ->

            val timestamp =
                findTimestamp(
                    row = row,
                    preferredIndex = timestampIndex
                ) ?: return@forEach

            if (
                timestamp !in
                periodStartMillis..periodEndMillis
            ) {
                return@forEach
            }

            val paymentMethod =
                if (paymentMethodIndex >= 0) {
                    cell(
                        row,
                        paymentMethodIndex
                    )
                } else {
                    findPaymentMethod(row)
                }

            if (
                !paymentMethod.equals(
                    "Cash",
                    ignoreCase = true
                )
            ) {
                return@forEach
            }

            val total =
                if (totalIndex >= 0) {
                    parseDouble(
                        cell(
                            row,
                            totalIndex
                        )
                    )
                } else {
                    findLikelyTransactionTotal(
                        row
                    )
                }

            totalCashSales +=
                total
        }

        return totalCashSales
    }

    private fun fetchApprovedCashExpenses(
        sheets: Sheets,
        spreadsheetId: String,
        periodStartMillis: Long,
        periodEndMillis: Long
    ): List<CashExpenseItem> {

        val values =
            sheets.spreadsheets()
                .values()
                .get(
                    spreadsheetId,
                    EXPENSES_RANGE
                )
                .execute()
                .getValues()
                .orEmpty()

        if (values.isEmpty()) {
            return emptyList()
        }

        val headers =
            values.first().map {
                normalizeHeader(
                    it.toString()
                )
            }

        val rows =
            values.drop(1)

        val uniqueIdIndex =
            findHeaderIndex(
                headers,
                "unique id",
                "uniqueid",
                "id"
            )

        val itemIndex =
            findHeaderIndex(
                headers,
                "item",
                "expense",
                "expense item",
                "name"
            )

        val descriptionIndex =
            findHeaderIndex(
                headers,
                "description",
                "details",
                "notes"
            )

        val amountIndex =
            findHeaderIndex(
                headers,
                "amount",
                "total",
                "expense amount"
            )

        val permitIndex =
            findHeaderIndex(
                headers,
                "permit",
                "approved",
                "approval",
                "authorised",
                "authorized"
            )

        val paymentMethodIndex =
            findHeaderIndex(
                headers,
                "payment method",
                "payment",
                "payment type",
                "method"
            )

        val userIndex =
            findHeaderIndex(
                headers,
                "user",
                "created by",
                "entered by"
            )

        val timestampIndex =
            findHeaderIndex(
                headers,
                "timestamp",
                "date",
                "created at",
                "expense date"
            )

        return rows.mapNotNull { row ->

            val timestamp =
                findTimestamp(
                    row = row,
                    preferredIndex = timestampIndex
                ) ?: return@mapNotNull null

            if (
                timestamp !in
                periodStartMillis..periodEndMillis
            ) {
                return@mapNotNull null
            }

            val approved =
                if (permitIndex >= 0) {
                    parseBoolean(
                        cell(
                            row,
                            permitIndex
                        )
                    )
                } else {
                    true
                }

            if (!approved) {
                return@mapNotNull null
            }

            /*
             * When Payment Method is blank or unavailable,
             * the expense is temporarily treated as Cash.
             */
            val paymentMethod =
                if (paymentMethodIndex >= 0) {
                    cell(
                        row,
                        paymentMethodIndex
                    ).ifBlank {
                        "Cash"
                    }
                } else {
                    "Cash"
                }

            if (
                !paymentMethod.equals(
                    "Cash",
                    ignoreCase = true
                )
            ) {
                return@mapNotNull null
            }

            val amount =
                parseDouble(
                    cell(
                        row,
                        amountIndex
                    )
                )

            if (amount <= 0.0) {
                return@mapNotNull null
            }

            CashExpenseItem(
                uniqueId =
                    cell(
                        row,
                        uniqueIdIndex
                    ),

                item =
                    cell(
                        row,
                        itemIndex
                    ).ifBlank {
                        "Expense"
                    },

                description =
                    cell(
                        row,
                        descriptionIndex
                    ),

                amount =
                    amount,

                approved =
                    true,

                paymentMethod =
                    paymentMethod,

                user =
                    cell(
                        row,
                        userIndex
                    ),

                timestamp =
                    timestamp
            )
        }
    }

    suspend fun saveCashReconciliation(
        account: GoogleSignInAccount,
        summary: CashReconciliationSummary,
        reconciledBy: String
    ) = withContext(Dispatchers.IO) {

        val actualDrawerCash =
            summary.actualDrawerCash
                ?: throw IllegalStateException(
                    "Actual drawer cash has not been entered."
                )

        val cashVariance =
            summary.cashVariance
                ?: actualDrawerCash -
                summary.expectedCash

        val services =
            prepareServices(account)

        val sessionId =
            StockTakeRepository
                .session
                .value
                ?.sessionId
                .orEmpty()

        val row: List<Any> =
            listOf(
                sessionId,
                formatDateTime(
                    summary.periodStartMillis
                ),
                formatDateTime(
                    summary.periodEndMillis
                ),
                summary.openingAvailableCash,
                summary.cashSales,
                summary.investments,
                summary.approvedCashExpenses,
                summary.expectedCash,
                actualDrawerCash,
                cashVariance,
                summary.statusText,
                reconciledBy,
                formatDateTime(
                    System.currentTimeMillis()
                ),
                summary.investmentRows.size
            )

        val body =
            ValueRange().setValues(
                listOf(row)
            )

        services.sheets
            .spreadsheets()
            .values()
            .append(
                services.spreadsheetId,
                CASH_RECONCILIATION_RANGE,
                body
            )
            .setValueInputOption(
                "USER_ENTERED"
            )
            .setInsertDataOption(
                "INSERT_ROWS"
            )
            .execute()
    }

    private fun looksLikeHeader(
        row: List<Any>
    ): Boolean {

        return row.any {
            val value =
                it.toString()
                    .trim()
                    .lowercase()

            value.contains("available cash") ||
                    value.contains("closing balance") ||
                    value.contains("uniqueid") ||
                    value.contains("timestamp") ||
                    value.contains("investment")
        }
    }

    private fun findPaymentMethod(
        row: List<Any>
    ): String {

        return row
            .map {
                it.toString().trim()
            }
            .firstOrNull {
                it.equals(
                    "Cash",
                    ignoreCase = true
                ) ||
                        it.equals(
                            "Card",
                            ignoreCase = true
                        ) ||
                        it.equals(
                            "Credit",
                            ignoreCase = true
                        ) ||
                        it.contains(
                            "Airtel",
                            ignoreCase = true
                        ) ||
                        it.contains(
                            "MTN",
                            ignoreCase = true
                        ) ||
                        it.contains(
                            "Bank",
                            ignoreCase = true
                        )
            }
            .orEmpty()
    }

    private fun findLikelyTransactionTotal(
        row: List<Any>
    ): Double {

        return row
            .takeLast(8)
            .asReversed()
            .map {
                parseDouble(
                    it.toString()
                )
            }
            .firstOrNull {
                it > 0.0
            }
            ?: 0.0
    }

    private fun findTimestamp(
        row: List<Any>,
        preferredIndex: Int
    ): Long? {

        if (preferredIndex >= 0) {
            parseDateToMillis(
                cell(
                    row,
                    preferredIndex
                )
            )?.let {
                return it
            }
        }

        row.forEach { value ->
            parseDateToMillis(
                value.toString()
            )?.let {
                return it
            }
        }

        return null
    }

    private fun parseDateToMillis(
        value: String
    ): Long? {

        if (value.isBlank()) {
            return null
        }

        val patterns =
            listOf(
                "dd/MM/yyyy HH:mm:ss",
                "d/M/yyyy HH:mm:ss",
                "dd/MM/yyyy",
                "d/M/yyyy",
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd",
                "yyyy-MM-dd'T'HH:mm:ss",
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
            )

        patterns.forEach { pattern ->
            try {
                val formatter =
                    SimpleDateFormat(
                        pattern,
                        Locale.getDefault()
                    ).apply {
                        isLenient =
                            false

                        if (
                            pattern.endsWith(
                                "'Z'"
                            )
                        ) {
                            timeZone =
                                TimeZone.getTimeZone(
                                    "UTC"
                                )
                        }
                    }

                formatter.parse(
                    value
                )?.time?.let {
                    return it
                }

            } catch (_: Exception) {
                // Try the next format.
            }
        }

        value.toLongOrNull()
            ?.let { numeric ->

                if (
                    numeric in
                    1_000_000_000L..
                    9_999_999_999L
                ) {
                    return numeric * 1000L
                }

                if (
                    numeric >
                    9_999_999_999L
                ) {
                    return numeric
                }
            }

        value.toDoubleOrNull()
            ?.let { serialDate ->

                if (
                    serialDate in
                    20_000.0..100_000.0
                ) {
                    val sheetsEpoch =
                        -2209161600000L

                    return sheetsEpoch +
                            (
                                    serialDate *
                                            86_400_000.0
                                    ).toLong()
                }
            }

        return null
    }

    private fun parseBoolean(
        value: String
    ): Boolean {

        return when (
            value.trim().lowercase()
        ) {
            "true",
            "yes",
            "approved",
            "permit",
            "1" -> true

            else -> false
        }
    }

    private fun parseDouble(
        value: String
    ): Double {

        return value
            .replace(
                ",",
                ""
            )
            .replace(
                "ZMW",
                "",
                ignoreCase = true
            )
            .replace(
                "K",
                "",
                ignoreCase = true
            )
            .trim()
            .toDoubleOrNull()
            ?: 0.0
    }

    private fun normalizeHeader(
        value: String
    ): String {

        return value
            .trim()
            .lowercase()
            .replace(
                "_",
                " "
            )
            .replace(
                Regex("\\s+"),
                " "
            )
    }

    private fun findHeaderIndex(
        headers: List<String>,
        vararg possibleNames: String
    ): Int {

        val normalizedNames =
            possibleNames.map {
                normalizeHeader(it)
            }

        return headers.indexOfFirst {
            it in normalizedNames
        }
    }

    private fun cell(
        row: List<Any>,
        index: Int
    ): String {

        if (index < 0) {
            return ""
        }

        return row
            .getOrNull(index)
            ?.toString()
            ?.trim()
            .orEmpty()
    }

    private fun formatDateTime(
        millis: Long
    ): String {

        return SimpleDateFormat(
            "dd/MM/yyyy HH:mm:ss",
            Locale.getDefault()
        ).format(
            Date(millis)
        )
    }

    private fun prepareServices(
        account: GoogleSignInAccount
    ): PreparedServices {

        val sheets =
            createSheetsService(
                account
            )

        val drive =
            createDriveService(
                account
            )

        val spreadsheetId =
            findSpreadsheetId(
                drive
            ) ?: throw IllegalStateException(
                "Spreadsheet '$spreadsheetName' was not found."
            )

        return PreparedServices(
            sheets =
                sheets,

            spreadsheetId =
                spreadsheetId
        )
    }

    private fun createSheetsService(
        account: GoogleSignInAccount
    ): Sheets {

        val credential =
            GoogleAccountCredential
                .usingOAuth2(
                    appContext,
                    listOf(
                        SheetsScopes.SPREADSHEETS
                    )
                )
                .apply {
                    selectedAccount =
                        account.account
                }

        return Sheets.Builder(
            GoogleNetHttpTransport
                .newTrustedTransport(),
            GsonFactory
                .getDefaultInstance(),
            credential
        )
            .setApplicationName(
                applicationName
            )
            .build()
    }

    private fun createDriveService(
        account: GoogleSignInAccount
    ): Drive {

        val credential =
            GoogleAccountCredential
                .usingOAuth2(
                    appContext,
                    listOf(
                        DriveScopes.DRIVE_READONLY
                    )
                )
                .apply {
                    selectedAccount =
                        account.account
                }

        return Drive.Builder(
            GoogleNetHttpTransport
                .newTrustedTransport(),
            GsonFactory
                .getDefaultInstance(),
            credential
        )
            .setApplicationName(
                applicationName
            )
            .build()
    }

    private fun findSpreadsheetId(
        drive: Drive
    ): String? {

        val escapedName =
            spreadsheetName.replace(
                "'",
                "\\'"
            )

        return drive.files()
            .list()
            .setQ(
                "name='$escapedName' and " +
                        "mimeType='application/vnd.google-apps.spreadsheet' " +
                        "and trashed=false"
            )
            .setSpaces(
                "drive"
            )
            .setFields(
                "files(id,name)"
            )
            .execute()
            .files
            .firstOrNull()
            ?.id
    }
}