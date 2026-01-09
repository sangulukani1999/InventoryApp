// In a file like app/src/main/java/com/example/zed/GoodsReceivedItem.kt
import java.util.Date

data class GoodsReceivedItem(
    val requisitionCode: String,
    val firstProductName: String, // The missing parameter
    val user: String,
    val timestamp: Date?,
    val itemCount: Int,
    val totalValue: Double
)
