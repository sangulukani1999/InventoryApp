package com.example.zed

import java.util.Date

// Holds raw data fetched from a single row in a sheet
data class SheetRow(
    val timestamp: Date?,
    val value: Double
)

// Holds all data fetched from Google Sheets for balancing
data class BalancingData(
    val sales: List<SheetRow>,
    val openingBalances: List<SheetRow>,
    val investments: List<SheetRow>,
    val expenses: List<SheetRow>,
    val availableCash: List<SheetRow>,
    val inventoryCost: Double,
    val salesWithCost: List<Pair<SheetRow, Double>> // Pair of (Sale, Cost)
)
