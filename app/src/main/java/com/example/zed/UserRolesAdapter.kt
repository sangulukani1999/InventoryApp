package com.example.zed

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.zed.databinding.ItemUserRoleBinding

class UserRolesAdapter(
    private val users: List<UserRole>,
    // 1. Add callbacks for edit and delete actions
    private val onEditClicked: (user: UserRole) -> Unit,
    private val onDeleteClicked: (user: UserRole) -> Unit
) : RecyclerView.Adapter<UserRolesAdapter.UserViewHolder>() {

    class UserViewHolder(val binding: ItemUserRoleBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UserViewHolder {
        val binding = ItemUserRoleBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return UserViewHolder(binding)
    }

    override fun getItemCount() = users.size

    override fun onBindViewHolder(holder: UserViewHolder, position: Int) {
        val user = users[position]
        val context = holder.itemView.context

        holder.binding.userEmailText.text = user.email
        holder.binding.userRoleText.text = if (user.isSubUser) "Sub-user" else "Main User"

        // --- NEW LOGIC FOR ROLE INDICATORS ---
        // Map role keys to their corresponding CardView IDs from the new XML
        val roleIndicatorMap = mapOf(
            "stock" to holder.binding.stock,
            "transaction" to holder.binding.transaction,
            "grn" to holder.binding.grn,
            "purchase requisition" to holder.binding.requisition,
            "inventory count" to holder.binding.physicalInventory,
            "cash tracker" to holder.binding.cashTracker
        )

        // Set the color for each role indicator
        roleIndicatorMap.forEach { (roleKey, cardView) ->
            val hasRole = user.roles[roleKey] == true
            val colorRes = if (hasRole) R.color.variance_positive else R.color.variance_negative
            cardView.setCardBackgroundColor(ContextCompat.getColor(context, colorRes))
        }

        // --- SETUP CLICK LISTENERS for Edit and Delete ---
        holder.binding.editUserRole.setOnClickListener {
            onEditClicked(user)
        }

        holder.binding.deleteUser.setOnClickListener {
            onDeleteClicked(user)
        }
    }
}
