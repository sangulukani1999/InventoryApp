package com.example.zed

import android.app.DatePickerDialog
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.zed.databinding.DetailedGoodsReceivedNoteItedBinding // Binding class name updated
import java.math.BigDecimal
import java.util.*

// Data class to represent a single, mutable product row in the detailed view
data class DetailedGoodsReceivedProduct(
    val name: String,
    val unitCost: String,
    val imageUrl: String?,
    var quantity: Int,
    var expiryDate: String? = null,
    var isChecked: Boolean = false // To track the checkbox state
)

class DetailedGoodsReceivedAdapter(
    private val items: MutableList<DetailedGoodsReceivedProduct>
) : RecyclerView.Adapter<DetailedGoodsReceivedAdapter.ViewHolder>() {

    class ViewHolder(val binding: DetailedGoodsReceivedNoteItedBinding) : RecyclerView.ViewHolder(binding.root) {

        // TextWatcher to listen for quantity changes
        val quantityTextWatcher: TextWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // This will be configured in onBindViewHolder to access the item's position
            }
        }
    }

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

        // --- Remove old TextWatcher before adding a new one to prevent conflicts ---
        (holder.binding.quantityInput.tag as? TextWatcher)?.let {
            holder.binding.quantityInput.removeTextChangedListener(it)
        }

        // --- Bind the data to the views ---
        holder.binding.productName.text = item.name
        holder.binding.costValue.text = "K${item.unitCost}"
        holder.binding.quantityInput.setText(item.quantity.toString())
        holder.binding.expiryDate.setText(item.expiryDate ?: "Set Date")
        holder.binding.itemCheckbox.isChecked = item.isChecked

        // --- Load Product Image ---
        holder.binding.productImage.load(item.imageUrl) {
            crossfade(true)
            placeholder(R.drawable.ic_placeholder) // Make sure you have this drawable
            error(R.drawable.ic_placeholder)
        }

        // --- Calculate and Display Initial Total ---
        updateIndividualTotal(holder, item)

        // --- Set up Listeners ---

        // Checkbox listener
        holder.binding.itemCheckbox.setOnCheckedChangeListener { _, isChecked ->
            item.isChecked = isChecked
            // You can add logic here if something needs to happen immediately on check
        }

        // Expiry Date picker listener
        holder.binding.expiryDate.setOnClickListener {
            val calendar = Calendar.getInstance()
            val year = calendar.get(Calendar.YEAR)
            val month = calendar.get(Calendar.MONTH)
            val day = calendar.get(Calendar.DAY_OF_MONTH)

            DatePickerDialog(context, { _, selectedYear, selectedMonth, selectedDay ->
                val dateStr = "$selectedDay/${selectedMonth + 1}/$selectedYear"
                holder.binding.expiryDate.setText(dateStr)
                item.expiryDate = dateStr
            }, year, month, day).show()
        }

        // --- Quantity TextWatcher Logic ---
        val textWatcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val newQuantity = s?.toString()?.toIntOrNull() ?: 0
                if (item.quantity != newQuantity) {
                    item.quantity = newQuantity
                    updateIndividualTotal(holder, item)
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }

        holder.binding.quantityInput.addTextChangedListener(textWatcher)
        holder.binding.quantityInput.tag = textWatcher // Store watcher in tag to remove it later
    }

    /**
     * Helper function to calculate and update the total for a single item.
     */
    private fun updateIndividualTotal(holder: ViewHolder, item: DetailedGoodsReceivedProduct) {
        val cost = item.unitCost.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val total = cost * item.quantity.toBigDecimal()
        holder.binding.individualTotalValue.text = "K${"%.2f".format(total)}"
    }
}
