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
import androidx.recyclerview.widget.LinearLayoutManager // Make sure this is imported
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
import java.util.UUID

class expenses : Fragment() {

    private var _binding: FragmentExpensesBinding? = null
    private val binding get() = _binding!!

    private val expenseList = mutableListOf<Expense>()
    private lateinit var expenseAdapter: ExpenseAdapter
    private var isAdmin = false
    private val SPREADSHEET_NAME = "nia-bridge data"
    private val EXPENSES_SHEET_NAME = "Expenses"
    private val ADMIN_EMAIL = "your_admin_email@example.com" // IMPORTANT: Change this!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentExpensesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Set the isAdmin flag first
        val currentUserEmail = GoogleSignIn.getLastSignedInAccount(requireContext())?.email
        isAdmin = currentUserEmail == ADMIN_EMAIL
        Log.d("ExpensesFragment", "Current user: $currentUserEmail, isAdmin: $isAdmin")

        // Now, set up the RecyclerView using the correct pattern
        setupRecyclerView()
        fetchExpensesFromSheet()

        binding.addExpense.setOnClickListener {
            showAddExpenseDialog()
        }
    }

    private fun setupRecyclerView() {
        // ✅ FIX: Get the current user's email here, inside this function's scope.
        val currentUserEmail = GoogleSignIn.getLastSignedInAccount(requireContext())?.email ?: ""

        // Now you can pass it to the adapter's constructor without an error.
        expenseAdapter = ExpenseAdapter(expenseList, currentUserEmail, isAdmin) { expenseToDelete ->
            // This is the code that runs when the delete button is clicked.
            showDeleteConfirmationDialog(expenseToDelete)
        }


        binding.expenseEntry.apply {
            // Set the layout manager and adapter in the same `apply` block.
            layoutManager = LinearLayoutManager(requireContext())
            adapter = expenseAdapter
        }
    }

    private fun showAddExpenseDialog() {
        // Corrected typo is important for the dialog to show up
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.expenses_entry_dialog, null)

        val itemEt: EditText = dialogView.findViewById(R.id.edit_text_expense_item)
        val descEt: EditText = dialogView.findViewById(R.id.edit_text_expense_description)
        val qtyEt: EditText = dialogView.findViewById(R.id.edit_text_expense_qty)
        val amountEt: EditText = dialogView.findViewById(R.id.edit_text_expense_amount)
        val permitRg: RadioGroup = dialogView.findViewById(R.id.radio_group_permit)

        AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setPositiveButton("Add") { _, _ ->
                val item = itemEt.text.toString().trim()
                val qty = qtyEt.text.toString().toDoubleOrNull()
                val amount = amountEt.text.toString().toDoubleOrNull()

                if (item.isEmpty() || qty == null || amount == null) {
                    Toast.makeText(requireContext(), "Item, Quantity, and Amount are required.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val checkedRadioButtonId = permitRg.checkedRadioButtonId
                val permitRadioButton: RadioButton = permitRg.findViewById(checkedRadioButtonId)
                val permit = permitRadioButton.text.toString().toBoolean()

                val newExpense = Expense(
                    uniqueId = UUID.randomUUID().toString(),
                    item = item,
                    description = descEt.text.toString().trim(),
                    quantity = qty,
                    amount = amount,
                    permit = permit,
                    user = GoogleSignIn.getLastSignedInAccount(requireContext())?.email ?: "unknown"
                )
                addExpenseToSheet(newExpense)
            }
            .setNegativeButton("Cancel", null)
            .show()
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

    //<editor-fold desc="Google Sheets API Functions">

    private fun fetchExpensesFromSheet() {
        val progressDialog = ProgressDialog(requireContext()).apply { setMessage("Loading Expenses..."); setCancelable(false); show() }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val account = getSignedInAccount()
                val sheetsService = getSheetsService(account)
                val spreadsheetId = findSheetIdByName(getDriveService(account), SPREADSHEET_NAME)
                    ?: throw Exception("Spreadsheet not found.")

                ensureExpensesSheetExists(sheetsService, spreadsheetId)

                val response = sheetsService.spreadsheets().values().get(spreadsheetId, "$EXPENSES_SHEET_NAME!A2:G").execute()
                val values = response.getValues() ?: emptyList()
                Log.d("ExpensesFragment", "Fetched ${values.size} rows from Google Sheet.")

                val tempList = mutableListOf<Expense>()
                values.forEach { row ->
                    tempList.add(Expense(
                        uniqueId = row.getOrNull(0)?.toString() ?: "",
                        item = row.getOrNull(1)?.toString() ?: "",
                        description = row.getOrNull(2)?.toString() ?: "",
                        quantity = row.getOrNull(3)?.toString()?.toDoubleOrNull() ?: 0.0,
                        amount = row.getOrNull(4)?.toString()?.toDoubleOrNull() ?: 0.0,
                        permit = row.getOrNull(5)?.toString()?.toBoolean() ?: false,
                        user = row.getOrNull(6)?.toString() ?: ""
                    ))
                }

                withContext(Dispatchers.Main) {
                    expenseList.clear()
                    expenseList.addAll(tempList)
                    expenseAdapter.notifyDataSetChanged()
                    Log.d("ExpensesFragment", "Adapter notified with ${expenseList.size} items.")

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

                val newRow = listOf(listOf(
                    expense.uniqueId, expense.item, expense.description, expense.quantity, expense.amount, expense.permit, expense.user
                ))
                val body = ValueRange().setValues(newRow)

                sheetsService.spreadsheets().values()
                    .append(spreadsheetId, "$EXPENSES_SHEET_NAME!A1", body)
                    .setValueInputOption("USER_ENTERED")
                    .execute()

                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Expense added successfully", Toast.LENGTH_SHORT).show()
                    fetchExpensesFromSheet() // Refresh the list
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

                val deleteRequest = Request().setDeleteDimension(DeleteDimensionRequest()
                    .setRange(DimensionRange()
                        .setSheetId(sheetId)
                        .setDimension("ROWS")
                        .setStartIndex(rowIndexToDelete)
                        .setEndIndex(rowIndexToDelete + 1)
                    )
                )

                val batchUpdateRequest = BatchUpdateSpreadsheetRequest().setRequests(listOf(deleteRequest))
                sheetsService.spreadsheets().batchUpdate(spreadsheetId, batchUpdateRequest).execute()

                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Expense deleted.", Toast.LENGTH_SHORT).show()
                    fetchExpensesFromSheet() // Refresh the list
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
            val addSheetRequest = Request().setAddSheet(AddSheetRequest().setProperties(SheetProperties().setTitle(EXPENSES_SHEET_NAME)))
            val batchUpdateRequest = BatchUpdateSpreadsheetRequest().setRequests(listOf(addSheetRequest))
            sheetsService.spreadsheets().batchUpdate(spreadsheetId, batchUpdateRequest).execute()

            // Add headers
            val headers = listOf(listOf("UniqueId", "Item", "Description", "Quantity", "Amount", "Permit", "User"))
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
    //</editor-fold>

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
