package com.example.zed

import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import coil.transform.CircleCropTransformation
import com.example.zed.databinding.DialogAddUserBinding
import com.example.zed.databinding.DialogManageUsersBinding
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONException
import java.io.IOException

// This data class should be outside the dashboardActivity class
data class UserRole(
    val email: String,
    val isSubUser: Boolean = false,
    val roles: Map<String, Boolean>
)

class dashboardActivity : AppCompatActivity() {

    private lateinit var firebaseAuth: FirebaseAuth
    private lateinit var googleSignInClient: GoogleSignInClient
    private val TAG = "DashboardActivity"

    // PASTE YOUR CORRECT '/exec' URL HERE
    private val scriptUrl = "https://script.google.com/macros/s/AKfycbzQYviWEzzxMnokoiTdCTBsN7DN_fYhV3CIoqOXkjP-_5et6Ux0RzV8DUp-lkDYHQ9T/exec"

    // Map to hold dashboard buttons for easy access
    private lateinit var dashboardButtons: Map<String, CardView>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_dashboard)
        setupSystemBars()

        firebaseAuth = FirebaseAuth.getInstance()
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        initializeDashboardButtons()

        val profileImage = findViewById<ImageView>(R.id.profileImage)
        val logoutText = findViewById<TextView>(R.id.logoutText)
        val account = GoogleSignIn.getLastSignedInAccount(this)
        account?.photoUrl?.let { url ->
            profileImage.load(url) {
                placeholder(R.drawable.profile_placeholder)
                transformations(CircleCropTransformation())
            }
        }
        logoutText.setOnClickListener { signOut() }

        // ✅ The original setOnClickListener for usersIcon is removed from here

        // Fetch roles for the current user and update the UI
        account?.email?.let {
            checkCurrentUserRoles(it)
        } ?: run {
            // Handle case where user email is not available
            updateDashboardUI(emptyMap(), false) // Disable all buttons and hide admin icons
            Toast.makeText(this, "Could not verify user email. Access denied.", Toast.LENGTH_LONG).show()
        }
    }

    private fun initializeDashboardButtons() {
        dashboardButtons = mapOf(
            "inventory count" to findViewById(R.id.cardView8),
            "stock" to findViewById(R.id.stock_id),
            "purchase requisition" to findViewById(R.id.purchaseRequisition),
            "grn" to findViewById(R.id.goodReceivedNote),
            "cash tracker" to findViewById(R.id.cashTrackerBtn),
            "transaction" to findViewById(R.id.transactions)
        )
    }

    private fun checkCurrentUserRoles(email: String) {
        lifecycleScope.launch {
            try {
                // Use fetchUsersAndRoles which already gets all user data
                val allUsers = fetchUsersAndRoles()
                // Find the current user in the list
                val currentUser = allUsers.firstOrNull { it.email.equals(email, ignoreCase = true) }

                withContext(Dispatchers.Main) {
                    if (currentUser != null) {
                        // An admin is a user who is not a sub-user.
                        val isAdmin = !currentUser.isSubUser
                        updateDashboardUI(currentUser.roles, isAdmin)
                    } else {
                        // User not found in the sheet, treat as no roles and not an admin.
                        updateDashboardUI(emptyMap(), false)
                        Toast.makeText(this@dashboardActivity, "User roles not found.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e(TAG, "Failed to check user roles", e)
                    updateDashboardUI(emptyMap(), false) // Disable all on error
                    Toast.makeText(this@dashboardActivity, "Error fetching roles: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun updateDashboardUI(roles: Map<String, Boolean>, isAdmin: Boolean) {
        // --- Define Button to Activity Mappings ---
        val activityMap = mapOf(
            "inventory count" to PhysicalInventory::class.java,
            "stock" to stockList::class.java,
            "purchase requisition" to purchase_requisition::class.java,
            "grn" to goods_received_note::class.java,
            "cash tracker" to cashTracker::class.java,
            "transaction" to transactions::class.java
        )

        // --- Set colors ---
        val activeColor = ContextCompat.getColor(this, R.color.white)
        val inactiveColor = ContextCompat.getColor(this, R.color.unselected_item_color)

        dashboardButtons.forEach { (roleName, button) ->
            val hasRole = roles[roleName] == true
            if (hasRole) {
                // Enable button
                button.setCardBackgroundColor(activeColor)
                button.isClickable = true
                button.isFocusable = true
                activityMap[roleName]?.let { activityClass ->
                    button.setOnClickListener { startActivity(Intent(this, activityClass)) }
                }
            } else {
                // Disable button
                button.setCardBackgroundColor(inactiveColor)
                button.isClickable = false
                button.isFocusable = false
                button.setOnClickListener(null) // Remove click listener
            }
        }

        // ✅ NEW LOGIC: Show or hide the user management icon based on admin status.
        val usersIcon = findViewById<ImageView>(R.id.users)
        val textView2 = findViewById<TextView>(R.id.textView2)

        if (isAdmin) {
            usersIcon.visibility = View.VISIBLE
            usersIcon.setOnClickListener { showManageUsersDialog() }
        } else {
            usersIcon.visibility = View.GONE
            textView2.visibility = View.GONE
            usersIcon.setOnClickListener(null)
        }
    }

    // --- All other functions remain the same ---

    private fun showManageUsersDialog() {
        val dialogBinding = DialogManageUsersBinding.inflate(layoutInflater)
        val usersRecyclerView = dialogBinding.usersRecyclerView
        usersRecyclerView.layoutManager = LinearLayoutManager(this)

        // A view is needed to post the runnable, the RecyclerView is a good choice.
        val viewForPosting = usersRecyclerView

        val mainDialog = AlertDialog.Builder(this)
            .setTitle("Manage Users")
            .setView(dialogBinding.root)
            .setNegativeButton("Close", null)
            .setPositiveButton("Add User") { _, _ ->
                // Post the action to the message queue. This ensures the current
                // dialog has finished dismissing before the new one is created.
                viewForPosting.post {
                    showAddUserDialog()
                }
            }
            .create()

        val progress = ProgressDialog(this).apply { setMessage("Fetching users..."); show() }
        lifecycleScope.launch {
            try {
                val users = fetchUsersAndRoles()
                withContext(Dispatchers.Main) {
                    progress.dismiss()
                    val adapter = UserRolesAdapter(
                        users,
                        onEditClicked = { user ->
                            // Use the same 'post' pattern for safety
                            viewForPosting.post {
                                mainDialog.dismiss()
                                showEditUserDialog(user)
                            }
                        },
                        onDeleteClicked = { user ->
                            viewForPosting.post {
                                mainDialog.dismiss()
                                showDeleteUserDialog(user)
                            }
                        }
                    )
                    usersRecyclerView.adapter = adapter
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progress.dismiss()
                    Log.e(TAG, "Error fetching users", e)
                    Toast.makeText(this@dashboardActivity, "Error fetching users: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
        mainDialog.show()
    }

    private suspend fun fetchUsersAndRoles(): List<UserRole> = withContext(Dispatchers.IO) {
        val client = okhttp3.OkHttpClient()
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
                val isAdmin = jsonObject.optString("admin", "") == "1"

                // Process the main user entry (Column A), as this is where the 'admin' flag is located.
                if (mainEmail.isNotEmpty()) {
                    val roles = if (isAdmin) {
                        // If they are an admin, grant them all roles.
                        roleKeys.associateWith { true }
                    } else {
                        // Otherwise, assign roles as specified in the sheet.
                        roleKeys.associateWith { key -> jsonObject.optString(key, "") == "1" }
                    }

                    // If this email is already in the map (e.g., as a sub-user),
                    // overwrite it with the more important main user data.
                    userMap[mainEmail] = UserRole(mainEmail, isSubUser = false, roles = roles)
                }

                // Now, process the sub-user entry (Column B)
                if (subUserEmail.isNotEmpty()) {
                    val roles = roleKeys.associateWith { key -> jsonObject.optString(key, "") == "1" }
                    // Only add the sub-user if they don't already exist as a main user (main user data takes precedence)
                    if (!userMap.containsKey(subUserEmail)) {
                        userMap[subUserEmail] = UserRole(subUserEmail, isSubUser = true, roles = roles)
                    }
                }
            }
            // Return the values from the map, which now contains the definitive list.
            return@withContext userMap.values.toList()
        }
    }

    private fun showAddUserDialog() {
        val dialogBinding = DialogAddUserBinding.inflate(layoutInflater)

        AlertDialog.Builder(this)
            .setTitle("Add New Sub-User")
            .setView(dialogBinding.root)
            .setPositiveButton("Add") { _, _ ->
                val newUserEmail = dialogBinding.emailInput.text.toString().trim()
                if (newUserEmail.isNotEmpty() && android.util.Patterns.EMAIL_ADDRESS.matcher(newUserEmail).matches()) {
                    val roles = mapOf(
                        "stock" to dialogBinding.roleStockModule.isChecked,
                        "transaction" to dialogBinding.roleTransactionsModule.isChecked,
                        "grn" to dialogBinding.roleGrn.isChecked,
                        "purchase requisition" to dialogBinding.rolePurchaseRequisition.isChecked,
                        "inventory count" to dialogBinding.rolePhysicalInventory.isChecked,
                        "cash tracker" to dialogBinding.roleCashTracker.isChecked
                    )
                    addUserToSheet(newUserEmail, roles)
                } else {
                    Toast.makeText(this, "Please enter a valid email address.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addUserToSheet(newUserEmail: String, roles: Map<String, Boolean>) {
        val progress = ProgressDialog(this).apply { setMessage("Adding user..."); show() }
        val mainUserEmail = firebaseAuth.currentUser?.email

        if (mainUserEmail == null) {
            progress.dismiss()
            Toast.makeText(this, "Could not identify main user.", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val postBody = org.json.JSONObject().apply {
                    put("action", "addUser")
                    put("mainUserEmail", mainUserEmail)
                    put("newUserEmail", newUserEmail)
                    put("token", "6BntfqAtwMbHlYOkbcwWipRTWKe2") // Your fixed token
                    put("roles", org.json.JSONObject(roles.mapValues { if (it.value) "1" else "" }))
                }
                executePostRequest(postBody.toString(), progress)
            } catch (e: Exception) {
                handleException(e, progress)
            }
        }
    }

    private fun showEditUserDialog(user: UserRole) {
        // This is the corrected version using binding
        val dialogBinding = DialogAddUserBinding.inflate(layoutInflater)

        val checkBoxes = mapOf(
            "stock" to dialogBinding.roleStockModule,
            "transaction" to dialogBinding.roleTransactionsModule,
            "grn" to dialogBinding.roleGrn,
            "purchase requisition" to dialogBinding.rolePurchaseRequisition,
            "inventory count" to dialogBinding.rolePhysicalInventory,
            "cash tracker" to dialogBinding.roleCashTracker
        )

        dialogBinding.emailInput.setText(user.email)
        dialogBinding.emailInput.isEnabled = false
        dialogBinding.emailInput.alpha = 0.5f

        checkBoxes.forEach { (roleKey, checkBox) ->
            checkBox.isChecked = user.roles[roleKey] == true
        }

        AlertDialog.Builder(this)
            .setTitle("Edit User Roles")
            .setView(dialogBinding.root)
            .setPositiveButton("Save Changes") { _, _ ->
                val updatedRoles = checkBoxes.mapValues { it.value.isChecked }
                updateUserInSheet(user.email, updatedRoles)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteUserDialog(user: UserRole) {
        AlertDialog.Builder(this)
            .setTitle("Delete User")
            .setMessage("Are you sure you want to permanently delete the user '${user.email}'?")
            .setPositiveButton("Delete") { _, _ ->
                deleteUserFromSheet(user.email)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateUserInSheet(userEmail: String, roles: Map<String, Boolean>) {
        val progress = ProgressDialog(this).apply { setMessage("Updating user..."); show() }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val postBody = org.json.JSONObject().apply {
                    put("action", "updateUser")
                    put("userEmail", userEmail)
                    put("roles", org.json.JSONObject(roles.mapValues { if (it.value) "1" else "" }))
                }
                executePostRequest(postBody.toString(), progress)
            } catch (e: Exception) {
                handleException(e, progress)
            }
        }
    }

    private fun deleteUserFromSheet(userEmail: String) {
        val progress = ProgressDialog(this).apply { setMessage("Deleting user..."); show() }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val postBody = org.json.JSONObject().apply {
                    put("action", "deleteUser")
                    put("userEmail", userEmail)
                }
                executePostRequest(postBody.toString(), progress)
            } catch (e: Exception) {
                handleException(e, progress)
            }
        }
    }

    private suspend fun executePostRequest(postBodyString: String, progress: ProgressDialog) {
        val client = okhttp3.OkHttpClient()
        val requestBody = okhttp3.RequestBody.create("application/json; charset=utf-8".toMediaTypeOrNull(), postBodyString)
        val request = okhttp3.Request.Builder().url(scriptUrl).post(requestBody).build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) throw IOException("Server error: ${response.code}. $responseBody")

            val jsonResponse = org.json.JSONObject(responseBody)
            if (jsonResponse.optString("status") == "error") {
                throw IOException(jsonResponse.optString("message", "Unknown script error."))
            }

            withContext(Dispatchers.Main) {
                progress.dismiss()
                Toast.makeText(this@dashboardActivity, jsonResponse.getString("message"), Toast.LENGTH_LONG).show()
                showManageUsersDialog() // Refresh dialog on success
            }
        }
    }

    private suspend fun handleException(e: Exception, progress: ProgressDialog) {
        withContext(Dispatchers.Main) {
            progress.dismiss()
            Log.e(TAG, "Sheet operation failed", e)
            Toast.makeText(this@dashboardActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupSystemBars() {
        val brandColor = Color.parseColor("#0071c1")
        val brandColorNavigation = Color.parseColor("#f4f6ff")
        window.statusBarColor = brandColor
        window.navigationBarColor = brandColorNavigation
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = true
    }

    private fun signOut() {
        firebaseAuth.signOut()
        googleSignInClient.signOut().addOnCompleteListener {
            startActivity(Intent(this, signup_dashboard_activity::class.java))
            finish()
        }
    }
}
