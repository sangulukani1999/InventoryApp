package com.example.zed

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import com.google.api.services.drive.model.Permission
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.SheetsScopes
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

class GoogleDriveHelper(
    private val context: Context,
    private val account: GoogleSignInAccount,
    private val parentEmail: String? // This will be the Admin's email for sub-users
) {

    private val credential by lazy {
        GoogleAccountCredential.usingOAuth2(
            context,
            listOf(DriveScopes.DRIVE, SheetsScopes.SPREADSHEETS)
        ).apply {
            selectedAccountName = account.email
        }
    }

    private val driveService: Drive by lazy {
        Drive.Builder(
            GoogleNetHttpTransport.newTrustedTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        ).setApplicationName("Nia Bridge App").build()
    }

    private val sheetsService: Sheets by lazy {
        Sheets.Builder(
            GoogleNetHttpTransport.newTrustedTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        ).setApplicationName("Nia Bridge App").build()
    }

    fun findOrCreateNiaBridgeFolderAndSheet() {
        val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
            Log.e("DriveHelper", "Coroutine failed", throwable)
            (context as? Activity)?.runOnUiThread {
                Toast.makeText(context, "❌ Setup Error: ${throwable.message}", Toast.LENGTH_LONG).show()
            }
        }

        CoroutineScope(Dispatchers.IO + exceptionHandler).launch {
            val folderQuery = if (parentEmail != null) {
                // For SUB-USER: Search for a folder shared with me by the parent admin.
                "'${parentEmail}' in owners and sharedWithMe and name='nia-bridge' and mimeType='application/vnd.google-apps.folder' and trashed=false"
            } else {
                // For ADMIN: Search for the folder they own.
                "'me' in owners and name='nia-bridge' and mimeType='application/vnd.google-apps.folder' and trashed=false"
            }

            Log.d("DriveHelper", "Executing query: $folderQuery")
            val folderResult = driveService.files().list().setQ(folderQuery).setSpaces("drive").execute()
            var niaBridgeFolder = folderResult.files.firstOrNull()

            // --- ADMIN PATH ---
            if (parentEmail == null) {
                if (niaBridgeFolder == null) {
                    // Admin is logging in for the very first time. Create everything.
                    withContext(Dispatchers.Main) { Toast.makeText(context, "Creating Nia Bridge folder...", Toast.LENGTH_SHORT).show() }
                    niaBridgeFolder = createNiaBridgeFolder() // Create folder
                    createSheetInFolder(niaBridgeFolder!!.id) // Create sheet
                }

                // ✅ ALWAYS aunn this on every Admin login.
                // This runs if the folder was just created OR if it already existed.
                // It ensures new sub-users get access.
                withContext(Dispatchers.Main) { Toast.makeText(context, "Syncing sub-user permissions...", Toast.LENGTH_SHORT).show() }
                shareFolderWithSubUsers(niaBridgeFolder!!.id, account.email!!)
            }

            // --- POST-ADMIN or SUB-USER PATH ---
            if (niaBridgeFolder == null) {
                // This will now only be reached by a sub-user if the admin hasn't logged in.
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "❌ Critical Error: Admin's shared folder not found. Please have the Admin log in again to grant permissions.", Toast.LENGTH_LONG).show()
                }
                return@launch
            }

            // --- Folder exists, now check for the sheet inside it ---
            val sheetQuery = "'${niaBridgeFolder.id}' in parents and mimeType='application/vnd.google-apps.spreadsheet' and name='nia-bridge data' and trashed=false"
            val sheetResult = driveService.files().list().setQ(sheetQuery).setSpaces("drive").execute()

            if (sheetResult.files.isNullOrEmpty()) {
                if (parentEmail != null) {
                    // Sub-user sees the folder but not the sheet.
                    withContext(Dispatchers.Main) { Toast.makeText(context, "❌ Error: Sheet is missing from Admin's folder.", Toast.LENGTH_LONG).show() }
                } else {
                    // Admin sees the folder is missing a sheet (e.g., it was deleted). Re-create it.
                    withContext(Dispatchers.Main) { Toast.makeText(context, "Folder found, creating missing sheet...", Toast.LENGTH_SHORT).show() }
                    createSheetInFolder(niaBridgeFolder.id)
                    withContext(Dispatchers.Main) { Toast.makeText(context, "✅ Sheet created successfully!", Toast.LENGTH_LONG).show() }
                    openDashboard()
                }
            } else {
                // --- Success! Folder and Sheet were found. ---
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "✅ Nia Bridge files found. You're all set!", Toast.LENGTH_LONG).show()
                    openDashboard()
                }
            }
        }
    }

    private fun createNiaBridgeFolder(): File {
        val folderMetadata = File().apply {
            name = "nia-bridge"
            mimeType = "application/vnd.google-apps.folder"
        }
        return driveService.files().create(folderMetadata).setFields("id").execute()
    }

    private suspend fun shareFolderWithSubUsers(folderId: String, adminEmail: String) {
        val client = OkHttpClient()
        val request = Request.Builder()
            .url("https://opensheet.elk.sh/1W-LOkSgPPrfhZ_kqfycUvOcGviQplMng6xpc6KBQ9Ik/users")
            .build()

        try {
            val response = withContext(Dispatchers.IO) {
                client.newCall(request).execute()
            }
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                val jsonArray = JSONArray(body)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val mainEmail = obj.optString("email").trim()
                    val subEmail = obj.optString("email sub user").trim()

                    // If this row belongs to the current admin and has a sub-user, share the folder
                    if (adminEmail.equals(mainEmail, ignoreCase = true) && subEmail.isNotEmpty()) {
                        Log.d("DriveHelper", "Attempting to share folder with: $subEmail")
                        val permission = Permission().apply {
                            type = "user"
                            role = "writer" // Give them write access
                            emailAddress = subEmail
                        }
                        // This sends the invitation
                        try {
                            driveService.permissions().create(folderId, permission).setSendNotificationEmail(false).execute()
                        } catch (e: Exception) {
                            // This can happen if the permission already exists. We can safely ignore it.
                            Log.w("DriveHelper", "Could not share with $subEmail. They might already have permission. Error: ${e.message}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("DriveHelper", "Failed to share folder with sub-users", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Warning: Could not sync sub-user permissions.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun createSheetInFolder(folderId: String): File {
        val sheetMetadata = File().apply {
            name = "nia-bridge data"
            mimeType = "application/vnd.google-apps.spreadsheet"
            parents = listOf(folderId)
        }
        return driveService.files().create(sheetMetadata).setFields("id, name").execute()
    }

    private fun openDashboard() {
        (context as? Activity)?.let {
            val intent = Intent(it, dashboardActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            it.startActivity(intent)
            it.finish()
        }
    }
}
