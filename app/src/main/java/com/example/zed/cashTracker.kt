package com.example.zed

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import cashTrackerAdapter
import com.example.zed.databinding.ActivityCashTrackerBinding
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import stockAdapter

class cashTracker : AppCompatActivity() {

    private lateinit var binding: ActivityCashTrackerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        //binding.backBtnPhysicalInventory.setOnClickListener { finish() }
        // ✅ --- THIS IS THE FIX ---
        // 1. Inflate the layout and initialize the binding object
        binding = ActivityCashTrackerBinding.inflate(layoutInflater)
        // 2. Set the content view using the root of the binding
        setContentView(binding.root)
        // --- END OF FIX ---


        window.statusBarColor = Color.parseColor("#0071c1")
        val windowInsetsController =
            WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.isAppearanceLightStatusBars = false

        window.navigationBarColor = Color.parseColor("#0071c1")
        windowInsetsController.isAppearanceLightNavigationBars = false


        // Use binding.main instead of findViewById
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupTabs()
    }

    private fun setupTabs() {
        // Set adapter for ViewPager2
        val adapter = cashTrackerAdapter(this)
        binding.tabContent.adapter = adapter

        val tabs = listOf(
            "Expenses",
            "Details",
            "Balancing",
            "User \n Report"
        )

        // Use TabLayoutMediator to link the TabLayout and ViewPager2
        TabLayoutMediator(binding.tabLayoutStock, binding.tabContent) { tab, position ->
            // Inflate your custom view for each tab
            val customTabView = LayoutInflater.from(this).inflate(R.layout.custom_tab_layout, null)
            val tabText = customTabView.findViewById<TextView>(R.id.tabText)
            tabText.text = tabs[position]

            tabText.textSize = 9f

            tab.customView = customTabView
        }.attach()

        // Add the listener for custom styling
        binding.tabLayoutStock.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                tab.customView?.let { view ->
                    view.setBackgroundResource(R.drawable.tab_active)
                    val params = view.layoutParams as ViewGroup.MarginLayoutParams
                    params.topMargin = -35
                    view.layoutParams = params
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {
                tab.customView?.let { view ->
                    view.setBackgroundResource(0) // Remove background
                    val params = view.layoutParams as ViewGroup.MarginLayoutParams
                    params.topMargin = 0 // Reset margin
                    view.layoutParams = params
                }
            }

            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        // Manually apply the active style to the first tab.
        binding.tabLayoutStock.post {
            binding.tabLayoutStock.getTabAt(0)?.customView?.let { view ->
                view.setBackgroundResource(R.drawable.tab_active)
                val params = view.layoutParams as ViewGroup.MarginLayoutParams
                params.topMargin = -35
                view.layoutParams = params
            }
        }
    }
}
