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
        adapter = UserReportAdapter(emptyList(), false) { userEmail, amount ->
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

                val sheetsService = getSheetsService(googleAccount)
                val spreadsheetId = findSheetIdByName(getDriveService(googleAccount), "nia-bridge data")
                    ?: throw IOException("Spreadsheet 'nia-bridge data' not found.")

                val rangesToFetch = listOf("Closing Balance!A:H", "Expenses!A:I", "stock_taking!A:H")
                val batchData = sheetsService.spreadsheets().values().batchGet(spreadsheetId).setRanges(rangesToFetch).execute()

                val closingBalanceValues = batchData.valueRanges.getOrNull(0)?.getValues()?.drop(1) ?: emptyList()
                val expenseValues = batchData.valueRanges.getOrNull(1)?.getValues()?.drop(1) ?: emptyList()
                val stockTakingValues = batchData.valueRanges.getOrNull(2)?.getValues()?.drop(1) ?: emptyList()

                val reports = mutableListOf<UserReportData>()
                val userList = if (isAdmin) allUsers else (currentUser?.let { listOf(it) } ?: emptyList())

                for (user in userList) {
                    reports.add(processUserData(user.email, closingBalanceValues, expenseValues, stockTakingValues))
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

    // ✅✅✅ --- FINAL, CORRECTED LOGIC --- ✅✅✅
    private fun processUserData(
        userEmail: String,
        closingBalanceData: List<List<Any>>,
        expensesData: List<List<Any>>,
        stockTakingData: List<List<Any>>
    ): UserReportData {

        val calendar = Calendar.getInstance()
        val todayStart = getStartOfDay(calendar.time)
        val monthStart = getStartOfDay(calendar.apply { set(Calendar.DAY_OF_MONTH, 1) }.time)

        // --- 1. NET Cash Liability (All-Time and This Month) ---
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

        // --- 2. Payments (All-Time and This Month) ---
        var monthPaid = 0.0
        var totalPaid = 0.0

        for (row in expensesData) {
            val description = row.getOrNull(2)?.toString() ?: ""
            if (description.equals("Payment to $userEmail", ignoreCase = true)) {
                val timestamp = parseDateString(row.getOrNull(7)?.toString()) ?: continue
                val amount = row.getOrNull(4)?.toString()?.toDoubleOrNull() ?: 0.0
                totalPaid += amount
                if (timestamp.after(monthStart)) monthPaid += amount
            }
        }

        // --- 3. NET Stock Variance Liability (All-Time and This Month) ---
        var netTotalStockVarianceLiability = 0.0
        var netMonthStockVarianceLiability = 0.0
        var monthPositiveVariances = 0.0 // For display purposes

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
                    -cost // A deficit (-ve in sheet) becomes a debt (+ve), a surplus (+ve in sheet) becomes a credit (-ve).
                }

                netTotalStockVarianceLiability += netVarianceForTimestamp

                if (timestamp.after(monthStart)) {
                    netMonthStockVarianceLiability += netVarianceForTimestamp

                    // Separately, calculate the sum of only positive variances (surpluses) for display
                    val positiveSurplusThisMonth = rows.sumOf {
                        val cost = it.getOrNull(6)?.toString()?.toDoubleOrNull() ?: 0.0
                        if (cost > 0) cost else 0.0
                    }
                    monthPositiveVariances += positiveSurplusThisMonth
                }
            }
        }

        // --- 4. Final Calculation ---
        val outstandingLiability = (netTotalCashLiability + netTotalStockVarianceLiability) - totalPaid
        val netMonthLiability = (netMonthCashLiability + netMonthStockVarianceLiability) - monthPaid

        return UserReportData(
            userName = userEmail.split("@").firstOrNull()?.replaceFirstChar { it.titlecase() } ?: "Unknown User",
            userEmail = userEmail,
            outstandingLiability = outstandingLiability,
            variances = netTotalStockVarianceLiability,
            todayShortage = netTodayCashLiability,
            monthShortages = netMonthLiability,
            monthPaid = monthPaid,
            monthPositiveVariances = monthPositiveVariances
        )
    }

    private fun getStartOfDay(date: Date): Date = Calendar.getInstance().apply { time = date; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.time

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
