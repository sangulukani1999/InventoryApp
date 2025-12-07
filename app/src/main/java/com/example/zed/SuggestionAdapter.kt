package com.example.zed

import android.content.Context
import android.database.Cursor
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.cursoradapter.widget.CursorAdapter

class SuggestionAdapter(context: Context, cursor: Cursor) :
    CursorAdapter(context, cursor, false) {

    override fun newView(context: Context, cursor: Cursor, parent: ViewGroup): View {
        // This part is correct: it inflates your custom layout file.
        return LayoutInflater.from(context).inflate(R.layout.suggestion_item_layout, parent, false)
    }

    /**
     * This function binds the data from the cursor to the view.
     */
    override fun bindView(view: View, context: Context, cursor: Cursor) {
        // ✅ 1. CORRECT ID: Use the ID of the TextView from your XML layout.
        val textView = view.findViewById<TextView>(R.id.suggestion_text)

        // Column index 1 is "productName" from the MatrixCursor in stockTaking.kt
        val text = cursor.getString(1)

        // ✅ 2. CORRECT METHOD: Use the `.text` property to set the text.
        textView.text = text
    }
}
