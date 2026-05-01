package com.vyvegroup.searchengine.indexer

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.vyvegroup.searchengine.data.*
import com.vyvegroup.searchengine.utils.TextUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI

class PageIndexer(private val db: SQLiteDatabase) {

    suspend fun indexPage(page: CrawledPage): Long = withContext(Dispatchers.IO) {
        val domain = try {
            URI(page.url).host ?: ""
        } catch (_: Exception) {
            ""
        }

        val snippet = TextUtils.generateSnippet(page.content, page.title, 300)
        val contentLength = page.content.length

        val values = ContentValues().apply {
            put(SearchDatabase.COL_PAGE_URL, page.url)
            put(SearchDatabase.COL_PAGE_TITLE, page.title)
            put(SearchDatabase.COL_PAGE_CONTENT, page.content)
            put(SearchDatabase.COL_PAGE_SNIPPET, snippet)
            put(SearchDatabase.COL_PAGE_DOMAIN, domain)
            put(SearchDatabase.COL_PAGE_CRAWLED_AT, page.crawledAt)
            put(SearchDatabase.COL_PAGE_CONTENT_LENGTH, contentLength)
            put(SearchDatabase.COL_PAGE_HTTP_STATUS, page.httpStatus)
            put(SearchDatabase.COL_PAGE_CONTENT_TYPE, page.contentType)
        }

        var rowId: Long = -1
        try {
            rowId = db.insertWithOnConflict(
                SearchDatabase.TABLE_PAGES, null, values,
                SQLiteDatabase.CONFLICT_REPLACE
            )
        } catch (_: Exception) {}

        if (rowId > 0) {
            updateStats(crawled = 1, indexed = 1)
        } else {
            updateStats(errors = 1)
        }
        rowId
    }

    suspend fun indexPages(pages: List<CrawledPage>): Int = withContext(Dispatchers.IO) {
        var indexed = 0
        db.beginTransaction()
        try {
            for (page in pages) {
                val domain = try { URI(page.url).host ?: "" } catch (_: Exception) { "" }
                val snippet = TextUtils.generateSnippet(page.content, page.title, 300)

                val values = ContentValues().apply {
                    put(SearchDatabase.COL_PAGE_URL, page.url)
                    put(SearchDatabase.COL_PAGE_TITLE, page.title)
                    put(SearchDatabase.COL_PAGE_CONTENT, page.content)
                    put(SearchDatabase.COL_PAGE_SNIPPET, snippet)
                    put(SearchDatabase.COL_PAGE_DOMAIN, domain)
                    put(SearchDatabase.COL_PAGE_CRAWLED_AT, page.crawledAt)
                    put(SearchDatabase.COL_PAGE_CONTENT_LENGTH, page.content.length)
                    put(SearchDatabase.COL_PAGE_HTTP_STATUS, page.httpStatus)
                    put(SearchDatabase.COL_PAGE_CONTENT_TYPE, page.contentType)
                }

                val rowId = db.insertWithOnConflict(
                    SearchDatabase.TABLE_PAGES, null, values,
                    SQLiteDatabase.CONFLICT_REPLACE
                )
                if (rowId > 0) indexed++
            }
            db.setTransactionSuccessful()
            updateStats(crawled = pages.size, indexed = indexed)
        } catch (_: Exception) {
        } finally {
            db.endTransaction()
        }
        indexed
    }

    suspend fun deletePage(url: String): Boolean = withContext(Dispatchers.IO) {
        val deleted = db.delete(
            SearchDatabase.TABLE_PAGES,
            "${SearchDatabase.COL_PAGE_URL} = ?",
            arrayOf(url)
        )
        deleted > 0
    }

    suspend fun clearAllPages(): Int = withContext(Dispatchers.IO) {
        val count = db.delete(SearchDatabase.TABLE_PAGES, null, null)
        // Rebuild FTS
        db.execSQL("INSERT INTO ${SearchDatabase.TABLE_FTS}(${SearchDatabase.TABLE_FTS}) VALUES('rebuild')")
        count
    }

    suspend fun getTotalPages(): Int = withContext(Dispatchers.IO) {
        var count = 0
        val cursor = db.rawQuery("SELECT COUNT(*) FROM ${SearchDatabase.TABLE_PAGES}", null)
        cursor?.use {
            if (it.moveToFirst()) count = it.getInt(0)
        }
        count
    }

    suspend fun getIndexedDomains(): List<Pair<String, Int>> = withContext(Dispatchers.IO) {
        val results = mutableListOf<Pair<String, Int>>()
        val cursor = db.rawQuery(
            "SELECT ${SearchDatabase.COL_PAGE_DOMAIN}, COUNT(*) as cnt " +
            "FROM ${SearchDatabase.TABLE_PAGES} " +
            "GROUP BY ${SearchDatabase.COL_PAGE_DOMAIN} " +
            "ORDER BY cnt DESC LIMIT 50",
            null
        )
        cursor?.use {
            while (it.moveToNext()) {
                results.add(Pair(it.getString(0), it.getInt(1)))
            }
        }
        results
    }

    private suspend fun updateStats(crawled: Int = 0, indexed: Int = 0, errors: Int = 0) {
        withContext(Dispatchers.IO) {
            try {
                db.execSQL("""
                    UPDATE ${SearchDatabase.TABLE_STATS} SET
                        ${SearchDatabase.COL_STATS_TOTAL_CRAWLED} = ${SearchDatabase.COL_STATS_TOTAL_CRAWLED} + ?,
                        ${SearchDatabase.COL_STATS_TOTAL_INDEXED} = ${SearchDatabase.COL_STATS_TOTAL_INDEXED} + ?,
                        ${SearchDatabase.COL_STATS_TOTAL_ERRORS} = ${SearchDatabase.COL_STATS_TOTAL_ERRORS} + ?,
                        ${SearchDatabase.COL_STATS_LAST_CRAWL} = ?
                    WHERE ${SearchDatabase.COL_STATS_ID} = 1
                """, arrayOf(crawled.toString(), indexed.toString(), errors.toString(), System.currentTimeMillis().toString()))
            } catch (_: Exception) {}
        }
    }
}
