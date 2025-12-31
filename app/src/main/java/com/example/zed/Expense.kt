package com.example.zed

data class Expense(
    val uniqueId: String = "", // Unique ID for finding the row in Sheets
    val item: String = "",
    val description: String = "",
    val quantity: Double = 0.0,
    val amount: Double = 0.0,
    val permit: Boolean = false,
    val user: String = "", // Email of the user who added it
    val timestamp: String,
    val approvalTimestamp: String

)
