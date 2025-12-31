package com.example.zed

import android.app.ProgressDialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.zed.databinding.FragmentExpensesBinding
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
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
import okhttp3.*
import org.json.JSONArray
import org.json.JSONException
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class expenses : Fragment() {

    private var _binding: FragmentExpensesBinding? = null
    private val binding get() = _binding!!

    private val expenseList = mutableListOf<Expense>()
    private lateinit var expenseAdapter: ExpenseAdapter
    private var isAdmin = false // Default to false until role is confirmed
    private val SPREADSHEET_NAME = "nia-bridge data"
    private val EXPENSES_SHEET_NAME = "Expenses"

    companion object {
        fun newInstance(): expenses {
            return expenses()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentExpensesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()

        val currentUserEmail = GoogleSignIn.getLastSignedInAccount(requireContext())?.email
        if (currentUserEmail == null) {
            Toast.makeText(requireContext(), "Cannot verify user. Please sign in.", Toast.LENGTH_LONG).show()
            return
        }

        checkUserRole(currentUserEmail) { isAdminResult, _, _ ->
            this.isAdmin = isAdminResult
            Log.d("ExpensesFragment", "Role check complete. User is admin: $isAdmin")
            setupRecyclerView()
            fetchExpensesFromSheet()
        }

        binding.addExpense.setOnClickListener {
            showAddExpenseDialog()
        }
    }

    private fun setupRecyclerView() {
        val currentUserEmail = GoogleSignIn.getLastSignedInAccount(requireContext())?.email ?: ""
        expenseAdapter = ExpenseAdapter(expenseList, currentUserEmail, isAdmin) { expenseToDelete ->
            showDeleteConfirmationDialog(expenseToDelete)
        }

        binding.expenseEntry.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = expenseAdapter
        }
    }

    // In expenses.kt

    private fun showAddExpenseDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.expenses_entry_dialog, null)

        // ... (finding other views)
        val itemEt: EditText = dialogView.findViewById(R.id.edit_text_expense_item)
        val descEt: EditText = dialogView.findViewById(R.id.edit_text_expense_description)
        val qtyEt: EditText = dialogView.findViewById(R.id.edit_text_expense_qty)
        val amountEt: EditText = dialogView.findViewById(R.id.edit_text_expense_amount)
        val permitRg: RadioGroup = dialogView.findViewById(R.id.radio_group_permit)
        val permitLabel: View? = dialogView.findViewById(R.id.permit_label)

        if (isAdmin) {
            permitRg.visibility = View.VISIBLE
            permitLabel?.visibility = View.VISIBLE
        } else {
            permitRg.visibility = View.GONE
            permitLabel?.visibility = View.GONE
        }

        AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setPositiveButton("Add") { _, _ ->
                // ... (getting item, qty, amount)

                var permit = false // Default to false
                if (isAdmin) {
                    val checkedRadioButtonId = permitRg.checkedRadioButtonId
                    if (checkedRadioButtonId != -1) {
                        val selectedRadioButton: RadioButton = permitRg.findViewById(checkedRadioButtonId)

                        // ✅ THIS IS THE FIX
                        // Check for the text "True" instead of "Yes"
                        if (selectedRadioButton.text.toString().equals("True", ignoreCase = true)) {
                            permit = true
                        }
                    }
                }

                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                val currentTime = sdf.format(Date())

                // Create the newExpense object with the now-correct 'permit' value
                val newExpense = Expense(
                    uniqueId = UUID.randomUUID().toString(),
                    item = itemEt.text.toString().trim(),
                    description = descEt.text.toString().trim(),
                    quantity = qtyEt.text.toString().toDoubleOrNull() ?: 0.0,
                    amount = amountEt.text.toString().toDoubleOrNull() ?: 0.0,
                    permit = permit, // This will now be correct
                    user = GoogleSignIn.getLastSignedInAccount(requireContext())?.email ?: "unknown",
                    timestamp = currentTime,
                    approvalTimestamp = if (permit) currentTime else ""
                )
                addExpenseToSheet(newExpense)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }


    private fun checkUserRole(email: String, callback: (isAdmin: Boolean, exists: Boolean, parentEmail: String?) -> Unit) {
        val client = OkHttpClient()
        val request = okhttp3.Request.Builder()
            .url("https://opensheet.elk.sh/1W-LOkSgPPrfhZ_kqfycUvOcGviQplMng6xpc6KBQ9Ik/users")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                activity?.runOnUiThread { callback(false, false, null) }
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
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
                        Log.e("checkUserRole", "Error parsing JSON from user sheet", e)
                        activity?.runOnUiThread { callback(false, false, null) }
                    }
                }
                if (!isFound) {
                    activity?.runOnUiThread { callback(false, false, null) }
                }
            }
        })
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

    private fun fetchExpensesFromSheet() {
        val progressDialog = ProgressDialog(requireContext()).apply { setMessage("Loading Expenses..."); setCancelable(false); show() }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val account = getSignedInAccount()
                val sheetsService = getSheetsService(account)
                val spreadsheetId = findSheetIdByName(getDriveService(account), SPREADSHEET_NAME)
                    ?: throw Exception("Spreadsheet not found.")

                ensureExpensesSheetExists(sheetsService, spreadsheetId)

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
                    expenseList.clear()
                    expenseList.addAll(tempList)
                    expenseAdapter.notifyDataSetChanged()

                    if (expenseList.isEmpty()) {
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

    private fun addExpenseToSheet(expense: Expense) {
        val progressDialog = ProgressDialog(requireContext()).apply { setMessage("Adding Expense..."); setCancelable(false); show() }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val account = getSignedInAccount()
                val sheetsService = getSheetsService(account)
                val spreadsheetId = findSheetIdByName(getDriveService(account), SPREADSHEET_NAME)
                    ?: throw Exception("Spreadsheet not found.")

                val newRow = listOf(
                    listOf(
                        expense.uniqueId, expense.item, expense.description, expense.quantity, expense.amount,
                        expense.permit, expense.user, expense.timestamp, expense.approvalTimestamp
                    )
                )
                val body = ValueRange().setValues(newRow)

                sheetsService.spreadsheets().values()
                    .append(spreadsheetId, "$EXPENSES_SHEET_NAME!A1", body)
                    .setValueInputOption("USER_ENTERED")
                    .execute()

                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Expense added successfully", Toast.LENGTH_SHORT).show()
                    fetchExpensesFromSheet()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e("AddExpenseError", "Error adding expense", e)
                    Toast.makeText(context, "Error adding expense: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(Dispatchers.Main) { if (progressDialog.isShowing) progressDialog.dismiss() }
            }
        }
    }

    private fun deleteExpenseFromSheet(expense: Expense) {
        val progressDialog = ProgressDialog(requireContext()).apply { setMessage("Deleting Expense..."); setCancelable(false); show() }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val account = getSignedInAccount()
                val sheetsService = getSheetsService(account)
                val spreadsheetId = findSheetIdByName(getDriveService(account), SPREADSHEET_NAME)
                    ?: throw Exception("Spreadsheet not found.")

                val sheetId = getSheetIdByTitle(sheetsService, spreadsheetId, EXPENSES_SHEET_NAME)
                    ?: throw Exception("'$EXPENSES_SHEET_NAME' sheet not found.")

                val response = sheetsService.spreadsheets().values().get(spreadsheetId, "$EXPENSES_SHEET_NAME!A:A").execute()
                val values = response.getValues() ?: emptyList()
                val rowIndexToDelete = values.indexOfFirst { it.isNotEmpty() && it[0] == expense.uniqueId }

                if (rowIndexToDelete == -1) throw Exception("Expense not found in sheet.")

                val deleteRequest = com.google.api.services.sheets.v4.model.Request().setDeleteDimension(
                    DeleteDimensionRequest()
                        .setRange(DimensionRange().setSheetId(sheetId).setDimension("ROWS").setStartIndex(rowIndexToDelete).setEndIndex(rowIndexToDelete + 1))
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

    private suspend fun ensureExpensesSheetExists(sheetsService: Sheets, spreadsheetId: String) {
        val spreadsheet = sheetsService.spreadsheets().get(spreadsheetId).execute()
        if (spreadsheet.sheets.none { it.properties.title == EXPENSES_SHEET_NAME }) {
            val addSheetRequest = com.google.api.services.sheets.v4.model.Request()
                .setAddSheet(AddSheetRequest().setProperties(SheetProperties().setTitle(EXPENSES_SHEET_NAME)))

            val batchUpdateRequest = BatchUpdateSpreadsheetRequest().setRequests(listOf(addSheetRequest))
            sheetsService.spreadsheets().batchUpdate(spreadsheetId, batchUpdateRequest).execute()

            val headers = listOf(listOf("UniqueId", "Item", "Description", "Quantity", "Amount", "Permit", "User", "Timestamp", "Approval Timestamp"))
            val headerBody = ValueRange().setValues(headers)
            sheetsService.spreadsheets().values()
                .update(spreadsheetId, "$EXPENSES_SHEET_NAME!A1", headerBody)
                .setValueInputOption("USER_ENTERED")
                .execute()
        }
    }

    private suspend fun findSheetIdByName(driveService: Drive, name: String): String? = withContext(Dispatchers.IO) {
        driveService.files().list().setQ("name='$name' and mimeType='application/vnd.google-apps.spreadsheet' and trashed=false").setFields("files(id)").execute().files.firstOrNull()?.id
    }

    private suspend fun getSheetIdByTitle(sheetsService: Sheets, spreadsheetId: String, sheetTitle: String): Int? = withContext(Dispatchers.IO) {
        sheetsService.spreadsheets().get(spreadsheetId).execute().sheets.find { it.properties.title == sheetTitle }?.properties?.sheetId
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
