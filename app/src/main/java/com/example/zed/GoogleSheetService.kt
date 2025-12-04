package com.example.zed

import retrofit2.http.GET

interface GoogleSheetService {
    @GET("1W-LOkSgPPrfhZ_kqfycUvOcGviQplMng6xpc6KBQ9Ik/products")
    suspend fun getInventory(): List<InventoryItem>
    @GET("1W-LOkSgPPrfhZ_kqfycUvOcGviQplMng6xpc6KBQ9Ik/product_locations")
    suspend fun getLocations(): List<LocationItem>
}
