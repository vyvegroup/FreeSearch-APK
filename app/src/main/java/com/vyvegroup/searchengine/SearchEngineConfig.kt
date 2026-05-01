package com.vyvegroup.searchengine

object SearchEngineConfig {

    enum class Engine(val displayName: String, val baseUrl: String) {
        DUCKDUCKGO("DuckDuckGo", "https://lite.duckduckgo.com/lite/?q="),
        BRAVE("Brave Search", "https://search.brave.com/search?q="),
        STARTPAGE("Startpage", "https://www.startpage.com/sp/search?query="),
        QWANT("Qwant", "https://www.qwant.com/?q="),
        CUSTOM("Custom", "")
    }

    data class SearchConfig(
        val engine: Engine = Engine.DUCKDUCKGO,
        val customUrl: String = "",
        val darkMode: Boolean = false,
        val javaScriptEnabled: Boolean = true
    )

    fun buildSearchUrl(query: String, config: SearchConfig): String {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        return if (config.engine == Engine.CUSTOM && config.customUrl.isNotBlank()) {
            val url = config.customUrl.trimEnd('/')
            if (url.contains("%s")) {
                url.replace("%s", encodedQuery)
            } else {
                "$url/$encodedQuery"
            }
        } else {
            "${config.engine.baseUrl}$encodedQuery"
        }
    }

    fun getMobileUserAgent(): String {
        return "Mozilla/5.0 (Linux; Android 12; SM-G991B) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }

    fun getDesktopUserAgent(): String {
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
