// In app/src/main/java/com/example/zed/TransactionsAdapter.kt

package com.example.zed

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.zed.databinding.TransactionItemBinding
import java.text.SimpleDateFormat
import java.util.Locale

class TransactionsAdapter(
    private var transactions: List<Transaction>
) : RecyclerView.Adapter<TransactionsAdapter.TransactionViewHolder>() {

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

    // To keep track of which item is expanded
    private var expandedPosition = -1

    inner class TransactionViewHolder(val binding: TransactionItemBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(transaction: Transaction, isExpanded: Boolean) {
            // Bind main transaction details
            binding.transactionId.text = transaction.transactionId
            binding.transactionTotal.text = "K${"%.2f".format(transaction.totalAmount)}"
            binding.paymentMethod.text = transaction.paymentMethod
            binding.soldBy.text = transaction.user.substringBefore('@') // Show user name before @

            transaction.timestamp?.let {
                binding.transactionTime.text = timeFormat.format(it)
                binding.transactionDate.text = dateFormat.format(it)
            } ?: run {
                binding.transactionTime.text = "N/A"
                binding.transactionDate.text = "N/A"
            }

            // Handle the expanded view for transaction items
            binding.itemsRecyclerView.visibility = if (isExpanded) View.VISIBLE else View.GONE
            if (isExpanded) {
                // Build a simple string to list all items
                val itemsDetails = transaction.items.joinToString("\n") { item ->
                    "${item.quantity} x ${item.productName} @ K${"%.2f".format(item.price)}"
                }
                binding.itemsDetailsText.text = itemsDetails
            }

            // Set the click listener to expand/collapse
            itemView.setOnClickListener {
                val position = adapterPosition
                // If the clicked item is already expanded, collapse it. Otherwise, expand the new one.
                expandedPosition = if (isExpanded) -1 else position
                // Notify the adapter about the change to redraw the items
                notifyItemChanged(position)
                if (expandedPosition != -1 && expandedPosition != position) {
                    notifyItemChanged(expandedPosition) // Also update previously expanded item
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TransactionViewHolder {
        val binding = TransactionItemBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return TransactionViewHolder(binding)
    }

    override fun getItemCount(): Int = transactions.size

    override fun onBindViewHolder(holder: TransactionViewHolder, position: Int) {
        holder.bind(transactions[position], position == expandedPosition)
    }

    fun updateData(newTransactions: List<Transaction>) {
        this.transactions = newTransactions
        notifyDataSetChanged()
    }
}
