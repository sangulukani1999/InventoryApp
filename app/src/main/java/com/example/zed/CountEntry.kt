package com.example.zed

data class CountEntry(
    val productName: String,
    val locationId: String, // Stores 'LOC-8D1AC31B'
    val aisle: String, // Still useful for display purposes
    val rack: String,  // Still useful for display purposes
    val shelf: String, // Still useful for display purposes
    val quantity: Int,
    val user: String?
)

