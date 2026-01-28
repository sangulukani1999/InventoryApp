package com.example.zed

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

/**
 * A simple FragmentStateAdapter that manages the tabs: Products, Details, and Locations.
 * It also holds references to the created fragments so the parent activity can communicate with them.
 */
class stockAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    // A map to hold references to the fragments created by the adapter.
    val fragments = mutableMapOf<Int, Fragment>()

    override fun getItemCount(): Int = 3 // We have 3 tabs

    override fun createFragment(position: Int): Fragment {
        val fragment = when (position) {
            0 -> stock_fragment() // Your list of products
            1 -> detailsStock()     // Your details fragment
            2 -> location_and_uom() // Your locations fragment
            else -> throw IllegalStateException("Invalid position for fragment: $position")
        }
        // Store the newly created fragment instance in our map.
        fragments[position] = fragment
        return fragment
    }

    /**
     * A helper function to safely retrieve a fragment at a specific position.
     */
    fun getFragment(position: Int): Fragment? {
        return fragments[position]
    }
}
