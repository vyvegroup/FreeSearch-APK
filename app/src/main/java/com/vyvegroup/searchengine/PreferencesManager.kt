package com.vyvegroup.searchengine

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    var currentEngine: String
        get() = prefs.getString("engine", SearchEngineConfig.Engine.DUCKDUCKGO.name)
            ?: SearchEngineConfig.Engine.DUCKDUCKGO.name
        set(value) = prefs.edit().putString("engine", value).apply()

    var customUrl: String
        get() = prefs.getString("custom_url", "") ?: ""
        set(value) = prefs.edit().putString("custom_url", value).apply()

    var darkMode: Boolean
        get() = prefs.getBoolean("dark_mode", false)
        set(value) = prefs.edit().putBoolean("dark_mode", value).apply()

    var javaScriptEnabled: Boolean
        get() = prefs.getBoolean("js_enabled", true)
        set(value) = prefs.edit().putBoolean("js_enabled", value).apply()

    fun getSearchConfig(): SearchEngineConfig.SearchConfig {
        return SearchEngineConfig.SearchConfig(
            engine = try {
                SearchEngineConfig.Engine.valueOf(currentEngine)
            } catch (_: Exception) {
                SearchEngineConfig.Engine.DUCKDUCKGO
            },
            customUrl = customUrl,
            darkMode = darkMode,
            javaScriptEnabled = javaScriptEnabled
        )
    }
}
