package com.example.zed

import DetailedGoodsReceivedProduct
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.zed.databinding.DetailedGoodsReceivedNoteItedBinding
import java.math.BigDecimal
import java.util.*

class DetailedGoodsReceivedAdapter(
    private val items: MutableList<DetailedGoodsReceivedProduct>,
    private val onStateChanged: () -> Unit,
    private val onConfirmReceive: (item: DetailedGoodsReceivedProduct, position: Int) -> Unit
) : RecyclerView.Adapter<DetailedGoodsReceivedAdapter.ViewHolder>() {

    // This property is no longer the primary lock, but can be used for other UI logic if needed.
    var isPurchaseComplete: Boolean = false

    class ViewHolder(val binding: DetailedGoodsReceivedNoteItedBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = DetailedGoodsReceivedNoteItedBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context

        // Remove old listeners to prevent conflicts
        (holder.binding.quantityInput.tag as? TextWatcher)?.let {
            holder.binding.quantityInput.removeTextChangedListener(it)
        }
        (holder.binding.costPrice.tag as? TextWatcher)?.let {
            holder.binding.costPrice.removeTextChangedListener(it)
        }
        holder.binding.expiryDate.onFocusChangeListener = null
        holder.binding.expiryDate.setOnClickListener(null)


        // Bind data
        holder.binding.productName.text = item.name
        holder.binding.costValue.text = "K${item.unitCost}"
        holder.binding.quantityInput.setText(item.quantity.toString())
        holder.binding.expiryDate.setText(item.expiryDate ?: "Set Date")
        holder.binding.itemCheckbox.isChecked = item.isChecked
        holder.binding.costPrice.setText(item.unitCost)

        holder.binding.productImage.load(item.imageUrl) {
            crossfade(true)
            placeholder(R.drawable.ic_placeholder)
            error(R.drawable.ic_placeholder)
        }
        updateIndividualTotal(holder, item)

        // Set indicator colors
        val purchasedIndicatorColor = if (item.isPurchased) R.color.variance_positive else R.color.variance_negative
        holder.binding.goodPurchasedIndicator.setCardBackgroundColor(ContextCompat.getColor(context, purchasedIndicatorColor))

        val receivedIndicatorColor = if (item.isReceived) R.color.variance_positive else R.color.variance_negative
        holder.binding.goodsReceivedIndicator.setCardBackgroundColor(ContextCompat.getColor(context, receivedIndicatorColor))


        // ✅ --- NEW, CORRECTED UI LOGIC ---

        // Rule: The quantity and cost fields should be locked if the purchase is complete.
        val purchaseIsLocked = isPurchaseComplete
        holder.binding.costPrice.isEnabled = !purchaseIsLocked
        holder.binding.quantityInput.isEnabled = !purchaseIsLocked
        holder.binding.itemCheckbox.isEnabled = !purchaseIsLocked
        holder.binding.costPrice.alpha = if (purchaseIsLocked) 0.5f else 1.0f
        holder.binding.quantityInput.alpha = if (purchaseIsLocked) 0.5f else 1.0f
        holder.binding.itemCheckbox.alpha = if (purchaseIsLocked) 0.5f else 1.0f

        // Rule: The date field has its own specific logic.
        // It should be enabled ONLY if the item has been purchased but NOT yet received.
        val dateIsEditable = item.isPurchased && !item.isReceived
        holder.binding.expiryDate.isEnabled = dateIsEditable
        holder.binding.expiryDate.alpha = if (dateIsEditable) 1.0f else 0.5f


        // --- LISTENERS (No changes here) ---

        // Click listener to show the DatePickerDialog
        holder.binding.expiryDate.setOnClickListener {
            // This check is still valid, as it only works if the field is enabled.
            if (holder.binding.expiryDate.isEnabled) {
                val calendar = Calendar.getInstance()
                val datePickerDialog = DatePickerDialog(context, { _, year, month, day ->
                    val dateStr = String.format(Locale.getDefault(), "%02d/%02d/%d", day, month + 1, year)
                    holder.binding.expiryDate.setText(dateStr)
                    holder.binding.quantityInput.requestFocus() // Trigger focus loss
                }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH))

                datePickerDialog.show()
            }
        }

        // Focus change listener to trigger the confirmation dialog
        holder.binding.expiryDate.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            val currentDateText = holder.binding.expiryDate.text.toString()
            // This logic is now perfect, as it will only trigger if the date was editable.
            if (!hasFocus && dateIsEditable && currentDateText != "Set Date" && currentDateText != item.expiryDate) {
                AlertDialog.Builder(context)
                    .setTitle("Confirm Expiry Date")
                    .setMessage("Are you sure? This will update stock and cannot be undone.")
                    .setPositiveButton("Yes, Confirm") { _, _ ->
                        item.expiryDate = currentDateText
                        onConfirmReceive(item, position)
                    }
                    .setNegativeButton("No") { dialog, _ ->
                        holder.binding.expiryDate.setText(item.expiryDate ?: "Set Date") // Revert
                        dialog.dismiss()
                    }
                    .setCancelable(false)
                    .show()
            }
        }

        // Other listeners remain unchanged
        holder.binding.itemCheckbox.setOnCheckedChangeListener { _, isChecked ->
            item.isChecked = isChecked
            onStateChanged()
        }

        // TextWatchers are complex, better to leave them be if not causing issues.
        // If you need to add logic here, it would be to update totals.
        val quantityTextWatcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                item.quantity = s.toString().toIntOrNull() ?: 0
                updateIndividualTotal(holder, item)
                onStateChanged()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }
        holder.binding.quantityInput.addTextChangedListener(quantityTextWatcher)
        holder.binding.quantityInput.tag = quantityTextWatcher

        val costPriceTextWatcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                item.unitCost = s.toString()
                updateIndividualTotal(holder, item)
                onStateChanged()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }
        holder.binding.costPrice.addTextChangedListener(costPriceTextWatcher)
        holder.binding.costPrice.tag = costPriceTextWatcher
    }

    private fun updateIndividualTotal(holder: ViewHolder, item: DetailedGoodsReceivedProduct) {
        val cost = item.unitCost.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val total = cost * item.quantity.toBigDecimal()
        holder.binding.individualTotalValue.text = "K${"%.2f".format(total)}"
    }
}
