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

// (RequisitionItem data class remains the same)
data class RequisitionItem(
    val product: Product,
    var quantity: Int = 0,
    var isChecked: Boolean = false,
    val uniqueSheetId: String
)

class PurchaseRequisitionAdapter(
    private val items: MutableList<RequisitionItem>,
    private val currentEmail: String,
    private val isAdmin: Boolean, // This parameter is no longer used for editing logic
    private val onItemChanged: (RequisitionItem) -> Unit,
    private val onTotalChanged: () -> Unit
) : RecyclerView.Adapter<PurchaseRequisitionAdapter.RequisitionViewHolder>() {

    var highlightedPosition = -1

    inner class RequisitionViewHolder(val binding: PurchaseRequisitionItemBinding) :
        RecyclerView.ViewHolder(binding.root) {

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
                        // Only trigger updates if the item is actually checked
                        if (item.isChecked) {
                            onItemChanged(item)
                            onTotalChanged()
                        }
                    }
                }
            }
        }

        fun bind(item: RequisitionItem) {
            // Highlighting logic
            if (adapterPosition == highlightedPosition) {
                binding.mainCard.setCardBackgroundColor(Color.parseColor("#E0E0E0"))
            } else {
                binding.mainCard.setCardBackgroundColor(Color.WHITE)
            }

            // --- Data Binding ---
            binding.productName.text = item.product.name
            binding.costValue.text = "K${item.product.unitCost}"

            // Use removeTextChangedListener before setting text to avoid triggering the watcher
            binding.quantityInput.removeTextChangedListener(textWatcher)
            binding.quantityInput.setText(if (item.quantity > 0) item.quantity.toString() else "")
            binding.quantityInput.addTextChangedListener(textWatcher)


            binding.productImage.load(item.product.imageUrl) {
                crossfade(true)
                placeholder(R.drawable.ic_placeholder)
                error(R.drawable.ic_placeholder)
            }

            updateIndividualTotal(item)
            binding.itemCheckbox.isChecked = item.isChecked

            // ✅ THE FIX: Editing is now always enabled for every user.
            // The `isAdmin` flag passed to the adapter is ignored for this logic.
            val canEdit = true
            binding.quantityInput.isEnabled = canEdit
            binding.itemCheckbox.isEnabled = canEdit
            binding.quantityInput.alpha = 1.0f // Ensure full opacity
            binding.itemCheckbox.alpha = 1.0f  // Ensure full opacity

            // --- Listeners ---
            binding.itemCheckbox.setOnCheckedChangeListener { _, isChecked ->
                // Check if the change was user-initiated to prevent loops
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
            binding.individualTotalValue.text = "K${"%.2f".format(total)}"
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

    fun clearAllSelections() {
        items.forEach {
            it.isChecked = false
            it.quantity = 0 // Also reset quantity on clear
        }
        notifyDataSetChanged()
        onTotalChanged()
    }

    fun calculateTotalBudgetedAmount(): BigDecimal {
        return items.filter { it.isChecked && it.quantity > 0 }
            .sumOf {
                val cost = it.product.unitCost.toBigDecimalOrNull() ?: BigDecimal.ZERO
                cost * it.quantity.toBigDecimal()
            }
    }
}
