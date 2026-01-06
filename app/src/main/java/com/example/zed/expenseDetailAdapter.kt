package com.example.zed

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.zed.databinding.ExpenseDetailItemBinding
import java.util.Locale

class expenseDetailAdapter(
    private val expenses: List<Expense>,
    private val currentEmail: String,
    private val isAdmin: Boolean,
    private val onDeleteClick: (Expense) -> Unit,
    private val onPermitStatusChange: (expense: Expense, newStatus: Boolean) -> Unit
) : RecyclerView.Adapter<expenseDetailAdapter.ExpenseViewHolder>() {

    inner class ExpenseViewHolder(private val binding: ExpenseDetailItemBinding) : RecyclerView.ViewHolder(binding.root) {
        private val topBorder: View = binding.root.findViewById(R.id.top_border_frame)

        fun bind(expense: Expense) {
            // --- Set existing text data ---
            binding.itemExpense.text = expense.item
            binding.descriptionExpense.text = if (expense.description.isNotBlank()) expense.description else "null"
            binding.qtyExpense.text = String.format(Locale.US, "%.2f", expense.quantity)
            binding.individualTotalValue.text = String.format(Locale.US, "%.2f", expense.amount)

            // SET INITIAL BORDER COLOR BASED ON PERMIT STATUS
            if (expense.permit) {
                topBorder.setBackgroundColor(ContextCompat.getColor(binding.root.context, android.R.color.holo_green_dark))
            } else {
                topBorder.setBackgroundColor(ContextCompat.getColor(binding.root.context, android.R.color.holo_red_dark))
            }

            // --- PERMISSION & VISIBILITY LOGIC ---
            if (isAdmin) {
                binding.agreePermit.visibility = View.VISIBLE
                binding.desagreePermit.visibility = View.VISIBLE
            } else {
                binding.agreePermit.visibility = View.GONE
                binding.desagreePermit.visibility = View.GONE
            }

            // ✅ --- FIX FOR INVISIBLE VIEWS --- ✅
            // Set the drawable backgrounds for the agree/disagree views to make them visible.
            // Assumes you have 'circle_background_green.xml' and 'circle_background_red.xml' in your res/drawable folder.
            binding.agreePermit.setBackgroundResource(R.drawable.circle_background_green)
            binding.desagreePermit.setBackgroundResource(R.drawable.circle_background_red)


            // Show delete icon only for admins or the item's creator
            binding.deleteExpenseEntry.visibility = if (isAdmin || expense.user == currentEmail) View.VISIBLE else View.GONE
            binding.deleteExpenseEntry.setOnClickListener { onDeleteClick(expense) }

            // ADD CLICK LISTENERS FOR AGREE/DISAGREE ICONS
            binding.agreePermit.setOnClickListener {
                if(isAdmin) {
                    onPermitStatusChange(expense, true)
                }
            }

            binding.desagreePermit.setOnClickListener {
                if(isAdmin) {
                    onPermitStatusChange(expense, false)
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ExpenseViewHolder {
        val binding = ExpenseDetailItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ExpenseViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ExpenseViewHolder, position: Int) {
        holder.bind(expenses[position])
    }

    override fun getItemCount(): Int = expenses.size
}
