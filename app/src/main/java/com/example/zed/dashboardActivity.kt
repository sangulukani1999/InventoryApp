package com.example.zed

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import coil.load
import coil.transform.CircleCropTransformation
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth

class dashboardActivity : AppCompatActivity() {

    private lateinit var firebaseAuth: FirebaseAuth
    private lateinit var googleSignInClient: GoogleSignInClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_dashboard)

        // Initialize Firebase Auth
        firebaseAuth = FirebaseAuth.getInstance()

        // Configure Google Sign-In
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        // Handle edge-to-edge padding
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // CardView navigation example
        val cardView8 = findViewById<CardView>(R.id.cardView8)
        cardView8.setOnClickListener {
            startActivity(Intent(this, PhysicalInventory::class.java))
        }

        // CardView navigation example
        val stock_id = findViewById<CardView>(R.id.stock_id)
        stock_id.setOnClickListener {
            startActivity(Intent(this, stockList::class.java))
        }

        // CardView navigation example
        val purchaseRequisition = findViewById<CardView>(R.id.purchaseRequisition)
        purchaseRequisition.setOnClickListener {
            // Correct: Point the Intent to the actual Activity class for the new screen
            startActivity(Intent(this, purchase_requisition::class.java))
        }

        // CardView navigation example
        val cashTrackerbtn = findViewById<CardView>(R.id.cashTrackerBtn)
        cashTrackerbtn.setOnClickListener {
            // Correct: Point the Intent to the actual Activity class for the new screen
            startActivity(Intent(this, cashTracker::class.java))
        }


        // Profile picture and logout
        val profileImage = findViewById<ImageView>(R.id.profileImage)
        val logoutText = findViewById<TextView>(R.id.logoutText)

        // Load Google profile picture
        val account = GoogleSignIn.getLastSignedInAccount(this)
        account?.photoUrl?.let { url ->
            profileImage.load(url) {
                placeholder(R.drawable.profile_placeholder) // Optional placeholder
                transformations(CircleCropTransformation())
            }
        }

        // Logout functionality
        logoutText.setOnClickListener {
            signOut()
        }
    }

    private fun signOut() {
        // Firebase sign out
        firebaseAuth.signOut()

        // Google sign out
        googleSignInClient.signOut().addOnCompleteListener {
            // Redirect to login/signup activity
            val intent = Intent(this, signup_dashboard_activity::class.java)
            startActivity(intent)
            finish()
        }
    }
}
