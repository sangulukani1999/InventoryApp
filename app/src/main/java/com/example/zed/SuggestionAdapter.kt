package com.example.zed

import android.content.Context
import android.database.Cursor
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.cursoradapter.widget.CursorAdapter

// REMOVED 'private' to make it a public, reusable class
class SuggestionAdapter(context: Context, cursor: Cursor) :
    CursorAdapter(context, cursor, false) {

    override fun newView(context: Context, cursor: Cursor, parent: ViewGroup): View {
        return LayoutInflater.from(context).inflate(android.R.layout.simple_list_item_1, parent, false)
    }

    override fun bindView(view: View, context: Context, cursor: Cursor) {
        val textView = view.findViewById<TextView>(android.R.id.text1)
        val text = cursor.getString(1) // Column index 1 is "productName"
        textView.text = text
    }
}
