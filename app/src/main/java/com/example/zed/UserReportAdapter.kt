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
    // ✅ 1. Add isAdmin flag
    private val isAdmin: Boolean,
    // ✅ 2. Add callback for confirming payment
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

        holder.binding.userNameTextView.text = report.userName
        holder.binding.userEmailTextView.text = report.userEmail
        holder.binding.outstandingLiabilityTextView.text = currencyFormat.format(report.outstandingLiability)

        // ✅ --- THIS IS THE FIX ---
        // Bind the new variances data to the correct TextView
        holder.binding.variancesTextView.text = currencyFormat.format(report.variances)
        // --- END OF FIX ---

        holder.binding.todayShortageTextView.text = currencyFormat.format(report.todayShortage)
        holder.binding.monthShortagesTextView.text = currencyFormat.format(report.monthShortages)
        holder.binding.monthPaidTextView.text = currencyFormat.format(report.monthPaid)

        // ✅ 3. CONTROL VISIBILITY AND SETUP CLICK LISTENER
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
    }

    fun updateData(newReports: List<UserReportData>) {
        this.userReports = newReports
        notifyDataSetChanged()
    }
}
