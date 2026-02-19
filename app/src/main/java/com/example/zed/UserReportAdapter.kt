package com.example.zed

import android.app.AlertDialog
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.zed.databinding.ItemUserReportBinding
import com.google.android.material.button.MaterialButton
import java.text.NumberFormat
import java.util.Locale

// Data classes
data class ManagerReview(
    val id: String,
    val reviewerEmail: String,
    val description: String,
    val status: String
)

data class UserTransaction(
    val id: String,
    val date: String,
    val actualTimeIn: String,
    val supposedTimeIn: String,
    val actualTimeOut: String,
    val supposedTimeOut: String,
    val managerReviews: List<ManagerReview>
)


// --- ManagerReviewAdapter (To show reviews inside each transaction) ---
class ManagerReviewAdapter : ListAdapter<ManagerReview, ManagerReviewAdapter.ReviewViewHolder>(ManagerReviewDiffCallback()) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ReviewViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_manager_review, parent, false)
    )
    override fun onBindViewHolder(holder: ReviewViewHolder, position: Int) = holder.bind(getItem(position))

    class ReviewViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val email: TextView = itemView.findViewById(R.id.reviewerEmailTextView)
        private val description: TextView = itemView.findViewById(R.id.reviewDescriptionTextView)
        private val status: CardView = itemView.findViewById(R.id.reviewStatusIndicator)

        fun bind(review: ManagerReview) {
            email.text = review.reviewerEmail
            description.text = review.description
            val statusColor = when (review.status.lowercase()) {
                "good" -> R.color.status_yellow
                "very good" -> R.color.status_green
                "bad" -> R.color.status_red
                else -> android.R.color.darker_gray
            }
            status.setCardBackgroundColor(ContextCompat.getColor(itemView.context, statusColor))
        }
    }

    class ManagerReviewDiffCallback : DiffUtil.ItemCallback<ManagerReview>() {
        override fun areItemsTheSame(oldItem: ManagerReview, newItem: ManagerReview) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: ManagerReview, newItem: ManagerReview) = oldItem == newItem
    }
}

// --- InnerTransactionAdapter (To show the list of transactions) ---
class InnerTransactionAdapter : ListAdapter<UserTransaction, InnerTransactionAdapter.TransactionViewHolder>(TransactionDiffCallback()) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TransactionViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_user_transaction, parent, false)
        return TransactionViewHolder(view)
    }

    override fun onBindViewHolder(holder: TransactionViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class TransactionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val date: TextView = itemView.findViewById(R.id.transactionDateTextView)
        private val timeInActual: TextView = itemView.findViewById(R.id.timeInActualTextView)
        private val timeInSupposed: TextView = itemView.findViewById(R.id.timeInSupposedTextView)
        private val timeOutActual: TextView = itemView.findViewById(R.id.timeOutActualTextView)
        private val timeOutSupposed: TextView = itemView.findViewById(R.id.timeOutSupposedTextView)
        private val clickableHeader: LinearLayout = itemView.findViewById(R.id.clickableTransactionHeader)
        private val managerReviewsRecyclerView: RecyclerView = itemView.findViewById(R.id.managerReviewsRecyclerView)
        private val managerReviewAdapter = ManagerReviewAdapter()

        init {
            managerReviewsRecyclerView.adapter = managerReviewAdapter
            managerReviewsRecyclerView.setRecycledViewPool(RecyclerView.RecycledViewPool())
        }

        fun bind(transaction: UserTransaction) {
            date.text = transaction.date
            timeInActual.text = transaction.actualTimeIn
            timeOutActual.text = transaction.actualTimeOut
            timeInSupposed.visibility = View.GONE
            timeOutSupposed.visibility = View.GONE
            managerReviewsRecyclerView.visibility = View.GONE

            if (transaction.managerReviews.isNotEmpty()) {
                clickableHeader.isClickable = true
                clickableHeader.setOnClickListener {
                    val isVisible = managerReviewsRecyclerView.visibility == View.VISIBLE
                    managerReviewsRecyclerView.visibility = if (isVisible) View.GONE else View.VISIBLE
                }
                managerReviewAdapter.submitList(transaction.managerReviews)
            } else {
                clickableHeader.isClickable = true
                clickableHeader.setOnClickListener {
                    Toast.makeText(itemView.context, "No reviews for this day.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    class TransactionDiffCallback : DiffUtil.ItemCallback<UserTransaction>() {
        override fun areItemsTheSame(oldItem: UserTransaction, newItem: UserTransaction) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: UserTransaction, newItem: UserTransaction) = oldItem == newItem
    }
}


// --- Main Adapter for User Reports ---
class UserReportAdapter(
    // ✅ THIS IS THE FIX: Changed 'val' to 'var' to make it mutable
    private var isAdmin: Boolean,
    private val onConfirmPayment: (userEmail: String, amount: Double) -> Unit,
    private val onPageRequested: (userId: String, page: Int, holder: UserReportViewHolder) -> Unit,
    private val onAddReviewClicked: (userEmail: String) -> Unit,
    private val onFilterChanged: (userEmail: String, period: String) -> Unit
) : ListAdapter<UserReportData, UserReportAdapter.UserReportViewHolder>(UserReportDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UserReportViewHolder {
        val binding = ItemUserReportBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return UserReportViewHolder(binding, onPageRequested, onAddReviewClicked, onFilterChanged)
    }

    override fun onBindViewHolder(holder: UserReportViewHolder, position: Int) {
        val report = getItem(position)
        holder.bind(report, isAdmin, onConfirmPayment)
    }

    // This function can now correctly modify the 'isAdmin' property
    fun setAdminStatus(isAdmin: Boolean) {
        this.isAdmin = isAdmin
    }

    class UserReportViewHolder(
        val binding: ItemUserReportBinding,
        private val onPageRequested: (userId: String, page: Int, holder: UserReportViewHolder) -> Unit,
        private val onAddReviewClicked: (userEmail: String) -> Unit,
        private val onFilterChanged: (userEmail: String, period: String) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        private val transactionAdapter = InnerTransactionAdapter()
        private var currentUserReport: UserReportData? = null
        private var currentPage = 1
        private var totalPages = 1

        init {
            binding.transactionsRecyclerView.adapter = transactionAdapter
            binding.transactionsRecyclerView.setRecycledViewPool(RecyclerView.RecycledViewPool())
            setupFilterSpinner()
        }

        private fun setupFilterSpinner() {
            val filters = arrayOf("Today", "Yesterday", "This Week", "This Month", "Custom")
            val spinnerAdapter = ArrayAdapter(itemView.context, R.layout.spinner_item_11sp, filters)

            spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.reviewFilter.adapter = spinnerAdapter

            binding.reviewFilter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    currentUserReport?.let { report ->
                        val selectedPeriod = parent?.getItemAtPosition(position).toString()
                        onFilterChanged(report.userEmail, selectedPeriod)
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) { /* Do nothing */ }
            }
        }

        fun bind(
            report: UserReportData,
            isAdmin: Boolean,
            onConfirmPayment: (userEmail: String, amount: Double) -> Unit
        ) {
            this.currentUserReport = report
            this.currentPage = 1
            this.totalPages = report.transactionTotalPages ?: 1

            val context = itemView.context
            val currencyFormat = NumberFormat.getCurrencyInstance(Locale("en", "ZM"))

            binding.userNameTextView.text = report.userName
            binding.userEmailTextView.text = report.userEmail
            binding.outstandingLiabilityTextView.text = currencyFormat.format(report.outstandingLiability)
            binding.variancesTextView.text = currencyFormat.format(report.variances)
            binding.todayShortageTextView.text = currencyFormat.format(report.todayShortage)
            binding.monthShortagesTextView.text = currencyFormat.format(report.monthShortages)
            binding.monthPaidTextView.text = currencyFormat.format(report.monthPaid)
            binding.monthPositiveVariancesTextView.text = currencyFormat.format(report.monthPositiveVariances)

            val totalReviews = (report.veryGoodPercentage + report.goodPercentage + report.badPercentage)
            val indicatorColor = if (totalReviews == 0) {
                android.R.color.darker_gray
            } else if (report.badPercentage >= 50) {
                R.color.status_red
            } else if (report.veryGoodPercentage >= 50) {
                R.color.status_green
            } else {
                R.color.status_yellow
            }
            binding.UserWorkingStatus.setCardBackgroundColor(
                ContextCompat.getColor(itemView.context, indicatorColor)
            )

            if (isAdmin) {
                binding.todayPaidEditText.visibility = View.VISIBLE
                binding.confirmPaidButton.visibility = View.VISIBLE
                binding.confirmPaidButton.setOnClickListener {
                    val amountStr = binding.todayPaidEditText.text.toString()
                    val amount = amountStr.toDoubleOrNull()
                    if (amount == null || amount <= 0) {
                        binding.todayPaidEditText.error = "Enter a valid amount"
                        return@setOnClickListener
                    }
                    AlertDialog.Builder(context)
                        .setTitle("Confirm Payment")
                        .setMessage("Pay K${"%.2f".format(amount)} to ${report.userName} to settle liability?")
                        .setPositiveButton("Confirm") { _, _ ->
                            onConfirmPayment(report.userEmail, amount)
                            binding.todayPaidEditText.text.clear()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            } else {
                binding.todayPaidEditText.visibility = View.GONE
                binding.confirmPaidButton.visibility = View.GONE
            }

            binding.collapsibleContainer.visibility = View.GONE
            (binding.reviewDetailsButton as MaterialButton).apply {
                text = "Review Transactions"
                setIconResource(R.drawable.drop_down_option_arrow)
            }
            updatePaginationUi()

            binding.reviewDetailsButton.setOnClickListener { toggleReviewSection() }
            binding.prevButton.setOnClickListener {
                if (currentPage > 1) {
                    currentPage--
                    loadPageData()
                }
            }
            binding.nextButton.setOnClickListener {
                if (currentPage < totalPages) {
                    currentPage++
                    loadPageData()
                }
            }

            binding.reviewButton.setOnClickListener {
                onAddReviewClicked(report.userEmail)
            }
        }

        private fun toggleReviewSection() {
            val isVisible = binding.collapsibleContainer.visibility == View.VISIBLE
            val button = binding.reviewDetailsButton as MaterialButton
            if (isVisible) {
                binding.collapsibleContainer.visibility = View.GONE
                button.text = "Review Transactions"
                button.setIconResource(R.drawable.drop_down_option_arrow)
            } else {
                binding.collapsibleContainer.visibility = View.VISIBLE
                button.text = "Hide Transactions"
                // Assuming you have an 'ic_arrow_up' drawable
                button.setIconResource(R.drawable.drop_down_option_arrow)
                if (transactionAdapter.itemCount == 0) {
                    loadPageData()
                }
            }
        }

        private fun loadPageData() {
            currentUserReport?.let { onPageRequested(it.userEmail, currentPage, this) }
        }

        fun updateTransactions(transactions: List<UserTransaction>, page: Int, totalPages: Int) {
            this.currentPage = page
            this.totalPages = totalPages
            transactionAdapter.submitList(transactions)
            updatePaginationUi()
        }

        private fun updatePaginationUi() {
            binding.pageInfoTextView.text = "Page $currentPage of $totalPages"
            binding.prevButton.isEnabled = currentPage > 1
            binding.nextButton.isEnabled = currentPage < totalPages
        }
    }

    class UserReportDiffCallback : DiffUtil.ItemCallback<UserReportData>() {
        override fun areItemsTheSame(oldItem: UserReportData, newItem: UserReportData) =
            oldItem.userEmail == newItem.userEmail

        override fun areContentsTheSame(oldItem: UserReportData, newItem: UserReportData) =
            oldItem == newItem
    }
}
