package com.example.zed

import android.app.AlertDialog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.zed.databinding.ItemUserReportBinding
import java.text.NumberFormat
import java.util.Locale

class UserReportAdapter(
    private var userReports: List<UserReportData>,
    private val isAdmin: Boolean,
    private val onConfirmPayment: (userEmail: String, amount: Double) -> Unit
) : RecyclerView.Adapter<UserReportAdapter.UserReportViewHolder>() {

    class UserReportViewHolder(val binding: ItemUserReportBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UserReportViewHolder {
        val binding = ItemUserReportBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return UserReportViewHolder(binding)
    }

    override fun getItemCount(): Int = userReports.size

    override fun onBindViewHolder(holder: UserReportViewHolder, position: Int) {
        val report = userReports[position]
        val context = holder.itemView.context
        val currencyFormat = NumberFormat.getCurrencyInstance(Locale("en", "ZM"))

        // --- Bind all data points ---
        holder.binding.userNameTextView.text = report.userName
        holder.binding.userEmailTextView.text = report.userEmail
        holder.binding.outstandingLiabilityTextView.text = currencyFormat.format(report.outstandingLiability)
        holder.binding.variancesTextView.text = currencyFormat.format(report.variances)
        holder.binding.todayShortageTextView.text = currencyFormat.format(report.todayShortage)
        holder.binding.monthShortagesTextView.text = currencyFormat.format(report.monthShortages)
        holder.binding.monthPaidTextView.text = currencyFormat.format(report.monthPaid)
        holder.binding.monthPositiveVariancesTextView.text = currencyFormat.format(report.monthPositiveVariances)

        // ✅ --- THIS IS THE FIX ---
        // Control visibility of individual admin controls directly.
        if (isAdmin) {
            holder.binding.todayPaidEditText.visibility = View.VISIBLE
            holder.binding.confirmPaidButton.visibility = View.VISIBLE

            holder.binding.confirmPaidButton.setOnClickListener {
                val amountStr = holder.binding.todayPaidEditText.text.toString()
                val amount = amountStr.toDoubleOrNull()
                if (amount == null || amount <= 0) {
                    holder.binding.todayPaidEditText.error = "Enter a valid amount"
                    return@setOnClickListener
                }

                AlertDialog.Builder(context)
                    .setTitle("Confirm Payment")
                    .setMessage("Pay K${"%.2f".format(amount)} to ${report.userName} to settle liability?")
                    .setPositiveButton("Confirm") { _, _ ->
                        onConfirmPayment(report.userEmail, amount)
                        // Clear the input after confirming
                        holder.binding.todayPaidEditText.text.clear()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        } else {
            // Hide for non-admins
            holder.binding.todayPaidEditText.visibility = View.GONE
            holder.binding.confirmPaidButton.visibility = View.GONE
        }
        // --- END OF FIX ---
    }

    fun updateData(newReports: List<UserReportData>) {
        this.userReports = newReports
        notifyDataSetChanged()
    }
}
