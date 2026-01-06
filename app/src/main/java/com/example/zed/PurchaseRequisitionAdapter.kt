package com.example.zed

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.zed.databinding.PurchaseRequisitionItemBinding
import java.math.BigDecimal

// Data class to hold all necessary info for an item
data class RequisitionItem(
    val product: Product,
    var quantity: Int = 0,
    var isChecked: Boolean = false
)

class PurchaseRequisitionAdapter(
    private val items: MutableList<RequisitionItem>,
    private val currentEmail: String,
    private val isAdmin: Boolean,
    private val onTotalChanged: () -> Unit // Callback to update total in Activity
) : RecyclerView.Adapter<PurchaseRequisitionAdapter.RequisitionViewHolder>() {

    inner class RequisitionViewHolder(val binding: PurchaseRequisitionItemBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: RequisitionItem) {
            // Bind data to the views from your new XML
            binding.productName.text = item.product.name
            binding.costValue.text = "$${item.product.unitCost}"
            binding.quantityInput.setText(item.quantity.toString())

            // Load image using Coil (make sure Coil dependency is in your build.gradle)
            binding.productImage.load(item.product.imageUrl) {
                crossfade(true)
                placeholder(R.drawable.ic_launcher_background) // Optional: replace with a real placeholder
                error(R.drawable.ic_launcher_background)       // Optional: replace with a real error image
            }


            // Set initial state
            updateIndividualTotal(item)
            binding.itemCheckbox.isChecked = item.isChecked

            // Simplified permission logic: only admin can edit. Adjust if needed.
            val canEdit = isAdmin
            binding.quantityInput.isEnabled = canEdit
            binding.itemCheckbox.isEnabled = canEdit

            // --- Listeners ---
            binding.itemCheckbox.setOnCheckedChangeListener { _, isChecked ->
                // Only trigger on direct user interaction to prevent loops
                if (binding.itemCheckbox.isPressed) {
                    item.isChecked = isChecked
                    onTotalChanged()
                }
            }

            binding.quantityInput.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val newQuantity = s?.toString()?.toIntOrNull() ?: 0
                    if (item.quantity != newQuantity) {
                        item.quantity = newQuantity
                        updateIndividualTotal(item)
                        // Also update the grand total if the quantity changes
                        onTotalChanged()
                    }
                }
            })
        }

        private fun updateIndividualTotal(item: RequisitionItem) {
            val cost = item.product.unitCost.toBigDecimalOrNull() ?: BigDecimal.ZERO
            val total = cost * item.quantity.toBigDecimal()
            // Bind the calculated total to the 'individualTotalValue' TextView
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

    // Public method for the activity to calculate the total
    fun calculateTotalBudgetedAmount(): BigDecimal {
        return items.filter { it.isChecked }
            .sumOf {
                val cost = it.product.unitCost.toBigDecimalOrNull() ?: BigDecimal.ZERO
                cost * it.quantity.toBigDecimal()
            }
    }
}
