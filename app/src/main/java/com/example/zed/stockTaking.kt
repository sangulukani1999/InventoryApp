package com.example.zed

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.zed.databinding.ActivityStockTakingBinding

class stockTaking : AppCompatActivity() {

    private lateinit var binding: ActivityStockTakingBinding

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Enable Edge-to-Edge
        enableEdgeToEdge()

        // 2. Initialize View Binding
        binding = ActivityStockTakingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 3. Fix Bottom Space & Handle Status Bar
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // Top padding for status bar, 0 bottom padding so Nav sits flush
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
            insets
        }

        // 4. Initialize your UI components (Example logic)
        setupDashboard()

        // 5. Navigation listeners
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when(item.itemId) {
                R.id.navigation_home -> {
                    // RELATIONSHIP: The Activity loads the Fragment into a container
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.stockHomeRoot, StockHomeFragment())
                        .commit()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupDashboard() {
        // Set your progress indicators from the XML
        binding.stockProgressCircle.progress = 69
        binding.stockProgressBar.progress = 69
        binding.locationProgressBar.progress = 53

        // Click listeners for the dashboard cards
        binding.continueCountingButton.setOnClickListener {
            // Logic to open scanner or item list
        }

        binding.startLocationButton.setOnClickListener {
            // Logic to change location
        }
    }
}