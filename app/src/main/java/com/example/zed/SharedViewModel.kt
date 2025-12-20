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
}
