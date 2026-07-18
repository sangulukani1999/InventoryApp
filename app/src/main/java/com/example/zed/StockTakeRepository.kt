package com.example.zed

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object StockTakeRepository {

    private val _products =
        MutableStateFlow<List<StockTakeProduct>>(emptyList())

    val products: StateFlow<List<StockTakeProduct>> =
        _products.asStateFlow()

    private val _session =
        MutableStateFlow<StockTakeSession?>(null)

    val session: StateFlow<StockTakeSession?> =
        _session.asStateFlow()

    fun setProducts(products: List<StockTakeProduct>) {
        _products.value = products
    }

    fun setSession(session: StockTakeSession?) {
        _session.value = session
    }

    fun clear() {
        _products.value = emptyList()
        _session.value = null
    }

    fun saveCountLocally(
        barcode: String,
        countedQuantity: Double,
        countedBy: String,
        countedAt: Long = System.currentTimeMillis()
    ): Boolean {
        val cleanBarcode = normalizeBarcode(barcode)
        var found = false

        _products.value = _products.value.map { product ->
            if (normalizeBarcode(product.barcode) == cleanBarcode) {
                found = true

                product.copy(
                    countedQuantity = countedQuantity,
                    countedBy = countedBy,
                    countedAt = countedAt
                )
            } else {
                product
            }
        }

        return found
    }

    fun updateCurrentLocation(location: ProductLocation) {
        val currentSession = _session.value ?: return

        _session.value = currentSession.copy(
            currentLocationId = location.locationId,
            currentAisle = location.aisle,
            currentRack = location.rack,
            currentShelf = location.shelf
        )
    }

    fun getAllLocations(): List<ProductLocation> {
        return _products.value
            .flatMap { it.locations }
            .distinctBy { normalizeLocationId(it.locationId) }
            .sortedWith(
                compareBy<ProductLocation>(
                    { it.aisle.lowercase() },
                    { it.rack.lowercase() },
                    { naturalShelfNumber(it.shelf) },
                    { it.shelf.lowercase() }
                )
            )
    }

    fun productsAtCurrentLocation(): List<StockTakeProduct> {
        val currentSession = _session.value ?: return emptyList()
        val locationId = normalizeLocationId(currentSession.currentLocationId)

        if (locationId.isBlank()) {
            return emptyList()
        }

        return _products.value
            .filter { product ->
                product.locations.any {
                    normalizeLocationId(it.locationId) == locationId
                }
            }
            .sortedBy { it.productName.lowercase() }
    }

    fun productsAtLocation(
        locationId: String
    ): List<StockTakeProduct> {
        val normalizedId = normalizeLocationId(locationId)

        return _products.value
            .filter { product ->
                product.locations.any {
                    normalizeLocationId(it.locationId) == normalizedId
                }
            }
            .sortedBy { it.productName.lowercase() }
    }

    fun countedProducts(): List<StockTakeProduct> {
        return _products.value.filter { it.isCounted }
    }

    fun remainingProducts(): List<StockTakeProduct> {
        return _products.value.filter { !it.isCounted }
    }

    fun mismatchedProducts(): List<StockTakeProduct> {
        return _products.value.filter {
            it.isCounted && it.variance != 0.0
        }
    }

    fun findProduct(barcode: String): StockTakeProduct? {
        val normalized = normalizeBarcode(barcode)

        return _products.value.firstOrNull {
            normalizeBarcode(it.barcode) == normalized
        }
    }

    fun nextRemainingProductAtCurrentLocation(): StockTakeProduct? {
        return productsAtCurrentLocation()
            .firstOrNull { !it.isCounted }
    }

    fun moveToNextIncompleteLocation(): ProductLocation? {
        val allLocations = getAllLocations()
        val currentSession = _session.value

        val currentIndex = allLocations.indexOfFirst {
            normalizeLocationId(it.locationId) ==
                    normalizeLocationId(
                        currentSession?.currentLocationId.orEmpty()
                    )
        }

        val orderedSearch = if (currentIndex >= 0) {
            allLocations.drop(currentIndex + 1) +
                    allLocations.take(currentIndex + 1)
        } else {
            allLocations
        }

        val nextLocation = orderedSearch.firstOrNull { location ->
            productsAtLocation(location.locationId)
                .any { !it.isCounted }
        }

        if (nextLocation != null) {
            updateCurrentLocation(nextLocation)
        }

        return nextLocation
    }

    private fun normalizeBarcode(value: String): String {
        return value
            .trim()
            .replace(" ", "")
    }

    private fun normalizeLocationId(value: String): String {
        return value.trim().uppercase()
    }

    private fun naturalShelfNumber(value: String): Int {
        return Regex("\\d+")
            .find(value)
            ?.value
            ?.toIntOrNull()
            ?: Int.MAX_VALUE
    }
}