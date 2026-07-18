package com.example.zed

enum class StockTakeStatus {
    NOT_STARTED,
    LOADING,
    IN_PROGRESS,
    COMPLETED,
    FAILED
}

enum class StockTakeType {
    WEEKLY,
    MONTHLY,
    RECOVERY
}

data class ProductLocation(
    val locationId: String,
    val aisle: String,
    val rack: String,
    val shelf: String
) {
    val displayName: String
        get() = listOf(
            aisle,
            rack,
            shelf
        )
            .filter { it.isNotBlank() }
            .joinToString(" › ")
}

data class StockTakeProduct(
    val productId: String,
    val productName: String,
    val imageUrl: String?,
    val barcode: String,

    val categoryId: String,
    val unit: String,

    /*
     * Products column G.
     * This is the quantity currently available in the system
     * and is compared with the physical quantity counted.
     */
    val expectedQuantity: Double,

    val minimumOrder: Double,
    val unitCost: Double,

    /*
     * A product may have one or more Location_ID values.
     */
    val locationIds: List<String>,

    /*
     * Location details loaded by matching:
     *
     * Products.Location_IDs
     *          ↓
     * product_location.Location_ID
     */
    val locations: List<ProductLocation>,

    val countedQuantity: Double? = null,
    val countedBy: String? = null,
    val countedAt: Long? = null,

    val isRequired: Boolean = true
) {

    val isCounted: Boolean
        get() = countedQuantity != null

    val variance: Double
        get() = countedQuantity
            ?.minus(expectedQuantity)
            ?: 0.0

    val shortageQuantity: Double
        get() = if (variance < 0.0) {
            -variance
        } else {
            0.0
        }

    val excessQuantity: Double
        get() = if (variance > 0.0) {
            variance
        } else {
            0.0
        }

    val primaryLocation: ProductLocation?
        get() = locations.firstOrNull()

    val aisle: String
        get() = primaryLocation
            ?.aisle
            .orEmpty()

    val rack: String
        get() = primaryLocation
            ?.rack
            .orEmpty()

    val shelf: String
        get() = primaryLocation
            ?.shelf
            .orEmpty()

    val locationId: String
        get() = primaryLocation
            ?.locationId
            .orEmpty()

    val locationDisplayName: String
        get() = primaryLocation
            ?.displayName
            .orEmpty()
}

data class StockTakeSession(
    val sessionId: String,

    val periodStartMillis: Long,
    val periodEndMillis: Long,

    val type: StockTakeType,
    val status: StockTakeStatus,

    val createdBy: String,

    val currentLocationId: String = "",
    val currentAisle: String = "",
    val currentRack: String = "",
    val currentShelf: String = "",

    val errorMessage: String? = null
) {
    val hasCurrentLocation: Boolean
        get() = currentLocationId.isNotBlank()

    val currentLocationDisplayName: String
        get() = listOf(
            currentAisle,
            currentRack,
            currentShelf
        )
            .filter { it.isNotBlank() }
            .joinToString(" › ")
}

data class StockHomeUiState(
    val loading: Boolean = false,
    val errorMessage: String? = null,

    val status: StockTakeStatus =
        StockTakeStatus.NOT_STARTED,

    val type: StockTakeType =
        StockTakeType.WEEKLY,

    /*
     * Overall stock-take progress.
     */
    val totalProducts: Int = 0,
    val countedProducts: Int = 0,
    val remainingProducts: Int = 0,
    val progressPercentage: Int = 0,

    /*
     * Current selected counting location.
     */
    val currentLocationId: String = "",
    val currentAisle: String = "",
    val currentRack: String = "",
    val currentShelf: String = "",

    /*
     * Progress at the current location.
     */
    val locationTotal: Int = 0,
    val locationCounted: Int = 0,
    val locationProgressPercentage: Int = 0,

    /*
     * Stock variance information.
     */
    val stockVarianceUnits: Double = 0.0,
    val mismatchedProducts: Int = 0,

    /*
     * Cash reconciliation information.
     *
     * Expected cash =
     * opening cash
     * + cash sales
     * - approved cash expenses
     */
    val expectedCash: Double = 0.0,

    val actualDrawerCash: Double? = null,

    /*
     * Cash variance =
     * actual drawer cash - expected cash
     */
    val cashVariance: Double? = null,

    val cashReconciled: Boolean = false
) {

    val hasProducts: Boolean
        get() = totalProducts > 0

    val hasCurrentLocation: Boolean
        get() = currentLocationId.isNotBlank()

    val stockTakeCompleted: Boolean
        get() = status == StockTakeStatus.COMPLETED

    val currentLocationDisplayName: String
        get() = listOf(
            currentAisle,
            currentRack,
            currentShelf
        )
            .filter { it.isNotBlank() }
            .joinToString(" › ")

    val cashStatusText: String
        get() =  when {
            !cashReconciled ||
                    cashVariance == null ->
                "Not reconciled"

            cashVariance < 0.0 ->
                "Cash short"

            cashVariance > 0.0 ->
                "Cash excess"

            else ->
                "Cash matchs"
        }

    val stockStatusText: String
        get() = when {
            countedProducts == 0 ->
                "No products counted yet"

            stockVarianceUnits < 0.0 ->
                "Stock shortage"

            stockVarianceUnits > 0.0 ->
                "Stock excess"

            else ->
                "Stock matches"
        }
}