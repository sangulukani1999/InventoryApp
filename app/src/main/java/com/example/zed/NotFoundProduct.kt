package com.example.zed

/**
 * This file contains data classes used ONLY by the notFoundFragment and its adapter.
 * This prevents conflicts with the main 'Product' data class used elsewhere.
 */

data class NotFoundProduct(
    val id: String,
    val name: String,
    val imageUrl: String?,
    val barcode: String,
    val caseQty: String,
    val unitCost: String,
    val locationIds: List<String>,

    // These are lists of objects, not simple strings.
    // They are marked as 'var' so they can be populated after the object is first created.
    var units: List<UnitOfMeasure>,
    var locations: List<Location>
)

// The 'Location' and 'UnitOfMeasure' classes can be re-used if their structure
// in 'Product.kt' is compatible. If not, they would also be defined here.
// Based on your 'Product.kt', these structures are compatible.
