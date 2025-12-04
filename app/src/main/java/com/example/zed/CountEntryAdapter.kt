package com.example.zed

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class CountEntryAdapter(
    private val items: MutableList<CountEntry>,
    private val onItemDeleted: (position: Int) -> Unit
) : RecyclerView.Adapter<CountEntryAdapter.CountEntryViewHolder>() {

    inner class CountEntryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val name: TextView = itemView.findViewById(R.id.productNameStockTaking)
        val aisle: TextView = itemView.findViewById(R.id.AisleStockTaking)
        val rack: TextView = itemView.findViewById(R.id.RackStockTaking)
        val shelf: TextView = itemView.findViewById(R.id.ShelfStockTaking)
        val qty: TextView = itemView.findViewById(R.id.QtyStockTaking)
        val deleteBtn: ImageView = itemView.findViewById(R.id.deleteStockTaking)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CountEntryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.inventory_stock_taking_item, parent, false)
        return CountEntryViewHolder(view)
    }

    override fun onBindViewHolder(holder: CountEntryViewHolder, position: Int) {
        val entry = items[position]
        holder.name.text = entry.productName
        holder.aisle.text = entry.aisle
        holder.rack.text = entry.rack
        holder.shelf.text = entry.shelf
        holder.qty.text = entry.quantity.toString()

        holder.deleteBtn.setOnClickListener {
            // CRITICAL POINT 1: This lambda is called from the adapter
            // It uses holder.adapterPosition to ensure it gets the most current position,
            // which is good practice, especially if items can be added/removed rapidly.
            Log.d("CountEntryAdapter", "Delete clicked for adapterPosition: ${holder.adapterPosition}")

            onItemDeleted(holder.adapterPosition)
        }
    }

    override fun getItemCount() = items.size
}
