package com.example.zed

import android.content.Context
import android.util.Log
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
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class GoogleSheetsStockTakeService(
    context: Context,
    private val spreadsheetName: String = "nia-bridge data",
    private val applicationName: String = "Nia Bridge App"
) {

    private val appContext = context.applicationContext

    companion object {
        private const val TAG = "StockTakeSheets"

        private const val PRODUCTS_RANGE = "Products!A:M"
        private const val LOCATIONS_RANGE = "product_location!A:F"
        private const val TRANSACTIONS_RANGE = "Transactions!A:Z"

        /*
         * Backward-compatible countData layout:
         *
         * A = Session ID
         * B = Barcode
         * C = Product Name
         * D = Expected Quantity
         * E = Counted Quantity
         * F = Counted By
         * G = Variance
         * H = Location ID
         * I = Counted At
         * J = Count Type
         * K = Period Start
         * L = Period End
         * M = Product ID
         */
        private const val COUNT_DATA_RANGE = "countData!A:M"
    }

    private data class RawProduct(
        val productId: String,
        val productName: String,
        val imageUrl: String?,
        val barcode: String,
        val categoryId: String,
        val unit: String,
        val expectedQuantity: Double,
        val minimumOrder: Double,
        val unitCost: Double,
        val locationIds: List<String>
    )

    private data class PreviousCount(
        val barcode: String,
        val countedQuantity: Double,
        val countedBy: String?,
        val countedAt: Long?
    )

    suspend fun loadWeeklyStockTake(
        account: GoogleSignInAccount,
        periodStartMillis: Long,
        periodEndMillis: Long
    ): List<StockTakeProduct> = withContext(Dispatchers.IO) {
        val services = prepareServices(account)

        val rawProducts = fetchProducts(
            services.sheets,
            services.spreadsheetId
        )

        val locationMap = fetchLocationMap(
            services.sheets,
            services.spreadsheetId
        )

        val requiredBarcodes = fetchTransactedBarcodes(
            sheets = services.sheets,
            spreadsheetId = services.spreadsheetId,
            periodStartMillis = periodStartMillis,
            periodEndMillis = periodEndMillis
        )

        val previousCounts = fetchPreviousCounts(
            sheets = services.sheets,
            spreadsheetId = services.spreadsheetId,
            periodStartMillis = periodStartMillis,
            periodEndMillis = periodEndMillis,
            type = StockTakeType.WEEKLY
        )

        buildStockProducts(
            rawProducts = rawProducts,
            locationMap = locationMap,
            requiredBarcodes = requiredBarcodes,
            previousCounts = previousCounts
        )
    }

    suspend fun loadMonthlyStockTake(
        account: GoogleSignInAccount,
        periodStartMillis: Long,
        periodEndMillis: Long
    ): List<StockTakeProduct> = withContext(Dispatchers.IO) {
        val services = prepareServices(account)

        val rawProducts = fetchProducts(
            services.sheets,
            services.spreadsheetId
        )

        val locationMap = fetchLocationMap(
            services.sheets,
            services.spreadsheetId
        )

        val previousCounts = fetchPreviousCounts(
            sheets = services.sheets,
            spreadsheetId = services.spreadsheetId,
            periodStartMillis = periodStartMillis,
            periodEndMillis = periodEndMillis,
            type = StockTakeType.MONTHLY
        )

        buildStockProducts(
            rawProducts = rawProducts,
            locationMap = locationMap,
            requiredBarcodes = null,
            previousCounts = previousCounts
        )
    }

    suspend fun saveOrUpdateCount(
        account: GoogleSignInAccount,
        session: StockTakeSession,
        product: StockTakeProduct,
        countedQuantity: Double,
        countedBy: String
    ) = withContext(Dispatchers.IO) {
        require(countedQuantity >= 0) {
            "Counted quantity cannot be negative."
        }

        val services = prepareServices(account)
        val countedAt = System.currentTimeMillis()
        val variance = countedQuantity - product.expectedQuantity

        val rowValues: List<Any> = listOf(
            session.sessionId,
            product.barcode,
            product.productName,
            product.expectedQuantity,
            countedQuantity,
            countedBy,
            variance,
            product.locationId,
            formatDateTime(countedAt),
            session.type.name,
            formatDateTime(session.periodStartMillis),
            formatDateTime(session.periodEndMillis),
            product.productId
        )

        val existingRowNumber = findExistingCountRow(
            sheets = services.sheets,
            spreadsheetId = services.spreadsheetId,
            sessionId = session.sessionId,
            barcode = product.barcode
        )

        val body = ValueRange().setValues(
            listOf(rowValues)
        )

        if (existingRowNumber != null) {
            services.sheets.spreadsheets()
                .values()
                .update(
                    services.spreadsheetId,
                    "countData!A$existingRowNumber:M$existingRowNumber",
                    body
                )
                .setValueInputOption("USER_ENTERED")
                .execute()
        } else {
            services.sheets.spreadsheets()
                .values()
                .append(
                    services.spreadsheetId,
                    "countData!A:M",
                    body
                )
                .setValueInputOption("USER_ENTERED")
                .setInsertDataOption("INSERT_ROWS")
                .execute()
        }

        StockTakeRepository.saveCountLocally(
            barcode = product.barcode,
            countedQuantity = countedQuantity,
            countedBy = countedBy,
            countedAt = countedAt
        )
    }

    private fun buildStockProducts(
        rawProducts: List<RawProduct>,
        locationMap: Map<String, ProductLocation>,
        requiredBarcodes: Set<String>?,
        previousCounts: Map<String, PreviousCount>
    ): List<StockTakeProduct> {
        return rawProducts
            .asSequence()
            .filter { product ->
                requiredBarcodes == null ||
                        requiredBarcodes.contains(
                            normalizeBarcode(product.barcode)
                        )
            }
            .map { product ->
                val mappedLocations = product.locationIds
                    .mapNotNull { locationId ->
                        locationMap[normalizeLocationId(locationId)]
                    }
                    .distinctBy {
                        normalizeLocationId(it.locationId)
                    }

                val previousCount = previousCounts[
                    normalizeBarcode(product.barcode)
                ]

                StockTakeProduct(
                    productId = product.productId,
                    productName = product.productName,
                    imageUrl = product.imageUrl,
                    barcode = product.barcode,
                    categoryId = product.categoryId,
                    unit = product.unit,
                    expectedQuantity = product.expectedQuantity,
                    minimumOrder = product.minimumOrder,
                    unitCost = product.unitCost,
                    locationIds = product.locationIds,
                    locations = mappedLocations,
                    countedQuantity = previousCount?.countedQuantity,
                    countedBy = previousCount?.countedBy,
                    countedAt = previousCount?.countedAt
                )
            }
            .sortedWith(
                compareBy<StockTakeProduct>(
                    { it.aisle.lowercase() },
                    { it.rack.lowercase() },
                    { extractNumber(it.shelf) },
                    { it.shelf.lowercase() },
                    { it.productName.lowercase() }
                )
            )
            .toList()
    }

    private fun fetchProducts(
        sheets: Sheets,
        spreadsheetId: String
    ): List<RawProduct> {
        val rows = sheets.spreadsheets()
            .values()
            .get(spreadsheetId, PRODUCTS_RANGE)
            .execute()
            .getValues()
            ?.drop(1)
            .orEmpty()

        return rows.mapNotNull { row ->
            val productId = cell(row, 0)
            val productName = cell(row, 1)
            val imageUrl = cell(row, 2).takeIf { it.isNotBlank() }
            val barcode = cell(row, 3)

            if (productId.isBlank() || barcode.isBlank()) {
                return@mapNotNull null
            }

            RawProduct(
                productId = productId,
                productName = productName,
                imageUrl = imageUrl,
                barcode = barcode,
                categoryId = cell(row, 4),
                unit = cell(row, 5),
                expectedQuantity = parseDouble(cell(row, 6)),
                minimumOrder = parseDouble(cell(row, 7)),
                unitCost = parseDouble(cell(row, 8)),
                locationIds = parseLocationIds(cell(row, 9))
            )
        }
    }

    private fun fetchLocationMap(
        sheets: Sheets,
        spreadsheetId: String
    ): Map<String, ProductLocation> {
        val rows = sheets.spreadsheets()
            .values()
            .get(spreadsheetId, LOCATIONS_RANGE)
            .execute()
            .getValues()
            ?.drop(1)
            .orEmpty()

        return rows.mapNotNull { row ->
            val locationId = cell(row, 0)

            if (locationId.isBlank()) {
                return@mapNotNull null
            }

            ProductLocation(
                locationId = locationId,
                aisle = cell(row, 1),
                rack = cell(row, 2),
                shelf = cell(row, 3)
            )
        }.associateBy {
            normalizeLocationId(it.locationId)
        }
    }

    private fun fetchTransactedBarcodes(
        sheets: Sheets,
        spreadsheetId: String,
        periodStartMillis: Long,
        periodEndMillis: Long
    ): Set<String> {
        val rows = sheets.spreadsheets()
            .values()
            .get(spreadsheetId, TRANSACTIONS_RANGE)
            .execute()
            .getValues()
            ?.drop(1)
            .orEmpty()

        val barcodes = mutableSetOf<String>()

        rows.forEach { row ->
            try {
                val transactionDate = findTransactionDate(row)
                    ?: return@forEach

                if (
                    transactionDate < periodStartMillis ||
                    transactionDate > periodEndMillis
                ) {
                    return@forEach
                }

                val itemsJson = findTransactionItemsJson(row)
                    ?: return@forEach

                val items = JSONArray(itemsJson)

                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue

                    val barcode = item.optString("id").trim()

                    if (barcode.isNotBlank()) {
                        barcodes.add(normalizeBarcode(barcode))
                    }
                }
            } catch (exception: Exception) {
                Log.w(
                    TAG,
                    "Invalid transaction row skipped: ${exception.message}"
                )
            }
        }

        return barcodes
    }

    private fun fetchPreviousCounts(
        sheets: Sheets,
        spreadsheetId: String,
        periodStartMillis: Long,
        periodEndMillis: Long,
        type: StockTakeType
    ): Map<String, PreviousCount> {
        return try {
            val rows = sheets.spreadsheets()
                .values()
                .get(spreadsheetId, COUNT_DATA_RANGE)
                .execute()
                .getValues()
                ?.drop(1)
                .orEmpty()

            val result = linkedMapOf<String, PreviousCount>()

            rows.forEach { row ->
                val barcode = cell(row, 1)

                if (barcode.isBlank()) {
                    return@forEach
                }

                val countedAt = parseDateToMillis(
                    cell(row, 8)
                ) ?: return@forEach

                val countType = cell(row, 9)

                val dateMatches =
                    countedAt in periodStartMillis..periodEndMillis

                val typeMatches =
                    countType.isBlank() ||
                            countType.equals(
                                type.name,
                                ignoreCase = true
                            )

                if (dateMatches && typeMatches) {
                    result[normalizeBarcode(barcode)] =
                        PreviousCount(
                            barcode = barcode,
                            countedQuantity = parseDouble(cell(row, 4)),
                            countedBy = cell(row, 5)
                                .takeIf { it.isNotBlank() },
                            countedAt = countedAt
                        )
                }
            }

            result
        } catch (exception: Exception) {
            Log.w(
                TAG,
                "Unable to load countData: ${exception.message}"
            )

            emptyMap()
        }
    }

    private fun findExistingCountRow(
        sheets: Sheets,
        spreadsheetId: String,
        sessionId: String,
        barcode: String
    ): Int? {
        val rows = sheets.spreadsheets()
            .values()
            .get(spreadsheetId, "countData!A:B")
            .execute()
            .getValues()
            .orEmpty()

        val normalizedBarcode = normalizeBarcode(barcode)

        rows.forEachIndexed { index, row ->
            val rowSessionId = cell(row, 0)
            val rowBarcode = cell(row, 1)

            if (
                rowSessionId == sessionId &&
                normalizeBarcode(rowBarcode) == normalizedBarcode
            ) {
                return index + 1
            }
        }

        return null
    }

    private fun parseLocationIds(rawValue: String): List<String> {
        if (rawValue.isBlank()) {
            return emptyList()
        }

        /*
         * Handles:
         * ["LOC-123"]
         * ['LOC-123']
         * [LOC-123]
         * LOC-123
         * LOC-123, LOC-456
         */
        val cleaned = rawValue
            .replace("[", "")
            .replace("]", "")
            .replace("\"", "")
            .replace("'", "")

        return cleaned
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { normalizeLocationId(it) }
    }

    private fun findTransactionItemsJson(
        row: List<Any>
    ): String? {
        return row
            .map { it.toString().trim() }
            .firstOrNull { value ->
                value.startsWith("[") &&
                        value.endsWith("]") &&
                        (
                                value.contains("\"id\"") ||
                                        value.contains("product_name")
                                )
            }
    }

    private fun findTransactionDate(
        row: List<Any>
    ): Long? {
        row.forEach { cell ->
            val value = cell.toString().trim()

            parseDateToMillis(value)?.let {
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

        val patterns = listOf(
            "dd/MM/yyyy HH:mm:ss",
            "d/M/yyyy HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
        )

        patterns.forEach { pattern ->
            try {
                val formatter = SimpleDateFormat(
                    pattern,
                    Locale.getDefault()
                ).apply {
                    isLenient = false

                    if (pattern.endsWith("'Z'")) {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }
                }

                formatter.parse(value)?.time?.let {
                    return it
                }
            } catch (_: Exception) {
                // Try the next format.
            }
        }

        value.toLongOrNull()?.let { numeric ->
            if (numeric in 1_000_000_000L..9_999_999_999L) {
                return numeric * 1000L
            }

            if (numeric > 9_999_999_999L) {
                return numeric
            }
        }

        value.toDoubleOrNull()?.let { serialDate ->
            if (serialDate in 20_000.0..100_000.0) {
                val googleSheetsEpoch = -2209161600000L

                return googleSheetsEpoch +
                        (serialDate * 86_400_000.0).toLong()
            }
        }

        return null
    }

    private fun parseDouble(value: String): Double {
        return value
            .replace(",", "")
            .replace("ZMW", "", ignoreCase = true)
            .replace("K", "", ignoreCase = true)
            .trim()
            .toDoubleOrNull()
            ?: 0.0
    }

    private fun formatDateTime(millis: Long): String {
        return SimpleDateFormat(
            "dd/MM/yyyy HH:mm:ss",
            Locale.getDefault()
        ).format(Date(millis))
    }

    private fun cell(
        row: List<Any>,
        index: Int
    ): String {
        return row.getOrNull(index)
            ?.toString()
            ?.trim()
            .orEmpty()
    }

    private fun normalizeBarcode(value: String): String {
        return value
            .trim()
            .replace(" ", "")
    }

    private fun normalizeLocationId(value: String): String {
        return value.trim().uppercase()
    }

    private fun extractNumber(value: String): Int {
        return Regex("\\d+")
            .find(value)
            ?.value
            ?.toIntOrNull()
            ?: Int.MAX_VALUE
    }

    private data class PreparedServices(
        val sheets: Sheets,
        val spreadsheetId: String
    )

    private fun prepareServices(
        account: GoogleSignInAccount
    ): PreparedServices {
        val sheets = createSheetsService(account)
        val drive = createDriveService(account)

        val spreadsheetId = findSpreadsheetId(drive)
            ?: throw IllegalStateException(
                "Spreadsheet '$spreadsheetName' was not found."
            )

        return PreparedServices(
            sheets = sheets,
            spreadsheetId = spreadsheetId
        )
    }

    private fun createSheetsService(
        account: GoogleSignInAccount
    ): Sheets {
        val credential = GoogleAccountCredential.usingOAuth2(
            appContext,
            listOf(SheetsScopes.SPREADSHEETS)
        ).apply {
            selectedAccount = account.account
        }

        return Sheets.Builder(
            GoogleNetHttpTransport.newTrustedTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        )
            .setApplicationName(applicationName)
            .build()
    }

    private fun createDriveService(
        account: GoogleSignInAccount
    ): Drive {
        val credential = GoogleAccountCredential.usingOAuth2(
            appContext,
            listOf(DriveScopes.DRIVE_READONLY)
        ).apply {
            selectedAccount = account.account
        }

        return Drive.Builder(
            GoogleNetHttpTransport.newTrustedTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        )
            .setApplicationName(applicationName)
            .build()
    }

    private fun findSpreadsheetId(
        drive: Drive
    ): String? {
        val escapedName = spreadsheetName
            .replace("'", "\\'")

        return drive.files()
            .list()
            .setQ(
                "name='$escapedName' and " +
                        "mimeType='application/vnd.google-apps.spreadsheet' " +
                        "and trashed=false"
            )
            .setSpaces("drive")
            .setFields("files(id,name)")
            .execute()
            .files
            .firstOrNull()
            ?.id
    }
}