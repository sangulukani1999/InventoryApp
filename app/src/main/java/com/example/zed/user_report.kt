package com.example.zed

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.zed.databinding.FragmentUserReportBinding
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.SheetsScopes
import com.google.api.services.sheets.v4.model.ValueRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONException
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
        SimpleDateFormat("dd/MM/yyyy, HH:mm:ss", Locale.getDefault()),
        SimpleDateFormat("d/M/yyyy, HH:mm:ss", Locale.getDefault()),
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    )

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
        fetchUserReports()
        binding.swipeRefreshLayout.setOnRefreshListener { fetchUserReports() }
    }


    private fun setupRecyclerView() {
        adapter = UserReportAdapter(emptyList(), isAdmin = false) { userEmail, amount ->
            recordPayment(userEmail, amount)
        }
        binding.userReportRecyclerView.adapter = adapter
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
                    fetchUserReports()
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

    private fun fetchUserReports() {
        binding.swipeRefreshLayout.isRefreshing = true

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val allUsers = fetchUsersAndRolesFromScript()
                val currentUserEmail = googleAccount.email
                val currentUser = allUsers.firstOrNull { it.email.equals(currentUserEmail, ignoreCase = true) }
                isAdmin = currentUser?.isSubUser == false

                val sheetsService = getSheetsService(googleAccount, readOnly = false) // Use write-enabled service
                val spreadsheetId = findSheetIdByName(getDriveService(googleAccount), "nia-bridge data")
                    ?: throw IOException("Spreadsheet 'nia-bridge data' not found.")

                // ✅ AUTOMATICALLY FIND AND RECORD VARIANCE BEFORE PROCESSING
                if (isAdmin) {
                    autoRecordVariance(sheetsService, spreadsheetId)
                }

                // Now, fetch all data again, including any new variance expense
                val rangesToFetch = listOf("Closing Balance!A:H", "Expenses!A:I")
                val batchData = sheetsService.spreadsheets().values().batchGet(spreadsheetId).setRanges(rangesToFetch).execute()

                val closingBalanceValues = batchData.valueRanges.getOrNull(0)?.getValues()?.drop(1) ?: emptyList()
                val expenseValues = batchData.valueRanges.getOrNull(1)?.getValues()?.drop(1) ?: emptyList()

                val reports = mutableListOf<UserReportData>()
                val userList = if (isAdmin) allUsers else (currentUser?.let { listOf(it) } ?: emptyList())

                for (user in userList) {
                    reports.add(processUserData(user.email, closingBalanceValues, expenseValues))
                }

                withContext(Dispatchers.Main) {
                    adapter = UserReportAdapter(reports, isAdmin) { userEmail, amount ->
                        recordPayment(userEmail, amount)
                    }
                    binding.userReportRecyclerView.adapter = adapter
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e(TAG, "Error fetching user reports", e)
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    binding.swipeRefreshLayout.isRefreshing = false
                }
            }
        }
    }

    // ✅ --- FUNCTION TO AUTOMATE VARIANCE RECORDING WITH LOGGING ---
    // In user_report.kt

    // ✅ --- FUNCTION TO AUTOMATE VARIANCE RECORDING WITH LOGGING ---
    // In user_report.kt

    // ✅ --- FINAL, CORRECTED FUNCTION TO AUTOMATE VARIANCE RECORDING WITH AGGREGATION ---
    private suspend fun autoRecordVariance(sheetsService: Sheets, spreadsheetId: String) {
        val VARIANCE_TAG = "VarianceLogic"
        try {
            Log.d(VARIANCE_TAG, "--- Starting automatic variance check ---")
            // Define the 7-day window for searching
            val sevenDaysAgo = getStartOfDay(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -7) }.time)
            Log.d(VARIANCE_TAG, "Searching for variances and closings since: $sevenDaysAgo")

            // Fetch all necessary data in one go
            val rangesToFetch = listOf("Closing Balance!E:H", "stock_taking!A:H", "Expenses!A:H")
            val batchData = sheetsService.spreadsheets().values().batchGet(spreadsheetId).setRanges(rangesToFetch).execute()

            val closingBalanceData = batchData.valueRanges.getOrNull(0)?.getValues()?.drop(1) ?: emptyList()
            val stockTakingData = batchData.valueRanges.getOrNull(1)?.getValues()?.drop(1) ?: emptyList()
            val expensesData = batchData.valueRanges.getOrNull(2)?.getValues()?.drop(1) ?: emptyList()

            // --- Step 1: Find the timestamp of the most recent stock-take ---
            val mostRecentStockTakeTimestamp = stockTakingData
                .mapNotNull { row -> parseDateString(row.getOrNull(0)?.toString()) } // Get all timestamps
                .filter { it.after(sevenDaysAgo) } // Filter for the last 7 days
                .maxOrNull() // Find the most recent one

            if (mostRecentStockTakeTimestamp == null) {
                Log.d(VARIANCE_TAG, "No recent stock-take timestamp found. Stopping.")
                return
            }
            Log.d(VARIANCE_TAG, "Step 1: Found most recent stock-take timestamp: $mostRecentStockTakeTimestamp")

            // --- Step 2: Aggregate the total cost for that specific timestamp ---
            val aggregatedVarianceCost = stockTakingData
                .filter { row ->
                    val timestamp = parseDateString(row.getOrNull(0)?.toString())
                    timestamp == mostRecentStockTakeTimestamp // Match the exact timestamp
                }
                .sumOf { row -> row.getOrNull(6)?.toString()?.toDoubleOrNull() ?: 0.0 } // Sum up Total Cost (Col G)

            Log.d(VARIANCE_TAG, "Step 2: Aggregated variance cost for this timestamp is: $aggregatedVarianceCost")

            val varianceDate = mostRecentStockTakeTimestamp
            val varianceCost = aggregatedVarianceCost

            // --- Step 3: Define the 7-day window *based on the stock-take date* ---
            val variancePeriodEnd = getEndOfDay(varianceDate)
            val variancePeriodStart = getStartOfDay(Calendar.getInstance().apply {
                time = varianceDate
                add(Calendar.DAY_OF_YEAR, -6) // 7 days inclusive window ending on the variance date
            }.time)
            Log.d(VARIANCE_TAG, "Step 3: Defined closing balance check window: $variancePeriodStart to $variancePeriodEnd")

            // --- Step 4: Find the Liable User based on the new rule ---
            val closingCounts = closingBalanceData
                .filter { row ->
                    val timestamp = parseDateString(row.getOrNull(1)?.toString()) // Timestamp (Col F of range E:H)
                    timestamp != null && timestamp >= variancePeriodStart && timestamp <= variancePeriodEnd
                }
                .mapNotNull { it.getOrNull(3)?.toString() } // Get the user email (Col H of range E:H)
                .groupingBy { it }
                .eachCount()

            Log.d(VARIANCE_TAG, "Closing counts in period: $closingCounts")

            val liableUser = closingCounts.maxByOrNull { it.value }?.key
            Log.d(VARIANCE_TAG, "Step 4: Determined liable user is: $liableUser")

            // --- Step 5: Record the variance if a liable user and cost exist ---
            if (liableUser != null && varianceCost != 0.0) {
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val uniqueVarianceId = "VAR-${sdf.format(varianceDate)}-$liableUser"
                Log.d(VARIANCE_TAG, "Step 5: Generated Unique ID for variance: $uniqueVarianceId")

                val isAlreadyRecorded = expensesData.any { it.getOrNull(0)?.toString() == uniqueVarianceId }

                if (!isAlreadyRecorded) {
                    Log.d(VARIANCE_TAG, "SUCCESS: Variance is new. Writing to Expenses sheet...")
                    val varianceExpenseRow = listOf(
                        uniqueVarianceId,
                        "Variances",
                        "Stock-take variance for $liableUser on ${sdf.format(varianceDate)}",
                        1,
                        varianceCost,
                        "TRUE",
                        googleAccount.email,
                        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
                        ""
                    )
                    val valueRange = ValueRange().setValues(listOf(varianceExpenseRow))
                    sheetsService.spreadsheets().values()
                        .append(spreadsheetId, "Expenses!A:I", valueRange)
                        .setValueInputOption("USER_ENTERED")
                        .execute()
                    Log.d(VARIANCE_TAG, "--- Variance recorded successfully ---")
                } else {
                    Log.d(VARIANCE_TAG, "INFO: Variance with ID '$uniqueVarianceId' already recorded. No action taken.")
                }
            } else {
                Log.d(VARIANCE_TAG, "Step 5 SKIPPED: No liable user found or variance cost is zero.")
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Log.e(VARIANCE_TAG, "Could not auto-record variance due to an error", e)
            }
        } finally {
            Log.d(VARIANCE_TAG, "--- Finished automatic variance check ---")
        }
    }

    // Helper function to get the end of a given day
    private fun getEndOfDay(date: Date): Date = Calendar.getInstance().apply {
        time = date
        set(Calendar.HOUR_OF_DAY, 23)
        set(Calendar.MINUTE, 59)
        set(Calendar.SECOND, 59)
        set(Calendar.MILLISECOND, 999)
    }.time


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
            val jsonArray = org.json.JSONArray(responseBody)
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

    // ✅ --- FINAL, CORRECTED DATA PROCESSING LOGIC ---
    private fun processUserData(
        userEmail: String,
        closingBalanceData: List<List<Any>>,
        expensesData: List<List<Any>>
    ): UserReportData {

        val calendar = Calendar.getInstance()
        val todayStart = getStartOfDay(calendar.time)
        val monthStart = getStartOfDay(calendar.apply { set(Calendar.DAY_OF_MONTH, 1) }.time)

        // --- 1. Calculate Shortages (All-Time and This Month) ---
        var todayShortage = 0.0
        var monthShortages = 0.0
        var totalShortages = 0.0

        for (row in closingBalanceData) {
            if (userEmail.equals(row.getOrNull(7)?.toString(), true)) {
                val timestamp = parseDateString(row.getOrNull(5)?.toString()) ?: continue
                val shortage = row.getOrNull(4)?.toString()?.toDoubleOrNull() ?: 0.0
                totalShortages += shortage
                if (timestamp.after(monthStart)) monthShortages += shortage
                if (timestamp.after(todayStart)) todayShortage += shortage
            }
        }

        // --- 2. Calculate Payments and Variances from Expenses sheet (All-Time and This Month) ---
        var todayPaid = 0.0
        var monthPaid = 0.0
        var totalPaid = 0.0
        var monthVariances = 0.0
        var totalVariances = 0.0
        val adminEmail = googleAccount.email

        for (row in expensesData) {
            val description = row.getOrNull(2)?.toString() ?: ""
            val permit = row.getOrNull(5)?.toString()
            val rowAdmin = row.getOrNull(6)?.toString()
            val itemType = row.getOrNull(1)?.toString() ?: ""
            val timestamp = parseDateString(row.getOrNull(7)?.toString()) ?: continue
            val amount = row.getOrNull(4)?.toString()?.toDoubleOrNull() ?: 0.0

            // Check for a liability payment
            if (itemType.equals("Liability Settlement", ignoreCase = true) &&
                description.equals("Payment to $userEmail", ignoreCase = true) &&
                permit.equals("TRUE", ignoreCase = true) &&
                rowAdmin.equals(adminEmail, ignoreCase = true)
            ) {
                totalPaid += amount
                if (timestamp.after(monthStart)) monthPaid += amount
                if (timestamp.after(todayStart)) todayPaid += amount
            }
            // Check for an attributed variance
            else if (itemType.equals("Variances", ignoreCase = true) &&
                description.contains(userEmail, ignoreCase = true) &&
                permit.equals("TRUE", ignoreCase = true)
            ) {
                totalVariances += amount
                if (timestamp.after(monthStart)) monthVariances += amount
            }
        }

        // --- 3. Calculate Final Display Values based on your new logic ---
        val outstandingLiability = (totalShortages + totalVariances) - totalPaid
        val netMonthLiability = (monthShortages + monthVariances) - monthPaid

        return UserReportData(
            userName = userEmail.split("@").firstOrNull()?.replaceFirstChar { it.titlecase() } ?: "Unknown User",
            userEmail = userEmail,
            outstandingLiability = outstandingLiability,
            variances = totalVariances, // Display the grand total of all variances
            todayShortage = todayShortage,
            todayPaid = todayPaid,
            monthShortages = netMonthLiability, // The "monthly shortage" field now shows the net monthly debt
            monthPaid = monthPaid // This correctly shows total paid for the month
        )
    }

    private fun getStartOfDay(date: Date): Date = Calendar.getInstance().apply { time = date; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.time

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
