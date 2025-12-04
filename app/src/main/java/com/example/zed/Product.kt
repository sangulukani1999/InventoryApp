package com.example.zed

/**
 * Shared data classes for the entire application.
 */

data class Product(
    val id: String,
    val name: String,
    val imageUrl: String,
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
    val sellingPrice: String,  // ✅ This expects a non-nullable String
    val caseUnits: String,     // ✅ This expects a non-nullable String
    val quantityDescription: String, // ✅ This expects a non-nullable String
    val cost: String?,
    val updatedBy: String?,
    val timestamp: String?
)
