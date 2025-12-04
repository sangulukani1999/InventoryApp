package com.example.zed

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.fragment.app.Fragment

class info : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_info, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // --- Reference Spinner ---
        val referenceSpinner: Spinner = view.findViewById(R.id.refence_spinner_1)
        val referenceItems = listOf("Option 1", "Option 2", "Option 3")

        val referenceAdapter = ArrayAdapter(
            requireContext(),            // context from Fragment
            R.layout.spinner_item,       // custom layout for spinner items
            referenceItems
        )
        referenceAdapter.setDropDownViewResource(R.layout.spinner_item)
        referenceSpinner.adapter = referenceAdapter

        // --- Select Shop Spinner ---
        val selectShopSpinner: Spinner = view.findViewById(R.id.select_shop_spinner)
        val shopItems = listOf("Shop 1", "Shop 2", "Shop 3")

        val shopAdapter = ArrayAdapter(
            requireContext(),
            R.layout.spinner_item,
            shopItems
        )
        shopAdapter.setDropDownViewResource(R.layout.spinner_item)
        selectShopSpinner.adapter = shopAdapter
    }

}
