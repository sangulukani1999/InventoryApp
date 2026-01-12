// In DetailedGoodsReceivedProduct.kt
data class DetailedGoodsReceivedProduct(
    val barcode: String,
    val name: String,
    var unitCost: String,
    val imageUrl: String?,
    var quantity: Int,
    var expiryDate: String? = null,
    var isChecked: Boolean = true,
    var isPurchased: Boolean = false,
    var isReceived: Boolean = false // ✅ ADD THIS NEW FLAG
)
