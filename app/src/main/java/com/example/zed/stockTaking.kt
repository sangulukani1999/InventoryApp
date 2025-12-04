package com.example.zed

import MyPageAdapter
import android.annotation.SuppressLint
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import java.io.IOException

class stockTaking : AppCompatActivity() {
    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_stock_taking)

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

        // --- START: NEW BUTTON CLICK LISTENER ---
        // Assuming you have a button with the id 'varianceAdd' in your activity_stock_taking.xml
        val varianceAddButton = findViewById<CardView>(R.id.varianceAdd) // Or whatever your button type is
        varianceAddButton.setOnClickListener {
            handleVarianceAddClick()
        }
        // --- END: NEW BUTTON CLICK LISTENER ---

        // TabLayout and ViewPager2
        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)
        val viewPager = findViewById<ViewPager2>(R.id.ViewPager)

        // Set adapter for ViewPager2
        val adapter = MyPageAdapter(this)
        viewPager.adapter = adapter

        val tabs = listOf(
            "Info",
            "Inventory",
            "Not Found"
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

    // --- START: NEW HELPER FUNCTIONS ---
    private fun handleVarianceAddClick() {
        val currentUser = Firebase.auth.currentUser
        if (currentUser?.email == null) {
            Toast.makeText(this, "Cannot add item: User not signed in.", Toast.LENGTH_SHORT).show()
            return
        }

        val userEmail = currentUser.email!!
        val progressDialog = ProgressDialog(this).apply {
            setMessage("Verifying user role...")
            setCancelable(false)
            show()
        }

        // We need to check the user's role to know if they are a main or sub-user
        checkUserRole(userEmail) { exists, parentEmail ->
            progressDialog.dismiss()
            if (exists) {
                // The onStockAdded callback is left empty for now, but you could use it to refresh data
                val bottomSheet = bottom_sheet_commit(userEmail, parentEmail) {
                    // This block is executed after a stock item is successfully added.
                    // You might want to refresh the data in your ViewPager fragments here.
                }
                bottomSheet.show(supportFragmentManager, "VarianceBottomSheet")
            } else {
                Toast.makeText(this, "Access denied. User not found in registry.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun checkUserRole(email: String, callback: (exists: Boolean, parentEmail: String?) -> Unit) {
        val client = OkHttpClient()
        val request = Request.Builder()
            .url("https://opensheet.elk.sh/1W-LOkSgPPrfhZ_kqfycUvOcGviQplMng6xpc6KBQ9Ik/users")
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { callback(false, null) }
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    runOnUiThread { callback(false, null) }
                    return
                }
                try {
                    val jsonArray = JSONArray(response.body?.string() ?: "")
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        val mainEmail = obj.optString("email").trim()
                        val subEmail = obj.optString("email sub user").trim()
                        if (email.equals(mainEmail, ignoreCase = true)) {
                            runOnUiThread { callback(true, null) }
                            return
                        }
                        if (subEmail.isNotEmpty() && email.equals(subEmail, ignoreCase = true)) {
                            runOnUiThread { callback(true, mainEmail) }
                            return
                        }
                    }
                    runOnUiThread { callback(false, null) }
                } catch (e: JSONException) {
                    Log.e("UserRoleCheck", "JSON parsing error", e)
                    runOnUiThread { callback(false, null) }
                }
            }
        })
    }
    // --- END: NEW HELPER FUNCTIONS ---

    private fun dpToPx(context: Context, dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}
