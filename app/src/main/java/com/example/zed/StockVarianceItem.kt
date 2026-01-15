package com.example.zed

import java.util.Date

// Data class to hold the information for one row in the variance report
data class StockVarianceItem(
    val timestamp: Date?,
    val productName: String,
    val countedQuantity: Int,
    val quantityVariance: Int,
    val unitCost: Double,
    val totalCost: Double,
    val countedBy: String,
    val sellingPrice: Double // We need this to calculate "Sales from variance"
)
