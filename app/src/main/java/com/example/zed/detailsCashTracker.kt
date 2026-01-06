package com.example.zed

import android.app.ProgressDialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.zed.databinding.FragmentDetailsCashTrackerBinding
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.SheetsScopes
import com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetRequest
import com.google.api.services.sheets.v4.model.DeleteDimensionRequest
import com.google.api.services.sheets.v4.model.DimensionRange
import com.google.api.services.sheets.v4.model.ValueRange // ✅ CORRECT ValueRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback // ✅ CORRECT Callback
import okhttp3.OkHttpClient
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class detailsCashTracker : Fragment() {
    private lateinit var expenseDetailAdapter: expenseDetailAdapter
    private var _binding: FragmentDetailsCashTrackerBinding? = null
    private val binding get() = _binding!!

    private val allExpensesList = mutableListOf<Expense>()
    private val filteredExpensesList = mutableListOf<Expense>()

    private var isAdmin = false
    private val SPREADSHEET_NAME = "nia-bridge data"
    private val EXPENSES_SHEET_NAME = "Expenses"

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDetailsCashTrackerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupFilterSpinner()
        setupRecyclerView()

        val currentUserEmail = GoogleSignIn.getLastSignedInAccount(requireContext())?.email
        if (currentUserEmail != null) {
            checkUserRole(currentUserEmail) { isAdminResult, _, _ ->
                this.isAdmin = isAdminResult
                setupRecyclerView()
                fetchExpensesFromSheet()
            }
        } else {
            fetchExpensesFromSheet()
        }
    }

    private fun setupFilterSpinner() {
        val filterOptions = arrayOf("All", "Today", "This Week", "This Month")
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, filterOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.filter.adapter = adapter

        binding.filter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                filterAndDisplayExpenses()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                // Do nothing
            }
        }
    }

    private fun setupRecyclerView() {
        val currentUserEmail = GoogleSignIn.getLastSignedInAccount(requireContext())?.email ?: ""
        expenseDetailAdapter = expenseDetailAdapter(
            expenses = filteredExpensesList,
            currentEmail = currentUserEmail,
            isAdmin = this.isAdmin,
            onDeleteClick = { expenseToDelete ->
                showDeleteConfirmationDialog(expenseToDelete)
            },
            onPermitStatusChange = { expense, newStatus ->
                updatePermitStatusInSheet(expense, newStatus)
            }
        )

        binding.expenseEntry.apply {
            layoutManager = LinearLayoutManager(requireContext())
            this.adapter = expenseDetailAdapter
        }
    }

    private fun fetchExpensesFromSheet() {
        val progressDialog = ProgressDialog(requireContext()).apply { setMessage("Loading Expenses..."); setCancelable(false); show() }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val account = getSignedInAccount()
                val sheetsService = getSheetsService(account)
                val spreadsheetId = findSheetIdByName(getDriveService(account), SPREADSHEET_NAME)
                    ?: throw Exception("Spreadsheet not found.")

                // This part of your code seems to be missing ensureExpensesSheetExists
                // I'll assume it exists elsewhere or add it if needed.

                val response = sheetsService.spreadsheets().values().get(spreadsheetId, "$EXPENSES_SHEET_NAME!A2:I").execute()
                val values = response.getValues() ?: emptyList()

                val tempList = mutableListOf<Expense>()
                values.forEach { row ->
                    tempList.add(
                        Expense(
                            uniqueId = row.getOrNull(0)?.toString() ?: "",
                            item = row.getOrNull(1)?.toString() ?: "",
                            description = row.getOrNull(2)?.toString() ?: "",
                            quantity = row.getOrNull(3)?.toString()?.toDoubleOrNull() ?: 0.0,
                            amount = row.getOrNull(4)?.toString()?.toDoubleOrNull() ?: 0.0,
                            permit = row.getOrNull(5)?.toString()?.toBoolean() ?: false,
                            user = row.getOrNull(6)?.toString() ?: "",
                            timestamp = row.getOrNull(7)?.toString() ?: "",
                            approvalTimestamp = row.getOrNull(8)?.toString() ?: ""
                        )
                    )
                }

                withContext(Dispatchers.Main) {
                    allExpensesList.clear()
                    allExpensesList.addAll(tempList.sortedByDescending { it.timestamp }) // Sort by newest first
                    filterAndDisplayExpenses()

                    if (allExpensesList.isEmpty()) {
                        Toast.makeText(requireContext(), "No expenses found.", Toast.LENGTH_SHORT).show()
                    }
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e("FetchExpensesError", "Error fetching expenses", e)
                    Toast.makeText(context, "Error fetching expenses: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(Dispatchers.Main) { if (progressDialog.isShowing) progressDialog.dismiss() }
            }
        }
    }

    private fun filterAndDisplayExpenses() {
        val selectedFilter = binding.filter.selectedItem.toString()
        val calendar = Calendar.getInstance()
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        val results = when (selectedFilter) {
            "Today" -> {
                val todayStart = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.time
                allExpensesList.filter {
                    try {
                        val expenseDate = sdf.parse(it.timestamp)
                        expenseDate != null && !expenseDate.before(todayStart)
                    } catch (e: Exception) { false }
                }
            }
            "This Week" -> {
                val weekStart = Calendar.getInstance().apply {
                    firstDayOfWeek = Calendar.SUNDAY // Or MONDAY, depending on your locale
                    set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                }.time
                allExpensesList.filter {
                    try {
                        val expenseDate = sdf.parse(it.timestamp)
                        expenseDate != null && !expenseDate.before(weekStart)
                    } catch (e: Exception) { false }
                }
            }
            "This Month" -> {
                val monthStart = Calendar.getInstance().apply {
                    set(Calendar.DAY_OF_MONTH, 1)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                }.time
                allExpensesList.filter {
                    try {
                        val expenseDate = sdf.parse(it.timestamp)
                        expenseDate != null && !expenseDate.before(monthStart)
                    } catch (e: Exception) { false }
                }
            }
            else -> { // "All"
                allExpensesList
            }
        }

        filteredExpensesList.clear()
        filteredExpensesList.addAll(results)
        expenseDetailAdapter.notifyDataSetChanged()
    }

    private fun updatePermitStatusInSheet(expense: Expense, newStatus: Boolean) {
        val progressDialog = ProgressDialog(requireContext()).apply { setMessage("Updating status..."); setCancelable(false); show() }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val account = getSignedInAccount()
                val sheetsService = getSheetsService(account)
                val spreadsheetId = findSheetIdByName(getDriveService(account), SPREADSHEET_NAME)
                    ?: throw Exception("Spreadsheet not found.")

                val searchRange = "$EXPENSES_SHEET_NAME!A2:I"
                val response = sheetsService.spreadsheets().values().get(spreadsheetId, searchRange).execute()
                val values = response.getValues() ?: emptyList()

                val dataIndex = values.indexOfFirst { it.isNotEmpty() && it.getOrNull(0) == expense.uniqueId }

                if (dataIndex == -1) {
                    throw Exception("Expense with ID ${expense.uniqueId} not found in sheet to update.")
                }

                val rowIndexToUpdate = dataIndex + 2

                // ✅ Use the correct ValueRange from the Google Sheets API
                val valueRange = ValueRange().setValues(listOf(listOf(newStatus)))
                sheetsService.spreadsheets().values()
                    .update(spreadsheetId, "$EXPENSES_SHEET_NAME!F$rowIndexToUpdate", valueRange)
                    .setValueInputOption("USER_ENTERED")
                    .execute()

                val timestamp = if (newStatus) SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()) else ""
                val timestampValueRange = ValueRange().setValues(listOf(listOf(timestamp)))
                sheetsService.spreadsheets().values()
                    .update(spreadsheetId, "$EXPENSES_SHEET_NAME!I$rowIndexToUpdate", timestampValueRange)
                    .setValueInputOption("USER_ENTERED")
                    .execute()

                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Status updated successfully", Toast.LENGTH_SHORT).show()
                    fetchExpensesFromSheet()
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e("UpdatePermitError", "Error updating status", e)
                    Toast.makeText(context, "Error updating status: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    if (progressDialog.isShowing) progressDialog.dismiss()
                }
            }
        }
    }

    private fun showDeleteConfirmationDialog(expense: Expense) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete Expense")
            .setMessage("Are you sure you want to delete the expense: ${expense.item}?")
            .setPositiveButton("Delete") { _, _ ->
                deleteExpenseFromSheet(expense)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteExpenseFromSheet(expense: Expense) {
        val progressDialog = ProgressDialog(requireContext()).apply { setMessage("Deleting Expense..."); setCancelable(false); show() }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val account = getSignedInAccount()
                val sheetsService = getSheetsService(account)
                val spreadsheetId = findSheetIdByName(getDriveService(account), SPREADSHEET_NAME) ?: throw Exception("Spreadsheet not found.")

                val sheetResponse = sheetsService.spreadsheets().get(spreadsheetId).execute()
                val sheetId = sheetResponse.sheets.find { it.properties.title == EXPENSES_SHEET_NAME }?.properties?.sheetId ?: throw Exception("Sheet not found")

                val valuesResponse = sheetsService.spreadsheets().values().get(spreadsheetId, "$EXPENSES_SHEET_NAME!A2:A").execute()
                val values = valuesResponse.getValues() ?: emptyList()
                val rowIndexToDelete = values.indexOfFirst { it.isNotEmpty() && it[0] == expense.uniqueId }

                if (rowIndexToDelete == -1) throw Exception("Expense not found in sheet.")

                val deleteRequest = com.google.api.services.sheets.v4.model.Request().setDeleteDimension(
                    DeleteDimensionRequest()
                        .setRange(DimensionRange().setSheetId(sheetId).setDimension("ROWS").setStartIndex(rowIndexToDelete + 1).setEndIndex(rowIndexToDelete + 2))
                )

                val batchUpdateRequest = BatchUpdateSpreadsheetRequest().setRequests(listOf(deleteRequest))
                sheetsService.spreadsheets().batchUpdate(spreadsheetId, batchUpdateRequest).execute()

                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Expense deleted.", Toast.LENGTH_SHORT).show()
                    fetchExpensesFromSheet()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e("DeleteExpenseError", "Error deleting expense", e)
                    Toast.makeText(context, "Error deleting expense: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(Dispatchers.Main) { if (progressDialog.isShowing) progressDialog.dismiss() }
            }
        }
    }


    private fun getSignedInAccount(): GoogleSignInAccount {
        return GoogleSignIn.getLastSignedInAccount(requireContext())
            ?: throw IllegalStateException("User is not signed in.")
    }

    private fun getDriveService(account: GoogleSignInAccount): Drive {
        val credential = GoogleAccountCredential.usingOAuth2(requireContext(), listOf(DriveScopes.DRIVE)).setBackOff(com.google.api.client.util.ExponentialBackOff()).apply { selectedAccountName = account.email }
        return Drive.Builder(GoogleNetHttpTransport.newTrustedTransport(), GsonFactory.getDefaultInstance(), credential).setApplicationName("Nia Bridge App").build()
    }

    private fun getSheetsService(account: GoogleSignInAccount): Sheets {
        val credential = GoogleAccountCredential.usingOAuth2(requireContext(), listOf(SheetsScopes.SPREADSHEETS)).setBackOff(com.google.api.client.util.ExponentialBackOff()).apply { selectedAccountName = account.email }
        return Sheets.Builder(GoogleNetHttpTransport.newTrustedTransport(), GsonFactory.getDefaultInstance(), credential).setApplicationName("Nia Bridge App").build()
    }

    private suspend fun findSheetIdByName(driveService: Drive, name: String): String? =
        withContext(Dispatchers.IO) {
            driveService.files().list().setQ("name='$name' and mimeType='application/vnd.google-apps.spreadsheet' and trashed=false").setFields("files(id)").execute().files.firstOrNull()?.id
        }

    private fun checkUserRole(email: String, callback: (isAdmin: Boolean, exists: Boolean, parentEmail: String?) -> Unit) {
        val client = OkHttpClient()
        val request = okhttp3.Request.Builder()
            .url("https://opensheet.elk.sh/1W-LOkSgPPrfhZ_kqfycUvOcGviQplMng6xpc6KBQ9Ik/users")
            .build()
        // ✅ Use the correct okhttp3.Callback
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                activity?.runOnUiThread { callback(false, false, null) }
            }

            override fun onResponse(call: Call, response: Response) {
                var isFound = false
                if (response.isSuccessful) {
                    try {
                        val jsonArray = JSONArray(response.body?.string() ?: "")
                        for (i in 0 until jsonArray.length()) {
                            val obj = jsonArray.getJSONObject(i)
                            val mainEmail = obj.optString("email").trim()
                            val subEmail = obj.optString("email sub user").trim()
                            val adminFlag = obj.optString("admin") == "1"
                            if (email.equals(mainEmail, ignoreCase = true)) {
                                activity?.runOnUiThread { callback(adminFlag, true, null) }
                                isFound = true; break
                            }
                            if (subEmail.isNotEmpty() && email.equals(subEmail, ignoreCase = true)) {
                                activity?.runOnUiThread { callback(false, true, mainEmail) }
                                isFound = true; break
                            }
                        }
                    } catch (e: JSONException) {
                        Log.e("checkUserRole", "Error parsing JSON", e)
                        activity?.runOnUiThread { callback(false, false, null) }
                    }
                }
                if (!isFound) {
                    activity?.runOnUiThread { callback(false, false, null) }
                }
            }
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
