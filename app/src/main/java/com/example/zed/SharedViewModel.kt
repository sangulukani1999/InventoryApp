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

    // ✅ ADD THIS ENTIRE FUNCTION
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
        // Get the current data from the LiveData, or exit if nothing is selected.
        val currentData = _selectedProductData.value ?: return

        // Create a new, updated Product object using the .copy() method.
        // This is important for LiveData to recognize the change.
        val updatedProduct = currentData.product.copy(
            name = newName,
            barcode = newBarcode,
            caseQty = newCaseQty,
            minOrder = newMinOrder,
            unitCost = newUnitCost
        )

        // Post the new data (with the updated product) back to the LiveData stream.
        // This will notify all observers of the change.
        _selectedProductData.value = currentData.copy(product = updatedProduct)
    }
}
