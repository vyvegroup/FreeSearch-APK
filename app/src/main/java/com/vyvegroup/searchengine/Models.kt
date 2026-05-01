package com.vyvegroup.searchengine

data class SearchResult(
    val id: Long = 0,
    val url: String,
    val title: String,
    val snippet: String,
    val domain: String,
    val score: Double = 0.0
)

data class HistoryItem(
    val id: Long = 0,
    val url: String,
    val title: String,
    val visitedAt: Long = 0
)

data class CrawlConfig(
    val maxDepth: Int = 2,
    val maxPages: Int = 500,
    val followExternal: Boolean = false,
    val userAgent: String = "FreeSearchBot/2.0"
)
