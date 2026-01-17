import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.example.zed.balancing
import com.example.zed.detailsCashTracker
import com.example.zed.detailsStock
import com.example.zed.expenses
import com.example.zed.info
import com.example.zed.inventory_fragment
import com.example.zed.location_and_uom
import com.example.zed.notFoundFragment
import com.example.zed.stock_fragment
import com.example.zed.user_report

class cashTrackerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
    override fun getItemCount(): Int = 4 // Number of tabs/pages

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> expenses()
            1 -> detailsCashTracker()
            2 -> balancing()
            3 -> user_report()
            else -> stock_fragment()
        }
    }
}

