package com.example.zed

import android.app.ProgressDialog
import android.content.Intent // ✅ Import Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.zed.databinding.ActivityStockFragmentBinding
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.SheetsScopes
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONArray
import org.json.JSONException
import java.io.IOException

// Import shared data classes
import com.example.zed.Product
import com.example.zed.Location
import com.example.zed.UnitOfMeasure
import com.example.zed.LocationItem

class inventory_fragment : Fragment() {
    private var _binding: ActivityStockFragmentBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // The binding class name should match the fragment's layout file.
        // Assuming it's `activity_stock_fragment.xml` for both fragments for now.
        _binding = ActivityStockFragmentBinding.inflate(inflater, container, false)
        binding.StockListRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        setupClickListeners()
        fetchInventoryData()
        return binding.root
    }

    private fun setupClickListeners() {
        binding.varianceAdd.setOnClickListener {
            // This button might not be needed in the inventory fragment,
            // but the logic is here if you decide to keep it.
            val currentUser = Firebase.auth.currentUser
            if (currentUser?.email == null) {
                Toast.makeText(requireContext(), "Cannot add product: User not signed in.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val userEmail = currentUser.email!!
            val progressDialog = ProgressDialog(requireContext()).apply {
                setMessage("Verifying user role...")
                setCancelable(false)
                show()
            }
            checkUserRole(userEmail) { exists, parentEmail ->
                progressDialog.dismiss()
                if (exists) {
                    MyBottomStockSheet(userEmail, parentEmail) {
                        fetchInventoryData() // Refresh callback
                    }.show(parentFragmentManager, "MyBottomSheet")
                } else {
                    Toast.makeText(requireContext(), "Access denied. User not found in registry.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun checkUserRole(email: String, callback: (exists: Boolean, parentEmail: String?) -> Unit) {
        val client = OkHttpClient()
        val request = Request.Builder()
            .url("https://opensheet.elk.sh/1W-LOkSgPPrfhZ_kqfycUvOcGviQplMng6xpc6KBQ9Ik/users")
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                activity?.runOnUiThread { callback(false, null) }
            }
            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    activity?.runOnUiThread { callback(false, null) }
                    return
                }
                try {
                    val jsonArray = JSONArray(response.body?.string() ?: "")
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        val mainEmail = obj.optString("email").trim()
                        val subEmail = obj.optString("email sub user").trim()
                        if (email.equals(mainEmail, ignoreCase = true)) {
                            activity?.runOnUiThread { callback(true, null) }; return
                        }
                        if (subEmail.isNotEmpty() && email.equals(subEmail, ignoreCase = true)) {
                            activity?.runOnUiThread { callback(true, mainEmail) }; return
                        }
                    }
                    activity?.runOnUiThread { callback(false, null) }
                } catch (e: JSONException) {
                    activity?.runOnUiThread { callback(false, null) }
                }
            }
        })
    }

    private fun fetchInventoryData() {
        val progressDialog = ProgressDialog(requireContext()).apply {
            setMessage("Fetching inventory...")
            setCancelable(false)
            show()
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val account = GoogleSignIn.getLastSignedInAccount(requireContext())
                    ?: throw IllegalStateException("User is not signed in.")
                val sheetsService = getSheetsService(account)
                val driveService = getDriveService(account)
                val spreadsheetId = findSheetIdByName(driveService, "nia-bridge data")
                    ?: throw IllegalStateException("Spreadsheet 'nia-bridge data' not found.")

                // --- 1. Fetch Products ---
                val productsRange = "Products!A2:K"
                val productsResponse = sheetsService.spreadsheets().values().get(spreadsheetId, productsRange).execute()
                val productList = productsResponse.getValues()?.mapNotNull { row ->
                    if (row.size < 4) return@mapNotNull null
                    Product(
                        id = row.getOrNull(0)?.toString() ?: "", name = row.getOrNull(1)?.toString() ?: "",
                        imageUrl = row.getOrNull(2)?.toString() ?: "", barcode = row.getOrNull(3)?.toString() ?: "",
                        categoryId = row.getOrNull(4)?.toString() ?: "", unit = row.getOrNull(5)?.toString() ?: "",
                        caseQty = row.getOrNull(6)?.toString() ?: "0", minOrder = row.getOrNull(7)?.toString() ?: "0",
                        unitCost = row.getOrNull(8)?.toString() ?: "0.00",
                        locationIds = emptyList() // Locations will be handled separately by the InventoryAdapter
                    )
                } ?: emptyList()

                // --- 2. Fetch all Locations ---
                // The InventoryAdapter needs the full list of locations to create its bubbles
                val locationsRange = "product_location!A:D" // Fetch all location data
                val locationsResponse = sheetsService.spreadsheets().values().get(spreadsheetId, locationsRange).execute()
                val allLocations = locationsResponse.getValues()?.mapNotNull { row ->
                    if (row.size < 4) return@mapNotNull null
                    LocationItem(
                        id = row.getOrNull(4)?.toString(),
                        barcode = row.getOrNull(0)?.toString(),
                        aisle = row.getOrNull(1)?.toString(),
                        rack = row.getOrNull(2)?.toString(),
                        shelf = row.getOrNull(3)?.toString(),
                    )
                } ?: emptyList()


                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()

                    // ✅ THIS IS THE FIX: Create and use InventoryAdapter
                    val adapter = InventoryAdapter(
                        // Pass the required lists directly from the fetched productList
                        inventoryBarcodes = productList.map { it.barcode },
                        productNameInventorys = productList.map { it.name },
                        productPriceInventory = productList.map { it.unitCost },
                        ProductImageUrl = productList.map { it.imageUrl },
                        inventoryQuantitys = productList.map { it.caseQty },
                        context = requireContext(),
                        allLocations = allLocations // Pass the complete list of all locations
                    )
                    // Ensure you are setting the adapter on the correct RecyclerView
                    binding.StockListRecyclerView.adapter = adapter
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Log.e("FetchInventoryError", "Error fetching data", e)
                    Toast.makeText(context, "Failed to fetch inventory: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun getDriveService(account: GoogleSignInAccount): Drive {
        val credential = GoogleAccountCredential.usingOAuth2(
            requireContext(),
            listOf(DriveScopes.DRIVE)
        ).apply { selectedAccountName = account.email }
        return Drive.Builder(GoogleNetHttpTransport.newTrustedTransport(), GsonFactory.getDefaultInstance(), credential).setApplicationName("Nia Bridge App").build()
    }

    private fun getSheetsService(account: GoogleSignInAccount): Sheets {
        val credential = GoogleAccountCredential.usingOAuth2(
            requireContext(),
            listOf(SheetsScopes.SPREADSHEETS)
        ).apply { selectedAccountName = account.email }
        return Sheets.Builder(GoogleNetHttpTransport.newTrustedTransport(), GsonFactory.getDefaultInstance(), credential).setApplicationName("Nia Bridge App").build()
    }

    private suspend fun findSheetIdByName(driveService: Drive, name: String): String? = withContext(Dispatchers.IO) {
        val query = "name='$name' and mimeType='application/vnd.google-apps.spreadsheet' and trashed=false"
        val result = driveService.files().list().setQ(query).setSpaces("drive").setCorpus("user").setFields("files(id, owners, shared)").execute()
        result.files.firstOrNull { file -> (file.owners?.any { it.me == true } == true) || (file.shared == true) }?.id
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
