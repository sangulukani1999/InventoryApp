package com.example.zed

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.ImageButton
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class PhysicalInventory : AppCompatActivity() {
    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_physical_inventory)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Handle back button click
        val backButton = findViewById<ImageButton>(R.id.backBtnPhysicalInventory)
        backButton.setOnClickListener {
            val intent = Intent(this, signup_dashboard_activity::class.java)
            startActivity(intent)
        }

        // Add this part to handle the click
        val cardView8 = findViewById<CardView>(R.id.stockTakingView)
        cardView8.setOnClickListener {
            val intent = Intent(this, stockTaking::class.java)
            startActivity(intent)
        }

        setupSystemBars()
    }

    private fun setupSystemBars() {
        // Parse the color from the hex string
        val brandColor = Color.parseColor("#0071c1")
        val brandColorNavigation = Color.parseColor("#f4f6ff")



        // Set the status bar color
        window.statusBarColor = brandColor
        // Set the navigation bar color
        window.navigationBarColor = brandColorNavigation

        // Tell the system that the status bar background is dark, so it should use light (white) icons
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false

        // It's also good practice to define the navigation bar icon color explicitly.
        // `false` means the navigation bar background is dark, so icons should be light.
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = false
    }

}