package com.example.zed

/**
 * An interface for fragments that can be filtered by a search query.
 */
interface SearchableFragment {
    fun filterData(query: String?)
}
