// In GoodsReceivedItem.kt

import java.util.Date

// Add the RequisitionStatus enum here or in goods_received_note.kt
enum class RequisitionStatus {
    NOT_RECEIVED,       // Red
    PARTIALLY_RECEIVED, // Yellow
    FULLY_RECEIVED      // Green
}

data class GoodsReceivedItem(
    val requisitionCode: String,
    val user: String,
    val timestamp: Date?,
    val itemCount: Int,
    val totalValue: Double,
    val firstProductName: String,
    val imageUrl: String?,
    val status: RequisitionStatus // ✅ ADD THIS LINE
)
