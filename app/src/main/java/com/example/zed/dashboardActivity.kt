package com.example.zed

import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class UserRole(
    val email: String,
    val isSubUser: Boolean = false,
    val roles: Map<String, Boolean>,
    val parentEmail: String? = null
)

class dashboardActivity : AppCompatActivity() {

    private lateinit var firebaseAuth: FirebaseAuth
    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var dashboardButtons: Map<String, CardView>

    private val TAG = "DashboardActivity"

    private val scriptUrl =
        "https://script.google.com/macros/s/AKfycbzQYviWEzzxMnokoiTdCTBsN7DN_fYhV3CIoqOXkjP-_5et6Ux0RzV8DUp-lkDYHQ9T/exec"

    /*
     * One reusable HTTP client.
     *
     * retryOnConnectionFailure helps when the connection temporarily drops.
     * The longer timeouts are useful when Google Apps Script responds slowly.
     */
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val roleKeys = listOf(
        "stock",
        "transaction",
        "grn",
        "purchase requisition",
        "inventory count",
        "cash tracker"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContentView(R.layout.activity_dashboard)

        setupSystemBars()
        setupWindowInsets()
        initializeAuthentication()
        initializeDashboardButtons()
        setupProfile()
        loadCurrentUserPermissions()
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val systemBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
            )

            view.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                systemBars.bottom
            )

            insets
        }
    }

    private fun initializeAuthentication() {
        firebaseAuth = FirebaseAuth.getInstance()

        val googleSignInOptions =
            GoogleSignInOptions.Builder(
                GoogleSignInOptions.DEFAULT_SIGN_IN
            )
                .requestIdToken(
                    getString(R.string.default_web_client_id)
                )
                .requestEmail()
                .build()

        googleSignInClient = GoogleSignIn.getClient(
            this,
            googleSignInOptions
        )
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

    private fun setupProfile() {
        val profileImage = findViewById<ImageView>(R.id.profileImage)
        val logoutText = findViewById<TextView>(R.id.logoutText)

        val googleAccount =
            GoogleSignIn.getLastSignedInAccount(this)

        googleAccount?.photoUrl?.let { photoUrl ->
            profileImage.load(photoUrl) {
                placeholder(R.drawable.profile_placeholder)
                error(R.drawable.profile_placeholder)
                transformations(CircleCropTransformation())
            }
        }

        logoutText.setOnClickListener {
            signOut()
        }
    }

    private fun loadCurrentUserPermissions() {
        val googleAccount =
            GoogleSignIn.getLastSignedInAccount(this)

        val email =
            googleAccount?.email
                ?: firebaseAuth.currentUser?.email

        if (email.isNullOrBlank()) {
            updateDashboardUI(
                roles = emptyMap(),
                isAdmin = false
            )

            Toast.makeText(
                this,
                "Could not verify your email address.",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        checkCurrentUserRoles(email)
    }

    /*
     * Corrected role-loading logic.
     *
     * 1. Load saved permissions immediately.
     * 2. Refresh permissions from Google Sheets.
     * 3. When loading fails, continue using saved permissions.
     * 4. Only disable cards when the server successfully confirms
     *    that the user has no permissions.
     */
    private fun checkCurrentUserRoles(email: String) {
        val normalizedEmail = email.trim().lowercase()

        val cachedUser =
            loadCachedUserRoles(normalizedEmail)

        if (cachedUser != null) {
            updateDashboardUI(
                roles = cachedUser.roles,
                isAdmin = !cachedUser.isSubUser
            )
        } else {
            setDashboardLoadingState(true)
        }

        lifecycleScope.launch {
            try {
                val allUsers = fetchUsersAndRoles()

                val currentUser =
                    allUsers.firstOrNull { user ->
                        user.email.equals(
                            normalizedEmail,
                            ignoreCase = true
                        )
                    }

                setDashboardLoadingState(false)

                if (currentUser != null) {
                    saveUserRoles(currentUser)

                    updateDashboardUI(
                        roles = currentUser.roles,
                        isAdmin = !currentUser.isSubUser
                    )
                } else {
                    /*
                     * The request succeeded, but the email was not found.
                     * This is a real permission denial, not a loading error.
                     */
                    clearCachedUserRoles()

                    updateDashboardUI(
                        roles = emptyMap(),
                        isAdmin = false
                    )

                    Toast.makeText(
                        this@dashboardActivity,
                        "Your account has not been assigned dashboard permissions.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (exception: Exception) {
                Log.e(
                    TAG,
                    "Failed to check user roles",
                    exception
                )

                setDashboardLoadingState(false)

                if (cachedUser != null) {
                    /*
                     * Important:
                     * Continue using the previously saved permissions.
                     */
                    updateDashboardUI(
                        roles = cachedUser.roles,
                        isAdmin = !cachedUser.isSubUser
                    )

                    Toast.makeText(
                        this@dashboardActivity,
                        "Could not refresh permissions. Using saved permissions.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    /*
                     * No saved permissions exist.
                     * Show retry instead of silently disabling cards forever.
                     */
                    showRoleLoadingFailedDialog(
                        normalizedEmail,
                        exception.message
                    )
                }
            }
        }
    }

    private fun updateDashboardUI(
        roles: Map<String, Boolean>,
        isAdmin: Boolean
    ) {
        val activityMap = mapOf(
            "inventory count" to PhysicalInventory::class.java,
            "stock" to stockList::class.java,
            "purchase requisition" to purchase_requisition::class.java,
            "grn" to goods_received_note::class.java,
            "cash tracker" to cashTracker::class.java,
            "transaction" to transactions::class.java
        )

        val activeColor =
            ContextCompat.getColor(
                this,
                R.color.white
            )

        val inactiveColor =
            ContextCompat.getColor(
                this,
                R.color.unselected_item_color
            )

        dashboardButtons.forEach { (roleName, button) ->
            val hasRole =
                roles[roleName] == true

            if (hasRole) {
                button.setCardBackgroundColor(activeColor)
                button.alpha = 1f
                button.isEnabled = true
                button.isClickable = true
                button.isFocusable = true

                val activityClass =
                    activityMap[roleName]

                if (activityClass != null) {
                    button.setOnClickListener {
                        startActivity(
                            Intent(
                                this,
                                activityClass
                            )
                        )
                    }
                }
            } else {
                button.setCardBackgroundColor(inactiveColor)
                button.alpha = 0.55f
                button.isEnabled = false
                button.isClickable = false
                button.isFocusable = false
                button.setOnClickListener(null)
            }
        }

        updateUserManagementVisibility(isAdmin)
    }

    private fun updateUserManagementVisibility(
        isAdmin: Boolean
    ) {
        val usersIcon =
            findViewById<ImageView>(R.id.users)

        val usersText =
            findViewById<TextView>(R.id.textView2)

        if (isAdmin) {
            usersIcon.visibility = View.VISIBLE
            usersText.visibility = View.VISIBLE

            usersIcon.isEnabled = true
            usersIcon.alpha = 1f

            usersIcon.setOnClickListener {
                showManageUsersDialog()
            }
        } else {
            usersIcon.visibility = View.GONE
            usersText.visibility = View.GONE
            usersIcon.setOnClickListener(null)
        }
    }

    /*
     * This only shows a loading state.
     *
     * It does not permanently remove permission listeners.
     */
    private fun setDashboardLoadingState(
        isLoading: Boolean
    ) {
        dashboardButtons.values.forEach { button ->
            if (isLoading) {
                button.alpha = 0.65f
                button.isEnabled = false
                button.isClickable = false
            }
        }

        val usersIcon =
            findViewById<ImageView>(R.id.users)

        if (isLoading) {
            usersIcon.alpha = 0.65f
            usersIcon.isEnabled = false
        } else {
            usersIcon.alpha = 1f
            usersIcon.isEnabled = true
        }
    }

    private fun showRoleLoadingFailedDialog(
        email: String,
        errorMessage: String?
    ) {
        val message = buildString {
            append(
                "The app could not load your dashboard permissions. "
            )

            append(
                "Check your internet connection and try again."
            )

            if (!errorMessage.isNullOrBlank()) {
                append("\n\n")
                append("Error: ")
                append(errorMessage)
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Permissions Could Not Load")
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("Retry") { _, _ ->
                checkCurrentUserRoles(email)
            }
            .setNegativeButton("Sign Out") { _, _ ->
                signOut()
            }
            .show()
    }

    /*
     * Save successfully loaded permissions locally.
     */
    private fun saveUserRoles(user: UserRole) {
        val preferences =
            getSharedPreferences(
                "dashboard_permissions",
                MODE_PRIVATE
            )

        val editor = preferences.edit()

        editor.putString(
            "email",
            user.email.trim().lowercase()
        )

        editor.putBoolean(
            "isSubUser",
            user.isSubUser
        )

        editor.putString(
            "parentEmail",
            user.parentEmail
        )

        roleKeys.forEach { roleName ->
            editor.putBoolean(
                "role_$roleName",
                user.roles[roleName] == true
            )
        }

        editor.apply()
    }

    /*
     * Read previously saved permissions.
     */
    private fun loadCachedUserRoles(
        email: String
    ): UserRole? {
        val preferences =
            getSharedPreferences(
                "dashboard_permissions",
                MODE_PRIVATE
            )

        val savedEmail =
            preferences.getString(
                "email",
                null
            ) ?: return null

        if (!savedEmail.equals(email, ignoreCase = true)) {
            return null
        }

        val roles =
            roleKeys.associateWith { roleName ->
                preferences.getBoolean(
                    "role_$roleName",
                    false
                )
            }

        return UserRole(
            email = savedEmail,
            isSubUser = preferences.getBoolean(
                "isSubUser",
                true
            ),
            roles = roles,
            parentEmail = preferences.getString(
                "parentEmail",
                null
            )
        )
    }

    private fun clearCachedUserRoles() {
        getSharedPreferences(
            "dashboard_permissions",
            MODE_PRIVATE
        )
            .edit()
            .clear()
            .apply()
    }

    private suspend fun fetchUsersAndRoles(): List<UserRole> =
        withContext(Dispatchers.IO) {
            val urlWithParameters =
                "$scriptUrl?action=getUsers"

            val request =
                Request.Builder()
                    .url(urlWithParameters)
                    .get()
                    .build()

            httpClient.newCall(request)
                .execute()
                .use { response ->
                    val responseBody =
                        response.body?.string()
                            ?: throw IOException(
                                "The server returned an empty response."
                            )

                    if (!response.isSuccessful) {
                        throw IOException(
                            "Failed to fetch users. " +
                                    "Server code: ${response.code}. " +
                                    responseBody
                        )
                    }

                    val jsonArray =
                        try {
                            JSONArray(responseBody)
                        } catch (exception: Exception) {
                            throw IOException(
                                "Invalid user data received from server.",
                                exception
                            )
                        }

                    val allUsers =
                        mutableListOf<UserRole>()

                    for (index in 0 until jsonArray.length()) {
                        val jsonObject =
                            jsonArray.getJSONObject(index)

                        val mainEmail =
                            jsonObject
                                .optString("email", "")
                                .trim()
                                .lowercase()

                        val subUserEmail =
                            jsonObject
                                .optString("email sub user", "")
                                .trim()
                                .lowercase()

                        val isAdminRow =
                            isEnabledValue(
                                jsonObject.optString(
                                    "admin",
                                    ""
                                )
                            )

                        /*
                         * Main/admin user:
                         * Admin gets access to all modules.
                         */
                        if (
                            mainEmail.isNotEmpty() &&
                            isAdminRow
                        ) {
                            val adminRoles =
                                roleKeys.associateWith {
                                    true
                                }

                            allUsers.add(
                                UserRole(
                                    email = mainEmail,
                                    isSubUser = false,
                                    roles = adminRoles,
                                    parentEmail = null
                                )
                            )
                        }

                        /*
                         * Sub-user:
                         * Permissions are taken from each module column.
                         */
                        if (subUserEmail.isNotEmpty()) {
                            val subUserRoles =
                                roleKeys.associateWith { roleName ->
                                    isEnabledValue(
                                        jsonObject.optString(
                                            roleName,
                                            ""
                                        )
                                    )
                                }

                            allUsers.add(
                                UserRole(
                                    email = subUserEmail,
                                    isSubUser = true,
                                    roles = subUserRoles,
                                    parentEmail = mainEmail
                                )
                            )
                        }
                    }

                    /*
                     * groupBy is safer than distinctBy.
                     *
                     * When an email appears more than once, it combines
                     * any true permissions instead of ignoring later rows.
                     */
                    return@use allUsers
                        .groupBy { user ->
                            user.email.lowercase()
                        }
                        .map { (_, matchingUsers) ->
                            mergeMatchingUsers(matchingUsers)
                        }
                }
        }

    private fun mergeMatchingUsers(
        users: List<UserRole>
    ): UserRole {
        val firstUser = users.first()

        /*
         * If any matching record is a main/admin user,
         * treat the account as an admin.
         */
        val isSubUser =
            users.all { user ->
                user.isSubUser
            }

        val mergedRoles =
            roleKeys.associateWith { roleName ->
                users.any { user ->
                    user.roles[roleName] == true
                }
            }

        val parentEmail =
            users.firstOrNull { user ->
                !user.parentEmail.isNullOrBlank()
            }?.parentEmail

        return UserRole(
            email = firstUser.email,
            isSubUser = isSubUser,
            roles = mergedRoles,
            parentEmail = parentEmail
        )
    }

    /*
     * Accepts values such as:
     * 1, true, yes, checked
     */
    private fun isEnabledValue(
        value: String
    ): Boolean {
        return when (
            value.trim().lowercase()
        ) {
            "1",
            "true",
            "yes",
            "checked" -> true

            else -> false
        }
    }

    private fun showManageUsersDialog() {
        val dialogBinding =
            DialogManageUsersBinding.inflate(
                layoutInflater
            )

        val usersRecyclerView =
            dialogBinding.usersRecyclerView

        usersRecyclerView.layoutManager =
            LinearLayoutManager(this)

        val currentUserEmail =
            firebaseAuth.currentUser?.email
                ?: GoogleSignIn
                    .getLastSignedInAccount(this)
                    ?.email

        if (currentUserEmail.isNullOrBlank()) {
            Toast.makeText(
                this,
                "Cannot verify the current user.",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        val mainDialog =
            AlertDialog.Builder(this)
                .setTitle("Manage Your Sub-Users")
                .setView(dialogBinding.root)
                .setNegativeButton("Close", null)
                .setPositiveButton(
                    "Add User"
                ) { _, _ ->
                    usersRecyclerView.post {
                        showAddUserDialog()
                    }
                }
                .create()

        mainDialog.show()

        val progress =
            ProgressDialog(this).apply {
                setMessage("Fetching users...")
                setCancelable(false)
                show()
            }

        lifecycleScope.launch {
            try {
                val allUsers =
                    fetchUsersAndRoles()

                val mySubUsers =
                    allUsers.filter { user ->
                        user.isSubUser &&
                                user.parentEmail.equals(
                                    currentUserEmail,
                                    ignoreCase = true
                                )
                    }

                progress.dismiss()

                val adapter =
                    UserRolesAdapter(
                        mySubUsers,
                        onEditClicked = { user ->
                            usersRecyclerView.post {
                                mainDialog.dismiss()
                                showEditUserDialog(user)
                            }
                        },
                        onDeleteClicked = { user ->
                            usersRecyclerView.post {
                                mainDialog.dismiss()
                                showDeleteUserDialog(user)
                            }
                        }
                    )

                usersRecyclerView.adapter = adapter
            } catch (exception: Exception) {
                progress.dismiss()

                Log.e(
                    TAG,
                    "Error fetching users",
                    exception
                )

                Toast.makeText(
                    this@dashboardActivity,
                    "Error fetching users: ${exception.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showAddUserDialog() {
        val dialogBinding =
            DialogAddUserBinding.inflate(
                layoutInflater
            )

        val dialog =
            AlertDialog.Builder(this)
                .setTitle("Add New Sub-User")
                .setView(dialogBinding.root)
                .setPositiveButton(
                    "Add",
                    null
                )
                .setNegativeButton(
                    "Cancel",
                    null
                )
                .create()

        dialog.setOnShowListener {
            dialog.getButton(
                AlertDialog.BUTTON_POSITIVE
            ).setOnClickListener {
                val newUserEmail =
                    dialogBinding.emailInput
                        .text
                        .toString()
                        .trim()
                        .lowercase()

                val isValidEmail =
                    android.util.Patterns.EMAIL_ADDRESS
                        .matcher(newUserEmail)
                        .matches()

                if (!isValidEmail) {
                    dialogBinding.emailInput.error =
                        "Enter a valid email address."

                    dialogBinding.emailInput.requestFocus()
                    return@setOnClickListener
                }

                val roles = mapOf(
                    "stock" to
                            dialogBinding
                                .roleStockModule
                                .isChecked,

                    "transaction" to
                            dialogBinding
                                .roleTransactionsModule
                                .isChecked,

                    "grn" to
                            dialogBinding
                                .roleGrn
                                .isChecked,

                    "purchase requisition" to
                            dialogBinding
                                .rolePurchaseRequisition
                                .isChecked,

                    "inventory count" to
                            dialogBinding
                                .rolePhysicalInventory
                                .isChecked,

                    "cash tracker" to
                            dialogBinding
                                .roleCashTracker
                                .isChecked
                )

                dialog.dismiss()

                addUserToSheet(
                    newUserEmail,
                    roles
                )
            }
        }

        dialog.show()
    }

    private fun addUserToSheet(
        newUserEmail: String,
        roles: Map<String, Boolean>
    ) {
        val progress =
            ProgressDialog(this).apply {
                setMessage(
                    "Validating and adding user..."
                )

                setCancelable(false)
                show()
            }

        val mainUserEmail =
            firebaseAuth.currentUser?.email
                ?: GoogleSignIn
                    .getLastSignedInAccount(this)
                    ?.email

        if (mainUserEmail.isNullOrBlank()) {
            progress.dismiss()

            Toast.makeText(
                this,
                "Could not identify the main user.",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        lifecycleScope.launch {
            try {
                val allUsers =
                    fetchUsersAndRoles()

                val userExists =
                    allUsers.any { user ->
                        user.email.equals(
                            newUserEmail,
                            ignoreCase = true
                        )
                    }

                if (userExists) {
                    progress.dismiss()

                    AlertDialog.Builder(
                        this@dashboardActivity
                    )
                        .setTitle("User Exists")
                        .setMessage(
                            "The email '$newUserEmail' is already " +
                                    "registered as a main user or sub-user."
                        )
                        .setPositiveButton(
                            "OK",
                            null
                        )
                        .show()

                    return@launch
                }

                progress.setMessage("Adding user...")

                val scriptRoles =
                    roles.mapValues { (_, allowed) ->
                        if (allowed) "1" else ""
                    }

                val postBody =
                    JSONObject().apply {
                        put(
                            "action",
                            "addUser"
                        )

                        put(
                            "mainUserEmail",
                            mainUserEmail.trim().lowercase()
                        )

                        put(
                            "newUserEmail",
                            newUserEmail.trim().lowercase()
                        )

                        put(
                            "token",
                            "6BntfqAtwMbHlYOkbcwWipRTWKe2"
                        )

                        put(
                            "roles",
                            JSONObject(scriptRoles)
                        )
                    }

                executePostRequest(
                    postBody.toString()
                )

                progress.dismiss()

                Toast.makeText(
                    this@dashboardActivity,
                    "User added successfully.",
                    Toast.LENGTH_LONG
                ).show()

                showManageUsersDialog()
            } catch (exception: Exception) {
                handleException(
                    exception,
                    progress
                )
            }
        }
    }

    private fun showEditUserDialog(
        user: UserRole
    ) {
        val dialogBinding =
            DialogAddUserBinding.inflate(
                layoutInflater
            )

        val checkBoxes = mapOf(
            "stock" to
                    dialogBinding.roleStockModule,

            "transaction" to
                    dialogBinding.roleTransactionsModule,

            "grn" to
                    dialogBinding.roleGrn,

            "purchase requisition" to
                    dialogBinding.rolePurchaseRequisition,

            "inventory count" to
                    dialogBinding.rolePhysicalInventory,

            "cash tracker" to
                    dialogBinding.roleCashTracker
        )

        dialogBinding.emailInput.setText(
            user.email
        )

        dialogBinding.emailInput.isEnabled =
            false

        dialogBinding.emailInput.alpha =
            0.5f

        checkBoxes.forEach { (roleName, checkBox) ->
            checkBox.isChecked =
                user.roles[roleName] == true
        }

        AlertDialog.Builder(this)
            .setTitle("Edit User Roles")
            .setView(dialogBinding.root)
            .setPositiveButton(
                "Save Changes"
            ) { _, _ ->
                val updatedRoles =
                    checkBoxes.mapValues { (_, checkBox) ->
                        checkBox.isChecked
                    }

                updateUserInSheet(
                    user.email,
                    updatedRoles
                )
            }
            .setNegativeButton(
                "Cancel",
                null
            )
            .show()
    }

    private fun showDeleteUserDialog(
        user: UserRole
    ) {
        AlertDialog.Builder(this)
            .setTitle("Delete User")
            .setMessage(
                "Are you sure you want to permanently " +
                        "delete '${user.email}'?"
            )
            .setPositiveButton(
                "Delete"
            ) { _, _ ->
                deleteUserFromSheet(
                    user.email
                )
            }
            .setNegativeButton(
                "Cancel",
                null
            )
            .show()
    }

    private fun updateUserInSheet(
        userEmail: String,
        roles: Map<String, Boolean>
    ) {
        val progress =
            ProgressDialog(this).apply {
                setMessage("Updating user...")
                setCancelable(false)
                show()
            }

        lifecycleScope.launch {
            try {
                val scriptRoles =
                    roles.mapValues { (_, allowed) ->
                        if (allowed) "1" else ""
                    }

                val postBody =
                    JSONObject().apply {
                        put(
                            "action",
                            "updateUser"
                        )

                        put(
                            "userEmail",
                            userEmail.trim().lowercase()
                        )

                        put(
                            "roles",
                            JSONObject(scriptRoles)
                        )
                    }

                val serverMessage =
                    executePostRequest(
                        postBody.toString()
                    )

                progress.dismiss()

                Toast.makeText(
                    this@dashboardActivity,
                    serverMessage,
                    Toast.LENGTH_LONG
                ).show()

                showManageUsersDialog()
            } catch (exception: Exception) {
                handleException(
                    exception,
                    progress
                )
            }
        }
    }

    private fun deleteUserFromSheet(
        userEmail: String
    ) {
        val progress =
            ProgressDialog(this).apply {
                setMessage("Deleting user...")
                setCancelable(false)
                show()
            }

        lifecycleScope.launch {
            try {
                val postBody =
                    JSONObject().apply {
                        put(
                            "action",
                            "deleteUser"
                        )

                        put(
                            "userEmail",
                            userEmail.trim().lowercase()
                        )
                    }

                val serverMessage =
                    executePostRequest(
                        postBody.toString()
                    )

                progress.dismiss()

                Toast.makeText(
                    this@dashboardActivity,
                    serverMessage,
                    Toast.LENGTH_LONG
                ).show()

                showManageUsersDialog()
            } catch (exception: Exception) {
                handleException(
                    exception,
                    progress
                )
            }
        }
    }

    /*
     * Network request now returns the server message.
     *
     * UI operations such as dismissing dialogs are done by the caller
     * on the Main thread.
     */
    private suspend fun executePostRequest(
        postBodyString: String
    ): String = withContext(Dispatchers.IO) {
        val mediaType =
            "application/json; charset=utf-8"
                .toMediaTypeOrNull()

        val requestBody =
            RequestBody.create(
                mediaType,
                postBodyString
            )

        val request =
            Request.Builder()
                .url(scriptUrl)
                .post(requestBody)
                .build()

        httpClient.newCall(request)
            .execute()
            .use { response ->
                val responseBody =
                    response.body?.string()
                        ?: throw IOException(
                            "The server returned an empty response."
                        )

                if (!response.isSuccessful) {
                    throw IOException(
                        "Server error ${response.code}: $responseBody"
                    )
                }

                val jsonResponse =
                    try {
                        JSONObject(responseBody)
                    } catch (exception: Exception) {
                        throw IOException(
                            "The server returned invalid data.",
                            exception
                        )
                    }

                if (
                    jsonResponse
                        .optString("status")
                        .equals(
                            "error",
                            ignoreCase = true
                        )
                ) {
                    throw IOException(
                        jsonResponse.optString(
                            "message",
                            "Unknown script error."
                        )
                    )
                }

                return@use jsonResponse.optString(
                    "message",
                    "Operation completed successfully."
                )
            }
    }

    private suspend fun handleException(
        exception: Exception,
        progress: ProgressDialog
    ) {
        withContext(Dispatchers.Main) {
            if (progress.isShowing) {
                progress.dismiss()
            }

            Log.e(
                TAG,
                "Google Sheet operation failed",
                exception
            )

            Toast.makeText(
                this@dashboardActivity,
                "Error: ${exception.message ?: "Unknown error"}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun setupSystemBars() {
        val brandColor =
            Color.parseColor("#0071c1")

        val navigationColor =
            Color.parseColor("#f4f6ff")

        window.statusBarColor =
            brandColor

        window.navigationBarColor =
            navigationColor

        val insetsController =
            WindowCompat.getInsetsController(
                window,
                window.decorView
            )

        insetsController.isAppearanceLightStatusBars =
            false

        insetsController.isAppearanceLightNavigationBars =
            true
    }

    private fun signOut() {
        /*
         * Clear permissions so that a different account cannot inherit
         * the previous account's permissions.
         */
        clearCachedUserRoles()

        firebaseAuth.signOut()

        googleSignInClient
            .signOut()
            .addOnCompleteListener {
                val intent =
                    Intent(
                        this,
                        signup_dashboard_activity::class.java
                    )

                intent.flags =
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TASK

                startActivity(intent)
                finish()
            }
    }
}