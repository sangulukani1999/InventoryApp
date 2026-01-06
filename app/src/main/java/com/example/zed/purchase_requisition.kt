package com.example.zed

import android.app.ProgressDialog
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.zed.databinding.ActivityPurchaseRequisitionBinding
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.SheetsScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class purchase_requisition : AppCompatActivity() {

    private lateinit var binding: ActivityPurchaseRequisitionBinding
    private lateinit var adapter: PurchaseRequisitionAdapter
    private val requisitionItems = mutableListOf<RequisitionItem>()

    private var isAdmin = false // This should be determined by a role check
    private lateinit var currentUserEmail: String
    private lateinit var googleAccount: GoogleSignInAccount

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPurchaseRequisitionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val account = GoogleSignIn.getLastSignedInAccount(this)
        if (account == null) {
            Toast.makeText(this, "User not signed in!", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        googleAccount = account
        currentUserEmail = account.email!!

        // In a real app, you would get isAdmin from a network call. For now, we'll keep it simple.
        isAdmin = true // Placeholder: You should replace this with a real role check.

        setupRecyclerView()
        fetchProductsFromSheet()
    }

    private fun setupRecyclerView() {
        adapter = PurchaseRequisitionAdapter(requisitionItems, currentUserEmail, isAdmin) {
            // This lambda is called every time a checkbox or quantity changes.
            updateTotalBudget()
        }
        binding.purchaseRequisitionRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.purchaseRequisitionRecyclerView.adapter = adapter
    }

    private fun updateTotalBudget() {
        val total = adapter.calculateTotalBudgetedAmount()
        binding.totalBudgetValue.text = "$${"%.2f".format(total)}"
    }

    /**
     * Fetches the list of all products from the "Products" sheet in Google Sheets.
     */
    private fun fetchProductsFromSheet() {
        val progressDialog = ProgressDialog(this).apply {
            setMessage("Fetching products...")
            setCancelable(false)
            show()
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val driveService = getDriveService(googleAccount)
                val sheetsService = getSheetsService(googleAccount)
                val spreadsheetId = findSheetIdByName(driveService, "nia-bridge data")
                    ?: throw Exception("Spreadsheet 'nia-bridge data' not found.")

                val productsRange = "Products!A2:K" // Assuming products are in this range
                val productsResponse = sheetsService.spreadsheets().values().get(spreadsheetId, productsRange).execute()
                val productValues = productsResponse.getValues()

                val productList = if (productValues.isNullOrEmpty()) {
                    emptyList()
                } else {
                    productValues.mapNotNull { row ->
                        if (row.size < 4) return@mapNotNull null
                        Product(
                            id = row.getOrNull(0)?.toString() ?: "",
                            name = row.getOrNull(1)?.toString() ?: "",
                            imageUrl = row.getOrNull(2)?.toString(),
                            barcode = row.getOrNull(3)?.toString() ?: "",
                            categoryId = row.getOrNull(4)?.toString() ?: "",
                            unit = row.getOrNull(5)?.toString() ?: "",
                            caseQty = row.getOrNull(6)?.toString() ?: "0",
                            minOrder = row.getOrNull(7)?.toString() ?: "0",
                            unitCost = row.getOrNull(8)?.toString() ?: "0.00",
                            locationIds = row.getOrNull(9)?.toString()?.removeSurrounding("['", "']")?.split("', '")?.filter { it.isNotBlank() } ?: emptyList()
                        )
                    }
                }

                val items = productList.map { RequisitionItem(it) }

                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    requisitionItems.clear()
                    requisitionItems.addAll(items)
                    adapter.notifyDataSetChanged()
                    updateTotalBudget() // Initial update
                    if (items.isEmpty()) {
                        Toast.makeText(this@purchase_requisition, "No products found in the sheet.", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Log.e("FetchProducts", "Error fetching products from sheet", e)
                    Toast.makeText(this@purchase_requisition, "Failed to fetch products: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    //<editor-fold desc="Google API Helper Functions">
    private suspend fun findSheetIdByName(driveService: Drive, name: String): String? = withContext(Dispatchers.IO) {
        driveService.files().list().setQ("name='$name' and mimeType='application/vnd.google-apps.spreadsheet' and trashed=false").setFields("files(id)").execute().files.firstOrNull()?.id
    }

    private fun getDriveService(account: GoogleSignInAccount): Drive {
        val credential = GoogleAccountCredential.usingOAuth2(this, listOf(DriveScopes.DRIVE_READONLY)).setBackOff(com.google.api.client.util.ExponentialBackOff()).apply { selectedAccount = account.account }
        return Drive.Builder(GoogleNetHttpTransport.newTrustedTransport(), GsonFactory.getDefaultInstance(), credential).setApplicationName("Nia Bridge App").build()
    }

    private fun getSheetsService(account: GoogleSignInAccount): Sheets {
        val credential = GoogleAccountCredential.usingOAuth2(this, listOf(SheetsScopes.SPREADSHEETS_READONLY)).setBackOff(com.google.api.client.util.ExponentialBackOff()).apply { selectedAccount = account.account }
        return Sheets.Builder(GoogleNetHttpTransport.newTrustedTransport(), GsonFactory.getDefaultInstance(), credential).setApplicationName("Nia Bridge App").build()
    }
    //</editor-fold>
}
