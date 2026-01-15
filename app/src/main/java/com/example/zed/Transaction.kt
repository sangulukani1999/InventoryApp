// In app/src/main/java/com/example/zed/Transaction.kt

package com.example.zed

import java.util.Date

// Represents a single product within a transaction
data class TransactionItem(
    val id: String,
    val productName: String,
    val price: Double,
    val quantity: Int,
    var costPrice: Double = 0.0
)

// Represents a full transaction record
data class Transaction(
    val transactionId: String,
    val items: List<TransactionItem>,
    val timestamp: Date?,
    val paymentMethod: String,
    val totalAmount: Double,
    val user: String
)
