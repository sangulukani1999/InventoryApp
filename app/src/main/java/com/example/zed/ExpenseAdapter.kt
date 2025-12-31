package com.example.zed

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.example.zed.databinding.ExpensesItemBinding
import java.util.Locale

class ExpenseAdapter(
    private val expenses: List<Expense>,
    private val currentEmail: String,
    private val isAdmin: Boolean,
    private val onDeleteClick: (Expense) -> Unit
) : RecyclerView.Adapter<ExpenseAdapter.ExpenseViewHolder>() {

    inner class ExpenseViewHolder(private val binding: ExpensesItemBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(expense: Expense) {
            binding.itemExpense.text = expense.item
            binding.descriptionExpense.text = if (expense.description.isNotBlank()) expense.description else "null"

            // Set quantity
            binding.qtyExpense.text = String.format(Locale.US, "%.2f", expense.quantity)

            // ✅ FIX 1: Set the amount to its dedicated TextView
            binding.amountExpense.text = String.format(Locale.US, "%.2f", expense.amount)

            // ✅ FIX 2: Set the background of the View, not its text
            if (expense.permit) {
                binding.perminExpense.setBackgroundResource(R.drawable.circle_background_green)
            } else {
                binding.perminExpense.setBackgroundResource(R.drawable.circle_background_red)
            }

            // --- PERMISSION LOGIC ---
            binding.deleteExpenseEntry.visibility = View.VISIBLE
            binding.deleteExpenseEntry.setOnClickListener {
                if (isAdmin || expense.user == currentEmail) {
                    onDeleteClick(expense)
                } else {
                    Toast.makeText(
                        binding.root.context,
                        "You do not have permission to delete this entry.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ExpenseViewHolder {
        val binding = ExpensesItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ExpenseViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ExpenseViewHolder, position: Int) {
        holder.bind(expenses[position])
    }

    override fun getItemCount(): Int = expenses.size
}
