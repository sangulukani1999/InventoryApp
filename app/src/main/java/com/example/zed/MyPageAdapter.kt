import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.example.zed.info
import com.example.zed.inventory_fragment
import com.example.zed.notFoundFragment

class MyPageAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
    override fun getItemCount(): Int = 3 // Number of tabs/pages

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> info()
            1 -> inventory_fragment()
            2 -> notFoundFragment()
            else -> info()
        }
    }
}
