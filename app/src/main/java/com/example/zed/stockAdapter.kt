import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.example.zed.detailsStock
import com.example.zed.info
import com.example.zed.inventory_fragment
import com.example.zed.location_and_uom
import com.example.zed.notFoundFragment
import com.example.zed.stock_fragment

class stockAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
    override fun getItemCount(): Int = 3 // Number of tabs/pages

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> stock_fragment()
            1 -> detailsStock()
            2 -> location_and_uom()
            else -> stock_fragment()
        }
    }
}

