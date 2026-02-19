package com.example.zed

import android.app.AlertDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioGroup
import android.widget.Toast
import androidx.core.util.Pair
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.zed.databinding.FragmentUserReportBinding
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.textfield.TextInputEditText
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.SheetsScopes
import com.google.api.services.sheets.v4.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONArray
import java.io.IOException
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.*

class user_report : Fragment() {

    private var _binding: FragmentUserReportBinding? = null
    private val binding get() = _binding!!

    private lateinit var googleAccount: GoogleSignInAccount
    private lateinit var adapter: UserReportAdapter
    private val TAG = "UserReportFragment"
    private var isAdmin: Boolean = false

    private val scriptUrl = "https://script.google.com/macros/s/AKfycbzQYviWEzzxMnokoiTdCTBsN7DN_fYhV3CIoqOXkjP-_5et6Ux0RzV8DUp-lkDYHQ9T/exec"

    private val dateFormats = listOf(
        SimpleDateFormat("d/M/yyyy, h:mm:ss a", Locale.getDefault()),
        SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()),
        SimpleDateFormat("d/M/yyyy, HH:mm:ss", Locale.getDefault()),
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    )

    // Properties for holding all fetched data
    private var allClosingBalanceData: List<List<Any>> = emptyList()
    private var allExpensesData: List<List<Any>> = emptyList()
    private var allStockTakingData: List<List<Any>> = emptyList()
    private var allReviewData: List<List<Any>> = emptyList()
    private var allUsers: List<UserRole> = emptyList()

    private val userFilterSelections = mutableMapOf<String, String>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentUserReportBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val account = GoogleSignIn.getLastSignedInAccount(requireContext())
        if (account == null) {
            Toast.makeText(requireContext(), "Sign-in required.", Toast.LENGTH_LONG).show()
            return
        }
        googleAccount = account
        setupRecyclerView()
        fetchInitialData()
        binding.swipeRefreshLayout.setOnRefreshListener { fetchInitialData() }
    }

    private fun setupRecyclerView() {
        adapter = UserReportAdapter(
            isAdmin = false, // This will be updated in fetchInitialData
            onConfirmPayment = { userEmail, amount -> recordPayment(userEmail, amount) },
            onPageRequested = { userId, page, holder ->
                fetchTransactionsForUser(userId, page, holder)
            },
            onAddReviewClicked = { userEmail -> showAddReviewDialog(userEmail) },
            onFilterChanged = { userEmail, period ->
                userFilterSelections[userEmail] = period
                if (period == "Custom") {
                    showDatePicker { dateRange ->
                        processAndDisplayReports(userEmail, dateRange)
                    }
                } else {
                    val dateRange = getDateRangeForPeriod(period)
                    processAndDisplayReports(userEmail, dateRange)
                }
            }
        )
        binding.userReportRecyclerView.adapter = adapter
    }

    private fun fetchInitialData() {
        binding.swipeRefreshLayout.isRefreshing = true
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val sheetsService = getSheetsService(googleAccount)
                val spreadsheetId = findSheetIdByName(getDriveService(googleAccount), "nia-bridge data")
                    ?: throw IOException("Spreadsheet 'nia-bridge data' not found.")

                allUsers = fetchUsersAndRolesFromScript()
                val currentUser = allUsers.firstOrNull { it.email.equals(googleAccount.email, ignoreCase = true) }
                isAdmin = currentUser?.isSubUser == false

                val rangesToFetch = listOf("Closing Balance!A:I", "Expenses!A:I", "stock_taking!A:H", "review_sheet!A:E")
                val batchData = sheetsService.spreadsheets().values().batchGet(spreadsheetId).setRanges(rangesToFetch).execute()

                allClosingBalanceData = batchData.valueRanges.getOrNull(0)?.getValues()?.drop(1) ?: emptyList()
                allExpensesData = batchData.valueRanges.getOrNull(1)?.getValues()?.drop(1) ?: emptyList()
                allStockTakingData = batchData.valueRanges.getOrNull(2)?.getValues()?.drop(1) ?: emptyList()
                allReviewData = batchData.valueRanges.getOrNull(3)?.getValues()?.drop(1) ?: emptyList()

                // --- START OF THE FIX ---
                val usersToDisplay = if (isAdmin) allUsers else allUsers.filter { it.email == googleAccount.email }

                // 1. Build the complete list of reports first
                val userReports = usersToDisplay.map { user ->
                    val initialPeriod = userFilterSelections.getOrPut(user.email) { "Today" }
                    val dateRange = getDateRangeForPeriod(initialPeriod)
                    // Generate the report data, but don't submit it to the adapter yet
                    generateUserReport(user.email, dateRange)
                }

                // 2. Switch to the main thread and submit the complete list at once
                withContext(Dispatchers.Main) {
                    adapter.setAdminStatus(isAdmin) // Update admin status in the adapter
                    adapter.submitList(userReports)
                }
                // --- END OF THE FIX ---

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e(TAG, "Error fetching initial data", e)
                    Toast.makeText(requireContext(), "Failed to load data: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    binding.swipeRefreshLayout.isRefreshing = false
                }
            }
        }
    }

    // ✅ NEW HELPER FUNCTION - Does not touch the adapter
    private fun generateUserReport(userEmail: String, dateRange: Pair<Date, Date>): UserReportData {
        val startDate = dateRange.first
        val endDate = dateRange.second

        val filteredReviews = allReviewData.filter { row ->
            val timestampStr = row.getOrNull(3)?.toString()
            val date = parseDateString(timestampStr)
            date != null && !date.before(startDate) && !date.after(endDate)
        }

        val user = allUsers.find { it.email.equals(userEmail, ignoreCase = true) }!!

        val userReviews = filteredReviews.filter { it.getOrNull(0)?.toString().equals(user.email, ignoreCase = true) }
        val total = userReviews.size

        val veryGoodCount = userReviews.count { it.getOrNull(2)?.toString().equals("Very Good", ignoreCase = true) }
        val goodCount = userReviews.count { it.getOrNull(2)?.toString().equals("Good", ignoreCase = true) }
        val badCount = userReviews.count { it.getOrNull(2)?.toString().equals("Bad", ignoreCase = true) }

        val veryGoodPercent = if (total > 0) (veryGoodCount * 100) / total else 0
        val goodPercent = if (total > 0) (goodCount * 100) / total else 0
        val badPercent = if (total > 0) (badCount * 100) / total else 0

        val financialData = processUserData(user.email, allClosingBalanceData, allExpensesData, allStockTakingData)

        return UserReportData(
            userName = user.email.split("@").firstOrNull()?.replaceFirstChar { it.titlecase() } ?: "Unknown",
            userEmail = user.email,
            outstandingLiability = financialData.outstandingLiability,
            variances = financialData.variances,
            todayShortage = financialData.todayShortage,
            monthShortages = financialData.monthShortages,
            monthPaid = financialData.monthPaid,
            monthPositiveVariances = financialData.monthPositiveVariances,
            veryGoodPercentage = veryGoodPercent,
            goodPercentage = goodPercent,
            badPercentage = badPercent,
            transactionTotalPages = 1
        )
    }

    // ✅ CORRECTED - This function now ONLY updates an existing item
    private fun processAndDisplayReports(userEmail: String, dateRange: Pair<Date, Date>) {
        // This should run on a background thread to avoid blocking the UI
        lifecycleScope.launch(Dispatchers.IO) {
            val newReport = generateUserReport(userEmail, dateRange)

            withContext(Dispatchers.Main) {
                val currentList = adapter.currentList.toMutableList()
                val index = currentList.indexOfFirst { it.userEmail.equals(userEmail, ignoreCase = true) }

                if (index != -1) {
                    currentList[index] = newReport
                    adapter.submitList(currentList)
                }
            }
        }
    }


    private fun getDateRangeForPeriod(period: String): Pair<Date, Date> {
        val cal = Calendar.getInstance()

        return when (period) {
            "Yesterday" -> {
                cal.add(Calendar.DAY_OF_YEAR, -1)
                Pair(getStartOfDay(cal.time), getEndOfDay(cal.time))
            }
            "This Week" -> {
                cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
                val start = getStartOfDay(cal.time)
                cal.add(Calendar.WEEK_OF_YEAR, 1)
                cal.add(Calendar.DAY_OF_YEAR, -1)
                val end = getEndOfDay(cal.time)
                Pair(start, end)
            }
            "This Month" -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                val start = getStartOfDay(cal.time)
                cal.add(Calendar.MONTH, 1)
                cal.add(Calendar.DAY_OF_YEAR, -1)
                val end = getEndOfDay(cal.time)
                Pair(start, end)
            }
            else -> { // Default to "Today"
                Pair(getStartOfDay(cal.time), getEndOfDay(cal.time))
            }
        }
    }



    private fun showDatePicker(onDateSelected: (Pair<Date, Date>) -> Unit) {
        val dateRangePicker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText("Select Date Range")
            .setSelection(Pair(MaterialDatePicker.thisMonthInUtcMilliseconds(), MaterialDatePicker.todayInUtcMilliseconds()))
            .build()

        dateRangePicker.addOnPositiveButtonClickListener { selection ->
            val startDate = Date(selection.first)
            val endDate = Date(selection.second)
            onDateSelected(Pair(getStartOfDay(startDate), getEndOfDay(endDate)))
        }

        dateRangePicker.show(childFragmentManager, "DATE_PICKER")
    }

    private fun showAddReviewDialog(userEmail: String) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_add_review, null)
        val radioGroup = dialogView.findViewById<RadioGroup>(R.id.reviewStatusRadioGroup)
        val commentEditText = dialogView.findViewById<TextInputEditText>(R.id.commentEditText)
        val timeInEditText = dialogView.findViewById<TextInputEditText>(R.id.supposedTimeInEditText)
        val timeOutEditText = dialogView.findViewById<TextInputEditText>(R.id.supposedTimeOutEditText)

        val timeSetListener = { editText: TextInputEditText ->
            TimePickerDialog.OnTimeSetListener { _, hourOfDay, minute ->
                editText.setText(String.format(Locale.getDefault(), "%02d:%02d", hourOfDay, minute))
            }
        }

        timeInEditText.setOnClickListener {
            TimePickerDialog(requireContext(), timeSetListener(timeInEditText), 7, 30, true).show()
        }
        timeOutEditText.setOnClickListener {
            TimePickerDialog(requireContext(), timeSetListener(timeOutEditText), 20, 30, true).show()
        }

        AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setPositiveButton("Submit") { dialog, _ ->
                val selectedId = radioGroup.checkedRadioButtonId
                if (selectedId == -1) {
                    Toast.makeText(context, "Please select a status", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val status = when (selectedId) {
                    R.id.radioVeryGood -> "Very Good"
                    R.id.radioGood -> "Good"
                    R.id.radioBad -> "Bad"
                    else -> "Unknown"
                }

                val supposedTimeIn = timeInEditText.text.toString()
                val supposedTimeOut = timeOutEditText.text.toString()
                val originalComment = commentEditText.text.toString()
                val fullComment = if (originalComment.isNotBlank()) {
                    "$originalComment\nExpected: $supposedTimeIn - $supposedTimeOut"
                } else {
                    "Expected: $supposedTimeIn - $supposedTimeOut"
                }

                submitReviewToSheet(userEmail, fullComment, status)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun fetchTransactionsForUser(userEmail: String, page: Int, holder: UserReportAdapter.UserReportViewHolder) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val reviewsByDate = allReviewData.mapNotNull { row ->
                    val reviewedUser = row.getOrNull(0)?.toString()
                    if (userEmail.equals(reviewedUser, ignoreCase = true)) {
                        val comment = row.getOrNull(1)?.toString() ?: ""
                        val status = row.getOrNull(2)?.toString() ?: "Unknown"
                        val timestampStr = row.getOrNull(3)?.toString()
                        val commenter = row.getOrNull(4)?.toString() ?: "Unknown"
                        val date = parseDateString(timestampStr)
                        if (date != null) {
                            Pair(getStartOfDay(date), ManagerReview(UUID.randomUUID().toString(), commenter, comment, status))
                        } else {
                            null
                        }
                    } else {
                        null
                    }
                }.groupBy({ it.first }, { it.second })

                val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
                val dateFormat = SimpleDateFormat("EEE, dd MMM yyyy", Locale.getDefault())

                val userTransactions = allClosingBalanceData
                    .mapNotNull { row ->
                        val closingTimestampStr = row.getOrNull(5)?.toString()
                        val closingDate = parseDateString(closingTimestampStr)
                        if (userEmail.equals(row.getOrNull(7)?.toString(), ignoreCase = true) && closingDate != null) {
                            Pair(row, closingDate)
                        } else {
                            null
                        }
                    }
                    .sortedByDescending { it.second }
                    .map { pair ->
                        val row = pair.first
                        val closingDate = pair.second
                        val clockInTimestampStr = row.getOrNull(8)?.toString()
                        val clockInDate = parseDateString(clockInTimestampStr)
                        val reviewsForThisDay = reviewsByDate[getStartOfDay(closingDate)] ?: emptyList()
                        UserTransaction(
                            id = row.getOrNull(0)?.toString() ?: UUID.randomUUID().toString(),
                            date = dateFormat.format(closingDate),
                            actualTimeIn = clockInDate?.let { timeFormat.format(it) } ?: "N/A",
                            supposedTimeIn = "",
                            actualTimeOut = timeFormat.format(closingDate),
                            supposedTimeOut = "",
                            managerReviews = reviewsForThisDay
                        )
                    }

                withContext(Dispatchers.Main) {
                    holder.updateTransactions(userTransactions, 1, 1)
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e(TAG, "Failed to fetch transactions for user", e)
                    Toast.makeText(context, "Could not load transaction details.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }


    private fun submitReviewToSheet(userEmail: String, comment: String, status: String) {
        val progress = android.app.ProgressDialog(requireContext()).apply {
            setMessage("Submitting review...")
            setCancelable(false)
            show()
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val sheetsService = getSheetsService(googleAccount, readOnly = false)
                val spreadsheetId = findSheetIdByName(getDriveService(googleAccount), "nia-bridge data")
                    ?: throw IOException("Spreadsheet 'nia-bridge data' not found.")

                checkAndCreateSheet(sheetsService, spreadsheetId, "review_sheet")

                val timestamp = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())
                val loggedInUser = googleAccount.email ?: "Unknown"

                val reviewRow = listOf(userEmail, comment, status, timestamp, loggedInUser)
                val valueRange = ValueRange().setValues(listOf(reviewRow))

                sheetsService.spreadsheets().values()
                    .append(spreadsheetId, "review_sheet!A:E", valueRange)
                    .setValueInputOption("USER_ENTERED")
                    .execute()

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Review submitted successfully!", Toast.LENGTH_LONG).show()
                    fetchInitialData()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e(TAG, "Failed to submit review", e)
                    Toast.makeText(context, "Error submitting review: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    if (progress.isShowing) progress.dismiss()
                }
            }
        }
    }

    private suspend fun checkAndCreateSheet(sheetsService: Sheets, spreadsheetId: String, sheetName: String) {
        val spreadsheet = sheetsService.spreadsheets().get(spreadsheetId).execute()
        val sheetExists = spreadsheet.sheets.any { it.properties.title == sheetName }

        if (!sheetExists) {
            val requests = mutableListOf<Request>()
            requests.add(Request().setAddSheet(AddSheetRequest().setProperties(SheetProperties().setTitle(sheetName))))

            val batchUpdateRequest = BatchUpdateSpreadsheetRequest().setRequests(requests)
            sheetsService.spreadsheets().batchUpdate(spreadsheetId, batchUpdateRequest).execute()

            val headers = listOf("unit_id", "comment", "comment_status", "createTimeStamp", "CommentedBy")
            val valueRange = ValueRange().setValues(listOf(headers))
            sheetsService.spreadsheets().values()
                .update(spreadsheetId, "$sheetName!A1", valueRange)
                .setValueInputOption("USER_ENTERED")
                .execute()
        }
    }

    private fun recordPayment(userEmail: String, amount: Double) {
        val progress = android.app.ProgressDialog(requireContext()).apply {
            setMessage("Recording payment...")
            setCancelable(false)
            show()
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val sheetsService = getSheetsService(googleAccount, readOnly = false)
                val spreadsheetId = findSheetIdByName(getDriveService(googleAccount), "nia-bridge data")
                    ?: throw IOException("Spreadsheet not found.")

                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                val timestamp = sdf.format(Date())

                val expenseRow = listOf(
                    UUID.randomUUID().toString(),
                    "Liability Settlement",
                    "Payment to $userEmail",
                    1,
                    amount,
                    "TRUE",
                    googleAccount.email,
                    timestamp,
                    timestamp
                )
                val expenseValueRange = ValueRange().setValues(listOf(expenseRow))
                sheetsService.spreadsheets().values()
                    .append(spreadsheetId, "Expenses!A:I", expenseValueRange)
                    .setValueInputOption("USER_ENTERED")
                    .execute()

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Payment recorded successfully.", Toast.LENGTH_SHORT).show()
                    fetchInitialData()
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e(TAG, "Failed to record payment", e)
                    Toast.makeText(context, "Error recording payment: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    if (progress.isShowing) progress.dismiss()
                }
            }
        }
    }

    private suspend fun getSheetsService(account: GoogleSignInAccount, readOnly: Boolean = true): Sheets = withContext(Dispatchers.IO) {
        val scopes = if (readOnly) listOf(SheetsScopes.SPREADSHEETS_READONLY) else listOf(SheetsScopes.SPREADSHEETS)
        val credential = GoogleAccountCredential.usingOAuth2(requireContext(), scopes)
            .setBackOff(com.google.api.client.util.ExponentialBackOff())
            .apply { selectedAccount = account.account }

        Sheets.Builder(GoogleNetHttpTransport.newTrustedTransport(), GsonFactory.getDefaultInstance(), credential)
            .setApplicationName(getString(R.string.app_name))
            .build()
    }

    private fun parseDateString(dateStr: String?): Date? {
        if (dateStr.isNullOrBlank()) return null
        for (format in dateFormats) {
            try {
                return format.parse(dateStr)
            } catch (e: ParseException) { /* Try next */ }
        }
        Log.w(TAG, "Unparseable date: '$dateStr'")
        return null
    }

    private suspend fun fetchUsersAndRolesFromScript(): List<UserRole> = withContext(Dispatchers.IO) {
        val client = OkHttpClient()
        val urlWithParams = "$scriptUrl?action=getUsers"
        val request = okhttp3.Request.Builder().url(urlWithParams).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Failed to fetch user data: ${response.code}")
            val responseBody = response.body?.string() ?: throw IOException("Empty response from server.")

            val userMap = mutableMapOf<String, UserRole>()
            val jsonArray = JSONArray(responseBody)
            val roleKeys = listOf("stock", "transaction", "grn", "purchase requisition", "inventory count", "cash tracker")

            for (i in 0 until jsonArray.length()) {
                val jsonObject = jsonArray.getJSONObject(i)
                val mainEmail = jsonObject.optString("email", "").trim().lowercase()
                val subUserEmail = jsonObject.optString("email sub user", "").trim().lowercase()

                if (mainEmail.isNotEmpty()) {
                    val isAdminFromSheet = jsonObject.optString("admin", "") == "1"
                    val roles = if (isAdminFromSheet) {
                        roleKeys.associateWith { true }
                    } else {
                        roleKeys.associateWith { key -> jsonObject.optString(key, "") == "1" }
                    }
                    userMap[mainEmail] = UserRole(email = mainEmail, isSubUser = false, roles = roles)
                }

                if (subUserEmail.isNotEmpty()) {
                    val subUserRoles = roleKeys.associateWith { key -> jsonObject.optString(key, "") == "1" }
                    userMap[subUserEmail] = UserRole(email = subUserEmail, isSubUser = true, roles = subUserRoles)
                }
            }
            return@withContext userMap.values.toList()
        }
    }

    private fun processUserData(
        userEmail: String,
        closingBalanceData: List<List<Any>>,
        expensesData: List<List<Any>>,
        stockTakingData: List<List<Any>>
    ): UserFinancials { // Changed return type

        val calendar = Calendar.getInstance()
        val todayStart = getStartOfDay(calendar.time)
        val monthStart = getStartOfDay(calendar.apply { set(Calendar.DAY_OF_MONTH, 1) }.time)

        var netTodayCashLiability = 0.0
        var netMonthCashLiability = 0.0
        var netTotalCashLiability = 0.0

        for (row in closingBalanceData) {
            if (userEmail.equals(row.getOrNull(7)?.toString(), ignoreCase = true)) {
                val timestamp = parseDateString(row.getOrNull(5)?.toString()) ?: continue
                val shortage = row.getOrNull(4)?.toString()?.toDoubleOrNull() ?: 0.0

                netTotalCashLiability += shortage
                if (timestamp.after(monthStart)) netMonthCashLiability += shortage
                if (timestamp.after(todayStart)) netTodayCashLiability += shortage
            }
        }

        var monthPaid = 0.0
        var totalPaid = 0.0

        for (row in expensesData) {
            val description = row.getOrNull(2)?.toString() ?: ""
            if (description.contains("Payment to $userEmail", ignoreCase = true)) {
                val timestamp = parseDateString(row.getOrNull(7)?.toString()) ?: continue
                val amount = row.getOrNull(4)?.toString()?.toDoubleOrNull() ?: 0.0
                totalPaid += amount
                if (timestamp.after(monthStart)) monthPaid += amount
            }
        }

        var netTotalStockVarianceLiability = 0.0
        var netMonthStockVarianceLiability = 0.0
        var monthPositiveVariances = 0.0

        val groupedStockTakes = stockTakingData.groupBy { parseDateString(it.getOrNull(0)?.toString()) }

        for ((timestamp, rows) in groupedStockTakes) {
            if (timestamp == null) continue

            val periodEnd = getEndOfDay(timestamp)
            val periodStart = getStartOfDay(Calendar.getInstance().apply {
                time = timestamp
                add(Calendar.DAY_OF_YEAR, -6)
            }.time)

            val liableUserForPeriod = closingBalanceData
                .filter {
                    val cbTimestamp = parseDateString(it.getOrNull(5)?.toString())
                    cbTimestamp != null && cbTimestamp >= periodStart && cbTimestamp <= periodEnd
                }
                .mapNotNull { it.getOrNull(7)?.toString() }
                .groupingBy { it.lowercase() }
                .eachCount()
                .maxByOrNull { it.value }?.key

            if (userEmail.equals(liableUserForPeriod, ignoreCase = true)) {
                val netVarianceForTimestamp = rows.sumOf {
                    val cost = it.getOrNull(6)?.toString()?.toDoubleOrNull() ?: 0.0
                    -cost
                }

                netTotalStockVarianceLiability += netVarianceForTimestamp

                if (timestamp.after(monthStart)) {
                    netMonthStockVarianceLiability += netVarianceForTimestamp
                    val positiveSurplusThisMonth = rows.sumOf {
                        val cost = it.getOrNull(6)?.toString()?.toDoubleOrNull() ?: 0.0
                        if (cost < 0) -cost else 0.0
                    }
                    monthPositiveVariances += positiveSurplusThisMonth
                }
            }
        }

        val outstandingLiability = (netTotalCashLiability + netTotalStockVarianceLiability) - totalPaid
        val netMonthLiability = (netMonthCashLiability + netMonthStockVarianceLiability)

        return UserFinancials(
            outstandingLiability = outstandingLiability,
            variances = netTotalStockVarianceLiability,
            todayShortage = netTodayCashLiability,
            monthShortages = netMonthLiability,
            monthPaid = monthPaid,
            monthPositiveVariances = monthPositiveVariances
        )
    }

    private fun getStartOfDay(date: Date): Date = Calendar.getInstance().apply {
        time = date
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.time

    private fun getEndOfDay(date: Date): Date = Calendar.getInstance().apply {
        time = date
        set(Calendar.HOUR_OF_DAY, 23)
        set(Calendar.MINUTE, 59)
        set(Calendar.SECOND, 59)
        set(Calendar.MILLISECOND, 999)
    }.time

    private suspend fun getDriveService(account: GoogleSignInAccount): Drive = withContext(Dispatchers.IO) {
        val credential = GoogleAccountCredential.usingOAuth2(requireContext(), listOf(DriveScopes.DRIVE_READONLY)).setBackOff(com.google.api.client.util.ExponentialBackOff()).apply { selectedAccount = account.account }
        Drive.Builder(GoogleNetHttpTransport.newTrustedTransport(), GsonFactory.getDefaultInstance(), credential).setApplicationName(getString(R.string.app_name)).build()
    }

    private suspend fun findSheetIdByName(driveService: Drive, name: String): String? = withContext(Dispatchers.IO) {
        driveService.files().list().setQ("name='$name' and mimeType='application/vnd.google-apps.spreadsheet' and trashed=false").setFields("files(id)").execute().files.firstOrNull()?.id
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}


data class UserFinancials(
    val outstandingLiability: Double,
    val variances: Double,
    val todayShortage: Double,
    val monthShortages: Double,
    val monthPaid: Double,
    val monthPositiveVariances: Double
)
