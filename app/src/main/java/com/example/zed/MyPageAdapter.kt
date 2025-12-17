

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.example.zed.inventory_fragment
import com.example.zed.notFoundFragment

class MyPageAdapter(fragmentActivity: FragmentActivity) : FragmentStateAdapter(fragmentActivity) {

    // ✅ CORRECTED FUNCTION
    override fun getItemCount(): Int {
        // Now the ViewPager will only create 2 pages.
        return 2
    }

    // ✅ CORRECTED FUNCTION
    override fun createFragment(position: Int): Fragment {
        // Now it only knows how to create two fragments.
        return when (position) {
            0 -> inventory_fragment()
            1 -> notFoundFragment()
            else -> throw IllegalStateException("Invalid position $position")
        }
    }
}

