// In SharedViewModel.kt

package com.example.zed

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

// A new data class to hold all information for the selected product
data class SelectedProductData(
    val product: Product,
    val locations: List<Location>,
    val units: List<UnitOfMeasure>
)

class SharedViewModel : ViewModel() {

    // The LiveData now holds our new wrapper class
    private val _selectedProductData = MutableLiveData<SelectedProductData?>()
    val selectedProductData: LiveData<SelectedProductData?> = _selectedProductData

    /**
     * Called by stock_fragment to pass the complete data object.
     */
    fun selectProduct(data: SelectedProductData) {
        _selectedProductData.value = data
    }

    /**
     * Clears the selection.
     */
    fun clearSelection() {
        _selectedProductData.value = null
    }

    /**
     * Updates the details of the currently selected product in the LiveData stream.
     * This is designed to be called by TextWatchers in the UI fragments.
     */
    fun updateProductDetails(
        newName: String,
        newBarcode: String,
        newCaseQty: String,
        newMinOrder: String,
        newUnitCost: String
    ) {
        val currentData = _selectedProductData.value ?: return
        val updatedProduct = currentData.product.copy(
            name = newName,
            barcode = newBarcode,
            caseQty = newCaseQty,
            minOrder = newMinOrder,
            unitCost = newUnitCost
        )
        _selectedProductData.value = currentData.copy(product = updatedProduct)
    }

    // ✅ ADD THIS ENTIRE FUNCTION TO FIX THE ERROR
    /**
     * Updates just the image URL of the currently selected product.
     * This is called when a new image is captured or selected in the detailsStock fragment.
     */
    fun updateProductImage(newImageUrl: String) {
        val currentData = _selectedProductData.value ?: return

        // Create an updated product object with the new image URL using .copy()
        // We also store the old imageUrl in an unused field like 'unit' for later retrieval.
        // This is a temporary workaround. A better solution would be a dedicated field.
        val updatedProduct = currentData.product.copy(
            imageUrl = newImageUrl,
            // Temporarily store the old URL here for the save process
            unit = currentData.product.imageUrl ?: ""
        )

        // Post the new data back to the LiveData stream
        _selectedProductData.value = currentData.copy(product = updatedProduct)
    }
}
