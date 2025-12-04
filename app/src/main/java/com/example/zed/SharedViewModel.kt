package com.example.zed

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class SharedViewModel : ViewModel() {
    private val _selectedProductBarcode = MutableLiveData<String>()
    val selectedProductBarcode: LiveData<String> = _selectedProductBarcode

    fun selectProduct(barcode: String) {
        _selectedProductBarcode.value = barcode
    }
}
