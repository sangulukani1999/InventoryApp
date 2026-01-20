package com.example.zed

data class UserReportData(
    val userName: String,
    val userEmail: String,
    val outstandingLiability: Double,
    val variances: Double, // This will hold the NET All-Time Stock Liability
    val todayShortage: Double,
    val monthShortages: Double, // This will hold the NET Monthly Liability
    val monthPaid: Double,
    // ✅ ADD THIS FIELD BACK to hold the monthly surplus value
    val monthPositiveVariances: Double
)
