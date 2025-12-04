package com.example.zed

import com.google.gson.annotations.SerializedName

data class InventoryItem(
    @SerializedName("Product Name") val ProductName: String?,
    @SerializedName("price") val price: String?,
    @SerializedName("Images") val Image: String?,
    @SerializedName("Barcode ") val Barcode: String?,
    @SerializedName("Aisle ") val Aisle: String?,
    @SerializedName("Rack") val Rack: String?,
    @SerializedName("System Quantuty") val Quantity: String?

)
