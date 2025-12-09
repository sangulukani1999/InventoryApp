package com.example.zed

/**
 * Shared data classes for the entire application.
 */

data class Product(
    val id: String,
    val name: String,
    val imageUrl: String?, // ✅ FIX: Change String to String?
    val barcode: String,
    val categoryId: String,
    val unit: String,
    val caseQty: String,
    val minOrder: String,
    val unitCost: String,
    val locationIds: List<String>
)

data class Location(
    val id: String,
    val aisle: String,
    val rack: String,
    val shelf: String
)

// In your Models.kt or similar file
data class UnitOfMeasure(
    val productId: String?,
    val unitBarcode: String?,
    val sellingPrice: String,
    val caseUnits: String,
    val quantityDescription: String,
    val cost: String?,
    val updatedBy: String?,
    val timestamp: String?
)
