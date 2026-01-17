package com.example.zed

data class UserReportData(
    val userName: String,
    val userEmail: String,
    val outstandingLiability: Double,
    val variances: Double,
    val todayShortage: Double,
    val todayPaid: Double,
    val monthShortages: Double,
    val monthPaid: Double
)
