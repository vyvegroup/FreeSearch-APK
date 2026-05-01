package com.vyvegroup.searchengine.crawler

import android.database.sqlite.SQLiteDatabase
import com.vyvegroup.searchengine.data.*
import com.vyvegroup.searchengine.utils.TextUtils
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class WebCrawler(
    private val db: SQLiteDatabase,
    private val config: CrawlConfig = CrawlConfig()
) {
    sealed class CrawlEvent {
        data class Progress(val crawled: Int, val indexed: Int, val errors: Int, val url: String) : CrawlEvent()
        data class PageFound(val url: String, val title: String) : CrawlEvent()
        data class Error(val url: String, val message: String) : CrawlEvent()
        data class Completed(val totalCrawled: Int, val totalIndexed: Int, val totalErrors: Int) : CrawlEvent()
        data class Log(val message: String) : CrawlEvent()
    }

    private val isRunning = kotlinx.coroutines.flow.MutableStateFlow(false)
    private val crawledDomains = ConcurrentHashMap<String, AtomicInteger>()
    private var job: Job? = null

    val running: kotlinx.coroutines.flow.StateFlow<Boolean> = isRunning

    fun startCrawl(
        seedUrls: List<String>,
        onEvent: (CrawlEvent) -> Unit,
        scope: CoroutineScope
    ) {
        if (isRunning.value) {
            onEvent(CrawlEvent.Log("Crawl already running"))
            return
        }

        job = scope.launch(Dispatchers.IO) {
            isRunning.value = true
            crawledDomains.clear()

            val totalCrawled = AtomicInteger(0)
            val totalIndexed = AtomicInteger(0)
            val totalErrors = AtomicInteger(0)

            onEvent(CrawlEvent.Log("Starting crawl with ${seedUrls.size} seed URL(s)"))

            // Add seed URLs to queue
            addUrlsToQueue(seedUrls.mapIndexed { i, url -> CrawlTask(url = url, depth = 0, priority = seedUrls.size - i) })

            try {
                while (isActive && isRunning.value) {
                    val task = getNextTask() ?: break
                    if (totalCrawled.get() >= config.maxPagesTotal) {
                        onEvent(CrawlEvent.Log("Reached max pages limit (${config.maxPagesTotal})"))
                        break
                    }

                    // Check domain limit
                    val domain = TextUtils.extractDomain(task.url)
                    val domainCount = crawledDomains.getOrPut(domain) { AtomicInteger(0) }
                    if (domainCount.get() >= config.maxPagesPerDomain) {
                        markTaskDone(task.url, "skipped")
                        continue
                    }

                    // Check extension exclusion
                    if (config.excludedExtensions.any { task.url.endsWith(it, ignoreCase = true) }) {
                        markTaskDone(task.url, "skipped")
                        continue
                    }

                    // Delay between requests
                    if (totalCrawled.get() > 0) {
                        delay(config.delayMs)
                    }

                    try {
                        val page = crawlPage(task.url)
                        if (page != null) {
                            // Index the page
                            val contentValues = android.content.ContentValues().apply {
                                put(SearchDatabase.COL_PAGE_URL, page.url)
                                put(SearchDatabase.COL_PAGE_TITLE, page.title)
                                put(SearchDatabase.COL_PAGE_CONTENT, page.content)
                                put(SearchDatabase.COL_PAGE_SNIPPET, page.snippet)
                                put(SearchDatabase.COL_PAGE_DOMAIN, page.domain)
                                put(SearchDatabase.COL_PAGE_CRAWLED_AT, page.crawledAt)
                                put(SearchDatabase.COL_PAGE_CONTENT_LENGTH, page.contentLength)
                                put(SearchDatabase.COL_PAGE_HTTP_STATUS, page.httpStatus)
                                put(SearchDatabase.COL_PAGE_CONTENT_TYPE, page.contentType)
                            }

                            val rowId = db.insertWithOnConflict(
                                SearchDatabase.TABLE_PAGES, null, contentValues,
                                SQLiteDatabase.CONFLICT_REPLACE
                            )

                            if (rowId > 0) {
                                totalIndexed.incrementAndGet()
                                domainCount.incrementAndGet()
                                onEvent(CrawlEvent.PageFound(page.url, page.title))
                            }

                            // Extract and queue links
                            if (task.depth < config.maxDepth) {
                                val links = extractLinks(page.content, page.url)
                                val newLinks = links.filter { link ->
                                    shouldCrawl(link, task.url) &&
                                    !isUrlInQueue(link) &&
                                    !isUrlCrawled(link)
                                }
                                addUrlsToQueue(newLinks.map { link ->
                                    CrawlTask(url = link, depth = task.depth + 1, priority = 0, parentUrl = task.url)
                                })
                                if (newLinks.isNotEmpty()) {
                                    onEvent(CrawlEvent.Log("Found ${newLinks.size} new link(s) from ${TextUtils.extractDomain(page.url)}"))
                                }
                            }
                        }

                        markTaskDone(task.url, "done")
                        totalCrawled.incrementAndGet()

                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        markTaskDone(task.url, "error")
                        totalErrors.incrementAndGet()
                        val msg = when (e) {
                            is SocketTimeoutException -> "Timeout"
                            is UnknownHostException -> "Host not found"
                            else -> e.message?.take(100) ?: "Unknown error"
                        }
                        onEvent(CrawlEvent.Error(task.url, msg))
                    }

                    // Report progress
                    onEvent(CrawlEvent.Progress(
                        crawled = totalCrawled.get(),
                        indexed = totalIndexed.get(),
                        errors = totalErrors.get(),
                        url = task.url
                    ))
                }
            } finally {
                isRunning.value = false
                onEvent(CrawlEvent.Completed(
                    totalCrawled = totalCrawled.get(),
                    totalIndexed = totalIndexed.get(),
                    totalErrors = totalErrors.get()
                ))
            }
        }
    }

    fun stopCrawl() {
        isRunning.value = false
        job?.cancel()
    }

    private suspend fun crawlPage(url: String): CrawledPage? = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.connect(url)
                .userAgent(config.userAgent)
                .timeout(config.timeoutMs.toInt())
                .followRedirects(true)
                .maxBodySize(config.maxContentSize)
                .ignoreHttpErrors(true)
                .get()

            val title = doc.title()?.trim() ?: TextUtils.extractTitle(doc.html(), url)
            val text = doc.body()?.text()?.trim() ?: ""
            val html = doc.html()
            val domain = TextUtils.extractDomain(url)
            val contentType = doc.connection().response().contentType() ?: "text/html"
            val httpStatus = doc.connection().response().statusCode()

            if (text.isBlank() || text.length < 50) return@withContext null

            val content = TextUtils.cleanText(html)
            CrawledPage(
                url = url,
                title = title,
                content = content,
                snippet = TextUtils.generateSnippet(content, title, 300),
                domain = domain,
                crawledAt = System.currentTimeMillis(),
                contentLength = content.length,
                httpStatus = httpStatus,
                contentType = contentType
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun extractLinks(html: String, baseUrl: String): List<String> {
        val links = mutableListOf<String>()
        try {
            val doc = Jsoup.parse(html, baseUrl)
            val elements = doc.select("a[href]")
            for (el in elements) {
                val href = el.attr("abs:href").trim()
                if (TextUtils.isValidUrl(href)) {
                    // Remove fragment
                    val cleanUrl = href.split("#").firstOrNull() ?: href
                    if (cleanUrl.isNotBlank() && cleanUrl != baseUrl) {
                        links.add(cleanUrl)
                    }
                }
            }
        } catch (_: Exception) {}
        return links.distinct()
    }

    private fun shouldCrawl(url: String, parentUrl: String): Boolean {
        if (!config.allowedSchemes.any { url.startsWith("$it://") }) return false
        if (!config.followExternalLinks && !TextUtils.isSameDomain(url, parentUrl)) return false
        if (config.excludedExtensions.any { url.endsWith(it, ignoreCase = true) }) return false
        return true
    }

    private fun addUrlsToQueue(tasks: List<CrawlTask>) {
        db.beginTransaction()
        try {
            for (task in tasks) {
                if (isUrlInQueue(task.url)) continue
                val values = android.content.ContentValues().apply {
                    put(SearchDatabase.COL_QUEUE_URL, task.url)
                    put(SearchDatabase.COL_QUEUE_DEPTH, task.depth)
                    put(SearchDatabase.COL_QUEUE_PRIORITY, task.priority)
                    put(SearchDatabase.COL_QUEUE_STATUS, task.status)
                    put(SearchDatabase.COL_QUEUE_ADDED_AT, task.addedAt)
                    put(SearchDatabase.COL_QUEUE_PARENT_URL, task.parentUrl)
                }
                db.insertWithOnConflict(SearchDatabase.TABLE_QUEUE, null, values, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } catch (_: Exception) {
        } finally {
            db.endTransaction()
        }
    }

    private fun getNextTask(): CrawlTask? {
        var task: CrawlTask? = null
        val cursor = db.query(
            SearchDatabase.TABLE_QUEUE,
            null,
            "${SearchDatabase.COL_QUEUE_STATUS} = ?",
            arrayOf("pending"),
            "${SearchDatabase.COL_QUEUE_PRIORITY} DESC",
            null,
            "1"
        )
        cursor?.use {
            if (it.moveToFirst()) {
                task = CrawlTask(
                    id = it.getLong(it.getColumnIndexOrThrow(SearchDatabase.COL_QUEUE_ID)),
                    url = it.getString(it.getColumnIndexOrThrow(SearchDatabase.COL_QUEUE_URL)),
                    depth = it.getInt(it.getColumnIndexOrThrow(SearchDatabase.COL_QUEUE_DEPTH)),
                    priority = it.getInt(it.getColumnIndexOrThrow(SearchDatabase.COL_QUEUE_PRIORITY)),
                    status = it.getString(it.getColumnIndexOrThrow(SearchDatabase.COL_QUEUE_STATUS)),
                    addedAt = it.getLong(it.getColumnIndexOrThrow(SearchDatabase.COL_QUEUE_ADDED_AT)),
                    parentUrl = it.getString(it.getColumnIndexOrThrow(SearchDatabase.COL_QUEUE_PARENT_URL))
                )
            }
        }
        return task
    }

    private fun markTaskDone(url: String, status: String) {
        val values = android.content.ContentValues().apply {
            put(SearchDatabase.COL_QUEUE_STATUS, status)
        }
        db.update(SearchDatabase.TABLE_QUEUE, values, "${SearchDatabase.COL_QUEUE_URL} = ?", arrayOf(url))
    }

    private fun isUrlInQueue(url: String): Boolean {
        var exists = false
        val cursor = db.query(
            SearchDatabase.TABLE_QUEUE,
            arrayOf("1"),
            "${SearchDatabase.COL_QUEUE_URL} = ?",
            arrayOf(url),
            null, null, null, "1"
        )
        cursor?.use {
            exists = it.count > 0
        }
        return exists
    }

    private fun isUrlCrawled(url: String): Boolean {
        var exists = false
        val cursor = db.query(
            SearchDatabase.TABLE_PAGES,
            arrayOf("1"),
            "${SearchDatabase.COL_PAGE_URL} = ?",
            arrayOf(url),
            null, null, null, "1"
        )
        cursor?.use {
            exists = it.count > 0
        }
        return exists
    }

    fun getQueueSize(): Int {
        var count = 0
        val cursor = db.rawQuery(
            "SELECT COUNT(*) FROM ${SearchDatabase.TABLE_QUEUE} WHERE ${SearchDatabase.COL_QUEUE_STATUS} = 'pending'",
            null
        )
        cursor?.use { if (it.moveToFirst()) count = it.getInt(0) }
        return count
    }

    fun clearQueue() {
        db.delete(SearchDatabase.TABLE_QUEUE, null, null)
    }
}
