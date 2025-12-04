package com.example.zed

import com.google.gson.annotations.SerializedName

data class LocationItem(
    val id: String?, // e.g., 'LOC-8D1AC31B'
    val barcode: String?,
    val aisle: String?,
    val rack: String?,
    val shelf: String?
)

