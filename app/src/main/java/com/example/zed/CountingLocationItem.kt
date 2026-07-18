package com.example.zed

enum class LocationSelectionLevel {
    AISLE,
    RACK,
    SHELF
}

enum class LocationCountStatus {
    NOT_STARTED,
    IN_PROGRESS,
    COMPLETED
}

data class CountingLocationItem(
    val id: String,
    val name: String,
    val level: LocationSelectionLevel,

    val totalProducts: Int,
    val countedProducts: Int,
    val remainingProducts: Int,

    val location: ProductLocation? = null
) {
    val status: LocationCountStatus
        get() = when {
            totalProducts > 0 &&
                    countedProducts >= totalProducts ->
                LocationCountStatus.COMPLETED

            countedProducts > 0 ->
                LocationCountStatus.IN_PROGRESS

            else ->
                LocationCountStatus.NOT_STARTED
        }
}