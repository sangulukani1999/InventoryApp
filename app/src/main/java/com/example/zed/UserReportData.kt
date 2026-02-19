package com.example.zed

data class UserReportData(
    val userEmail: String,
    val userName: String,
    // Financial Data
    val outstandingLiability: Double,
    val variances: Double,
    val todayShortage: Double,
    val monthShortages: Double,
    val monthPaid: Double,
    val monthPositiveVariances: Double,
    // Working Status Data
    val veryGoodPercentage: Int,
    val goodPercentage: Int,
    val badPercentage: Int,
    // Other Data
    val transactionTotalPages: Int?
)
