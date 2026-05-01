package com.vyvegroup.searchengine

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class SearchHistoryManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("search_history", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun addQuery(query: String) {
        if (query.isBlank()) return
        val history = getHistory().toMutableList()
        history.remove(query)
        history.add(0, query)
        if (history.size > 100) history.removeLast()
        val json = gson.toJson(history)
        prefs.edit().putString("queries", json).apply()
    }

    fun getHistory(): List<String> {
        val json = prefs.getString("queries", null) ?: return emptyList()
        val type = object : TypeToken<List<String>>() {}.type
        return try { gson.fromJson(json, type) } catch (_: Exception) { emptyList() }
    }

    fun clearHistory() {
        prefs.edit().remove("queries").apply()
    }
}
