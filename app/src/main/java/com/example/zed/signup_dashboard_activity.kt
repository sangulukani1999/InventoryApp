package com.example.zed

import android.app.ProgressDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import com.google.api.services.sheets.v4.SheetsScopes
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import okhttp3.*
import org.json.JSONArray
import org.json.JSONException
import java.io.IOException

class signup_dashboard_activity : AppCompatActivity() {

    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var firebaseAuth: FirebaseAuth
    private lateinit var progressDialog: ProgressDialog

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_signup_dashboard)

        firebaseAuth = FirebaseAuth.getInstance()
        progressDialog = ProgressDialog(this).apply {
            setCancelable(false)
            setMessage("Verifying access...")
        }

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .requestScopes(
                Scope(DriveScopes.DRIVE),
                Scope(SheetsScopes.SPREADSHEETS)
            )
            .build()

        googleSignInClient = GoogleSignIn.getClient(this, gso)

        val signinCard = findViewById<ConstraintLayout>(R.id.btnLogin)
        signinCard.setOnClickListener {
            // Sign out first to ensure the permission screen is always shown during testing
            googleSignInClient.signOut().addOnCompleteListener {
                signInWithGoogle()
            }
        }
    }

    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)!!
                firebaseAuthWithGoogle(account)
            } catch (e: ApiException) {
                progressDialog.dismiss()
                Log.e("AuthError", "Google sign in failed", e)
                Toast.makeText(this, "Google sign in failed: ${e.statusCode}", Toast.LENGTH_SHORT).show()
            }
        } else {
            progressDialog.dismiss()
            Toast.makeText(this, "Sign in cancelled.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun signInWithGoogle() {
        val signInIntent = googleSignInClient.signInIntent
        signInLauncher.launch(signInIntent)
    }

    private fun firebaseAuthWithGoogle(account: GoogleSignInAccount) {
        progressDialog.show()

        val credential = GoogleAuthProvider.getCredential(account.idToken, null)
        firebaseAuth.signInWithCredential(credential).addOnCompleteListener(this) { task ->
            if (task.isSuccessful) {
                val userEmail = account.email ?: ""

                // ✅ UPDATED: The callback now returns the parentEmail
                checkUserInSheet(userEmail) { isAdmin, exists, parentEmail ->
                    runOnUiThread {
                        if (exists) {
                            // ✅ UPDATED: Pass the parentEmail to the GoogleDriveHelper constructor
                            val driveHelper = GoogleDriveHelper(this, account, parentEmail)

                            if (isAdmin) {
                                progressDialog.setMessage("Verifying Nia Bridge setup...")
                                driveHelper.findOrCreateNiaBridgeFolderAndSheet()
                            } else {
                                // For sub-users, the helper now has the parent's email to find the files
                                progressDialog.setMessage("Locating shared files...")
                                driveHelper.findOrCreateNiaBridgeFolderAndSheet()
                            }
                        } else {
                            progressDialog.dismiss()
                            firebaseAuth.signOut()
                            googleSignInClient.signOut()
                            Toast.makeText(
                                this,
                                "Access denied: User not registered",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            } else {
                progressDialog.dismiss()
                Toast.makeText(this, "Firebase Authentication Failed.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * ✅ UPDATED: The callback now includes a third parameter: `parentEmail: String?`
     * This function now correctly finds the parent email for a sub-user.
     */
    private fun checkUserInSheet(email: String, callback: (isAdmin: Boolean, exists: Boolean, parentEmail: String?) -> Unit) {
        val client = OkHttpClient()
        val request = Request.Builder()
            .url("https://opensheet.elk.sh/1W-LOkSgPPrfhZ_kqfycUvOcGviQplMng6xpc6KBQ9Ik/users")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    progressDialog.dismiss()
                    Toast.makeText(this@signup_dashboard_activity, "Network error. Please check connection.", Toast.LENGTH_SHORT).show()
                }
                callback(false, false, null)
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    runOnUiThread {
                        progressDialog.dismiss()
                        Toast.makeText(this@signup_dashboard_activity, "Error fetching user list.", Toast.LENGTH_SHORT).show()
                    }
                    callback(false, false, null)
                    return
                }

                try {
                    val body = response.body?.string() ?: ""
                    val jsonArray = JSONArray(body)

                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        val mainEmail = obj.optString("email").trim()
                        val subEmail = obj.optString("email sub user").trim()
                        val adminFlag = obj.optString("admin") == "1"

                        // Case 1: The logged-in user is a main user/admin
                        if (email.equals(mainEmail, ignoreCase = true)) {
                            // They exist, might be an admin, and have no parent.
                            callback(adminFlag, true, null)
                            return // Exit after finding the match
                        }

                        // Case 2: The logged-in user is a sub-user
                        if (subEmail.isNotEmpty() && email.equals(subEmail, ignoreCase = true)) {
                            // They exist, are not an admin, and their parent is the "mainEmail" from this row.
                            callback(false, true, mainEmail)
                            return // Exit after finding the match
                        }
                    }

                    // If the loop finishes, the user was not found anywhere.
                    callback(false, false, null)

                } catch (e: JSONException) {
                    runOnUiThread {
                        progressDialog.dismiss()
                        Toast.makeText(this@signup_dashboard_activity, "Error parsing user data.", Toast.LENGTH_SHORT).show()
                    }
                    callback(false, false, null)
                }
            }
        })
    }
}
