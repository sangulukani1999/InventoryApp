package com.example.zed

import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.zed.databinding.PurchaseRequisitionItemBinding
import java.math.BigDecimal

/**
 * Data class to hold the state of each item in the RecyclerView.
 */
data class RequisitionItem(
    val product: Product,
    var quantity: Int = 0,
    var isChecked: Boolean = false,
    // A unique ID to find this item in the Google Sheet, combining product and a "default" unit.
    val uniqueSheetId: String
)

/**
 * Adapter for the Purchase Requisition screen.
 * It binds Product data to the purchase_requisition_item.xml layout.
 */
class PurchaseRequisitionAdapter(
    private val items: MutableList<RequisitionItem>,
    private val currentEmail: String,
    private val isAdmin: Boolean,
    // Callback to write the item's state to the Google Sheet when it changes.
    private val onItemChanged: (RequisitionItem) -> Unit,
    // Callback to update the total budget in the activity.
    private val onTotalChanged: () -> Unit
) : RecyclerView.Adapter<PurchaseRequisitionAdapter.RequisitionViewHolder>() {

    // 1. ADD PROPERTY TO TRACK HIGHLIGHTED ITEM
    var highlightedPosition = -1

    inner class RequisitionViewHolder(val binding: PurchaseRequisitionItemBinding) :
        RecyclerView.ViewHolder(binding.root) {

        // Remove previous listeners to prevent them from firing for the wrong item
        private val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val newQuantity = s?.toString()?.toIntOrNull() ?: 0
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val item = items[position]
                    if (item.quantity != newQuantity) {
                        item.quantity = newQuantity
                        updateIndividualTotal(item)
                        if (item.isChecked) { // Only sync if the item is selected
                            onItemChanged(item)
                            onTotalChanged()
                        }
                    }
                }
            }
        }

        fun bind(item: RequisitionItem) {
            // 2. ADD HIGHLIGHTING LOGIC
            if (adapterPosition == highlightedPosition) {
                // Set a highlight color (light grey) on the main CardView
                binding.mainCard.setCardBackgroundColor(Color.parseColor("#E0E0E0"))
            } else {
                // Reset to the default color (white)
                binding.mainCard.setCardBackgroundColor(Color.WHITE)
            }

            // Bind data to the views
            binding.productName.text = item.product.name
            binding.costValue.text = "$${item.product.unitCost}"
            binding.quantityInput.setText(item.quantity.toString())

            // Load image using Coil
            binding.productImage.load(item.product.imageUrl) {
                crossfade(true)
                placeholder(R.drawable.ic_launcher_background) // Replace with a placeholder
                error(R.drawable.ic_launcher_background)       // Replace with an error image
            }

            // Set initial state
            updateIndividualTotal(item)
            binding.itemCheckbox.isChecked = item.isChecked

            // Set permissions
            val canEdit = isAdmin
            binding.quantityInput.isEnabled = canEdit
            binding.itemCheckbox.isEnabled = canEdit

            // --- Listeners ---
            // Remove old listener before adding new one
            binding.quantityInput.removeTextChangedListener(textWatcher)
            binding.quantityInput.addTextChangedListener(textWatcher)

            binding.itemCheckbox.setOnCheckedChangeListener { _, isChecked ->
                // Ensure this only fires on user interaction
                if (binding.itemCheckbox.isPressed) {
                    item.isChecked = isChecked
                    onItemChanged(item)
                    onTotalChanged()
                }
            }
        }

        private fun updateIndividualTotal(item: RequisitionItem) {
            val cost = item.product.unitCost.toBigDecimalOrNull() ?: BigDecimal.ZERO
            val total = cost * item.quantity.toBigDecimal()
            binding.individualTotalValue.text = "$${"%.2f".format(total)}"
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RequisitionViewHolder {
        val binding = PurchaseRequisitionItemBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return RequisitionViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RequisitionViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    // Public method for the activity to calculate the total budget
    fun calculateTotalBudgetedAmount(): BigDecimal {
        return items.filter { it.isChecked && it.quantity > 0 }
            .sumOf {
                val cost = it.product.unitCost.toBigDecimalOrNull() ?: BigDecimal.ZERO
                cost * it.quantity.toBigDecimal()
            }
    }
}
