package com.example.zed

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.viewpager2.widget.ViewPager2
import com.example.zed.databinding.ActivityStockListBinding // Import ViewBinding class
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator // Import TabLayoutMediator
import stockAdapter

class stockList : AppCompatActivity() {

    // Use ViewBinding for safer and cleaner view access
    private lateinit var binding: ActivityStockListBinding

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // --- Use ViewBinding to inflate the layout ---
        binding = ActivityStockListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Back button
        binding.backBtnPhysicalInventory.setOnClickListener {
            startActivity(Intent(this, PhysicalInventory::class.java))
            finish()
        }

        // --- Correctly set up TabLayout and ViewPager2 ---
        setupTabs()
    }

    private fun setupTabs() {
        // Set adapter for ViewPager2
        val adapter = stockAdapter(this)
        binding.tabContent.adapter = adapter

        val tabs = listOf(
            "Products",
            "Details",
            "Locations"
        )

        // ✅ Use TabLayoutMediator to link the TabLayout and ViewPager2
        TabLayoutMediator(binding.tabLayoutStock, binding.tabContent) { tab, position ->
            // Inflate your custom view for each tab
            val customTabView = LayoutInflater.from(this).inflate(R.layout.custom_tab_layout, null)
            val tabText = customTabView.findViewById<TextView>(R.id.tabText)
            tabText.text = tabs[position]
            tab.customView = customTabView
        }.attach() // This is the most important call! It links everything.

        // Add the listener for custom styling (like moving the tab up)
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

        // ✅ THIS IS THE FIX: Manually apply the active style to the first tab.
        // The .post block ensures this code runs after the layout is fully drawn.
        binding.tabLayoutStock.post {
            val firstTab = binding.tabLayoutStock.getTabAt(0)
            firstTab?.customView?.let { view ->
                view.setBackgroundResource(R.drawable.tab_active)
                val params = view.layoutParams as ViewGroup.MarginLayoutParams
                params.topMargin = -35
                view.layoutParams = params
            }
        }
    }

    private fun dpToPx(context: Context, dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}
