package com.vyvegroup.searchengine.data

data class CrawledPage(
    val id: Long = 0,
    val url: String,
    val title: String = "",
    val content: String = "",
    val snippet: String = "",
    val domain: String = "",
    val crawledAt: Long = System.currentTimeMillis(),
    val contentLength: Int = 0,
    val httpStatus: Int = 200,
    val contentType: String = "text/html"
)

data class SearchResult(
    val id: Long = 0,
    val url: String,
    val title: String,
    val snippet: String,
    val domain: String,
    val score: Double = 0.0,
    val contentLength: Int = 0,
    val crawledAt: Long = 0
)

data class CrawlTask(
    val id: Long = 0,
    val url: String,
    val depth: Int = 0,
    val priority: Int = 0,
    val status: String = "pending",
    val addedAt: Long = System.currentTimeMillis(),
    val parentUrl: String = ""
)

data class CrawlStats(
    val totalCrawled: Int = 0,
    val totalIndexed: Int = 0,
    val totalErrors: Int = 0,
    val startTime: Long = 0,
    val lastCrawl: Long = 0
)

data class CrawlConfig(
    val maxDepth: Int = 2,
    val maxPagesPerDomain: Int = 500,
    val maxPagesTotal: Int = 10000,
    val maxContentSize: Int = 2 * 1024 * 1024, // 2MB per page
    val followExternalLinks: Boolean = false,
    val respectRobotsTxt: Boolean = false,
    val userAgent: String = "FreeSearchBot/2.0 (+https://github.com/vyvegroup/FreeSearch-APK)",
    val timeoutMs: Long = 15000,
    val delayMs: Long = 500,
    val maxConcurrent: Int = 3,
    val allowedSchemes: List<String> = listOf("http", "https"),
    val excludedExtensions: List<String> = listOf(
        ".jpg", ".jpeg", ".png", ".gif", ".svg", ".webp", ".ico",
        ".mp3", ".mp4", ".avi", ".mov", ".wmv", ".flv",
        ".zip", ".rar", ".7z", ".tar", ".gz",
        ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt",
        ".exe", ".dmg", ".apk", ".msi"
    )
)
