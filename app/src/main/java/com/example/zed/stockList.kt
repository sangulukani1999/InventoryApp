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
import com.google.android.material.tabs.TabLayout
import stockAdapter

class stockList : AppCompatActivity() {
    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_stock_list)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Back button
        val backButton = findViewById<ImageButton>(R.id.backBtnPhysicalInventory)
        backButton.setOnClickListener {
            startActivity(Intent(this, PhysicalInventory::class.java))
            finish()
        }

        // TabLayout and ViewPager2
        val tabLayout = findViewById<TabLayout>(R.id.tabLayoutStock)
        val viewPager = findViewById<ViewPager2>(R.id.tabContent)

        // Set adapter for ViewPager2
        val adapter = stockAdapter(this)
        viewPager.adapter = adapter

        val tabs = listOf(
            "Products",
            "Details",
            "Locations"
        )

        // Add custom tabs
        for (i in tabs.indices) {
            val tab = tabLayout.newTab()
            val view = LayoutInflater.from(this).inflate(R.layout.custom_tab_layout, null)
            val tabText = view.findViewById<TextView>(R.id.tabText)
            tabText.text = tabs[i]
            tab.customView = view
            tabLayout.addTab(tab)
        }

        // Sync ViewPager -> TabLayout
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                tabLayout.getTabAt(position)?.select()
            }
        })

        // Sync TabLayout -> ViewPager
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                viewPager.currentItem = tab.position // <-- important!
                tab.customView?.let { view ->
                    view.setBackgroundResource(R.drawable.tab_active)
                    val params = view.layoutParams as ViewGroup.MarginLayoutParams
                    params.topMargin = -35
                    when (tab.position) {
                        0 -> {
                            params.marginStart = dpToPx(tabLayout.context, 0)
                            params.marginEnd = 0
                        }
                        tabLayout.tabCount - 1 -> {
                            params.marginStart = 0
                            params.marginEnd = dpToPx(tabLayout.context, 3)
                        }
                        else -> {
                            params.marginStart = 0
                            params.marginEnd = 0
                        }
                    }
                    view.layoutParams = params
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {
                tab.customView?.let { view ->
                    view.setBackgroundResource(0)
                    val params = view.layoutParams as ViewGroup.MarginLayoutParams
                    params.topMargin = 0
                    when (tab.position) {
                        0 -> {
                            params.marginStart = dpToPx(tabLayout.context, 20)
                            params.marginEnd = 0
                        }
                        tabLayout.tabCount - 1 -> {
                            params.marginStart = 0
                            params.marginEnd = dpToPx(tabLayout.context, 20)
                        }
                        else -> {
                            params.marginStart = 0
                            params.marginEnd = 0
                        }
                    }
                    view.layoutParams = params
                }
            }

            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun dpToPx(context: Context, dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}