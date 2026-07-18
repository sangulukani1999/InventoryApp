package com.example.zed

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.example.zed.databinding.ActivityPhysicalInventoryBinding // 1. Import the binding class

class PhysicalInventory : AppCompatActivity() {

    private lateinit var binding: ActivityPhysicalInventoryBinding // 2. Declare a binding variable

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 3. Inflate the layout using the binding class
        binding = ActivityPhysicalInventoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // --- Use the binding object to access views ---

        // ✅ CORRECTED: Handle back button click by finishing the current activity
        binding.backBtnPhysicalInventory.setOnClickListener {
            finish() // This correctly closes the current screen and goes back.
        }

        // Set click listener for the stock taking CardView
        binding.stockTakingView.setOnClickListener {
            val intent = Intent(this, stockTaking::class.java)
            startActivity(intent)
        }

        // Set click listener for the stock summary report CardView
        binding.stockSummaryReport.setOnClickListener {
            // This will now work because the Activity is declared in the manifest
            val intent = Intent(this, stockSummaryReport::class.java)
            startActivity(intent)
        }

        setupSystemBars()
    }

    private fun setupSystemBars() {
        val brandColor = Color.parseColor("#0071c1")
        val brandColorNavigation = Color.parseColor("#f4f6ff")

        window.statusBarColor = brandColor
        window.navigationBarColor = brandColorNavigation

        // Status bar has a dark background, so icons should be light (false)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false

        // ✅ CORRECTED: Navigation bar has a light background, so icons must be dark (true)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = true
    }
}