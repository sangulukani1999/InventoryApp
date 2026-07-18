package com.example.zed

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.zed.databinding.ItemCashExpenseBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CashExpenseAdapter :
    ListAdapter<
            CashExpenseItem,
            CashExpenseAdapter.ExpenseViewHolder
            >(ExpenseDiffCallback()) {

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): ExpenseViewHolder {

        val binding =
            ItemCashExpenseBinding.inflate(
                LayoutInflater.from(
                    parent.context
                ),
                parent,
                false
            )

        return ExpenseViewHolder(
            binding
        )
    }

    override fun onBindViewHolder(
        holder: ExpenseViewHolder,
        position: Int
    ) {
        holder.bind(
            getItem(position)
        )
    }

    class ExpenseViewHolder(
        private val binding:
        ItemCashExpenseBinding
    ) : RecyclerView.ViewHolder(
        binding.root
    ) {

        fun bind(
            item: CashExpenseItem
        ) {
            binding.expenseItemText.text =
                item.item.ifBlank {
                    "Expense"
                }

            binding.expenseDescriptionText.text =
                item.description.ifBlank {
                    "No description"
                }

            val user =
                item.user.ifBlank {
                    "Unknown user"
                }

            val date =
                item.timestamp?.let {
                    formatDate(it)
                } ?: "Unknown date"

            binding.expenseMetaText.text =
                "${item.paymentMethod} • " +
                        "$user • $date"

            binding.expenseAmountText.text =
                "-K${formatMoney(item.amount)}"
        }

        private fun formatMoney(
            amount: Double
        ): String {
            return String.format(
                Locale.getDefault(),
                "%,.2f",
                amount
            )
        }

        private fun formatDate(
            millis: Long
        ): String {
            return SimpleDateFormat(
                "dd MMM yyyy HH:mm",
                Locale.getDefault()
            ).format(
                Date(millis)
            )
        }
    }

    private class ExpenseDiffCallback :
        DiffUtil.ItemCallback<CashExpenseItem>() {

        override fun areItemsTheSame(
            oldItem: CashExpenseItem,
            newItem: CashExpenseItem
        ): Boolean {
            return if (
                oldItem.uniqueId.isNotBlank() &&
                newItem.uniqueId.isNotBlank()
            ) {
                oldItem.uniqueId ==
                        newItem.uniqueId
            } else {
                oldItem.item ==
                        newItem.item &&
                        oldItem.timestamp ==
                        newItem.timestamp &&
                        oldItem.amount ==
                        newItem.amount
            }
        }

        override fun areContentsTheSame(
            oldItem: CashExpenseItem,
            newItem: CashExpenseItem
        ): Boolean {
            return oldItem ==
                    newItem
        }
    }
}