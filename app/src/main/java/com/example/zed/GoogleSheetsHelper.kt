package com.example.zed

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
// ✅ 1. REMOVE the deprecated import
// import com.google.api.client.extensions.android.http.AndroidHttp
// ✅ 2. ADD the modern transport import
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.FileContent
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import com.google.api.services.drive.model.Permission
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.SheetsScopes
import com.google.api.services.sheets.v4.model.AddSheetRequest
import com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetRequest
import com.google.api.services.sheets.v4.model.Request
import com.google.api.services.sheets.v4.model.SheetProperties
import com.google.api.services.sheets.v4.model.ValueRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.io.InputStream

class GoogleSheetsHelper(private val context: Context, private val account: GoogleSignInAccount) {

    private fun getDriveService(): Drive {
        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            listOf(DriveScopes.DRIVE)
        ).apply {
            selectedAccountName = account.email
        }
        return Drive.Builder(
            // ✅ 3. USE the modern transport
            GoogleNetHttpTransport.newTrustedTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        ).setApplicationName("Nia Bridge App").build()
    }

    private fun getSheetsService(): Sheets {
        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            listOf(SheetsScopes.SPREADSHEETS)
        ).apply {
            selectedAccountName = account.email
        }
        return Sheets.Builder(
            // ✅ 3. USE the modern transport
            GoogleNetHttpTransport.newTrustedTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        ).setApplicationName("Nia Bridge App").build()
    }


    suspend fun findSheetIdByName(name: String): String? = withContext(Dispatchers.IO) {
        val driveService = getDriveService()
        val query = "mimeType='application/vnd.google-apps.spreadsheet' and name='$name' and trashed=false"
        val result = driveService.files().list()
            .setQ(query)
            .setSpaces("drive")
            .setFields("files(id)")
            .execute()
        return@withContext result.files.firstOrNull()?.id
    }

    suspend fun ensureSheetExists(spreadsheetId: String, sheetName: String) = withContext(Dispatchers.IO) {
        val sheetsService = getSheetsService()
        val spreadsheet = sheetsService.spreadsheets().get(spreadsheetId).execute()
        val sheetExists = spreadsheet.sheets.any { it.properties.title == sheetName }

        if (!sheetExists) {
            val addSheetRequest = AddSheetRequest().setProperties(SheetProperties().setTitle(sheetName))
            val batchUpdateRequest = BatchUpdateSpreadsheetRequest().setRequests(listOf(Request().setAddSheet(addSheetRequest)))
            sheetsService.spreadsheets().batchUpdate(spreadsheetId, batchUpdateRequest).execute()
        }
    }

    suspend fun uploadImageAndWriteToSheet(
        imageUri: Uri,
        spreadsheetId: String,
        sheetName: String,
        userEmail: String,
        productName: String
    ): String? = withContext(Dispatchers.IO) {
        try {
            val driveService = getDriveService()
            val sheetsService = getSheetsService()

            val imageFile = createTempFileFromUri(imageUri)
            val folderId = getOrCreateNiaBridgeFolder(driveService)

            val fileMetadata = File().apply {
                this.name = imageFile.name
                mimeType = "image/jpeg"
                parents = listOf(folderId)
            }
            val mediaContent = FileContent("image/jpeg", imageFile)
            val uploadedFile = driveService.files().create(fileMetadata, mediaContent)
                .setFields("id, name")
                .execute()

            val permission = Permission().apply {
                type = "anyone"
                role = "reader"
            }
            driveService.permissions().create(uploadedFile.id, permission).execute()

            val publicUrl = "https://drive.google.com/uc?id=${uploadedFile.id}"

            val values = listOf(listOf(userEmail, productName, publicUrl))
            val body = ValueRange().setValues(values)
            val range = "$sheetName!A1"

            sheetsService.spreadsheets().values()
                .append(spreadsheetId, range, body)
                .setValueInputOption("USER_ENTERED")
                .setInsertDataOption("INSERT_ROWS")
                .execute()

            publicUrl
        } catch (e: Exception) {
            Log.e("GoogleSheetsHelper", "Error uploading to sheet", e)
            null
        }
    }

    private suspend fun getOrCreateNiaBridgeFolder(driveService: Drive): String = withContext(Dispatchers.IO) {
        val folderQuery = "mimeType='application/vnd.google-apps.folder' and name='nia-bridge' and trashed=false"
        val folderList = driveService.files().list().setQ(folderQuery).execute()

        if (folderList.files.isNotEmpty()) {
            return@withContext folderList.files[0].id
        }

        val folderMetadata = File().apply {
            name = "nia-bridge"
            mimeType = "application/vnd.google-apps.folder"
        }
        val folder = driveService.files().create(folderMetadata).setFields("id").execute()
        return@withContext folder.id
    }

    private fun createTempFileFromUri(uri: Uri): java.io.File {
        val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
        val tempFile = java.io.File.createTempFile("upload_", ".jpg", context.cacheDir)
        val outputStream = FileOutputStream(tempFile)
        inputStream?.copyTo(outputStream)
        inputStream?.close()
        outputStream.close()
        return tempFile
    }
}
