package com.vyvegroup.searchengine.indexer

import android.database.sqlite.SQLiteDatabase
import com.vyvegroup.searchengine.data.*

class SearchEngine(private val db: SQLiteDatabase) {

    data class SearchOptions(
        val maxResults: Int = 50,
        val offset: Int = 0,
        val domainFilter: String? = null,
        val minLength: Int = 0,
        val sortByDate: Boolean = false
    )

    fun search(query: String, options: SearchOptions = SearchOptions()): List<SearchResult> {
        if (query.isBlank()) return emptyList()

        val searchTerms = query.trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() && it.length >= 2 }
            .map { "$it*" }
            .joinToString(" ")

        if (searchTerms.isBlank()) return emptyList()

        val domainWhere = if (!options.domainFilter.isNullOrBlank()) {
            " AND p.${SearchDatabase.COL_PAGE_DOMAIN} LIKE '%${options.domainFilter}%'"
        } else ""

        val lengthWhere = if (options.minLength > 0) {
            " AND p.${SearchDatabase.COL_PAGE_CONTENT_LENGTH} >= ${options.minLength}"
        } else ""

        val orderBy = if (options.sortByDate) {
            "p.${SearchDatabase.COL_PAGE_CRAWLED_AT} DESC"
        } else {
            "rank"
        }

        val sql = """
            SELECT 
                p.${SearchDatabase.COL_PAGE_ID},
                p.${SearchDatabase.COL_PAGE_URL},
                p.${SearchDatabase.COL_PAGE_TITLE},
                COALESCE(
                    snippet(${SearchDatabase.TABLE_FTS}, 2, '<b>', '</b>', '...', 32),
                    p.${SearchDatabase.COL_PAGE_SNIPPET}
                ) as snippet,
                p.${SearchDatabase.COL_PAGE_DOMAIN},
                bm25(${SearchDatabase.TABLE_FTS}) as score,
                p.${SearchDatabase.COL_PAGE_CONTENT_LENGTH},
                p.${SearchDatabase.COL_PAGE_CRAWLED_AT}
            FROM ${SearchDatabase.TABLE_FTS} fts
            JOIN ${SearchDatabase.TABLE_PAGES} p ON p.${SearchDatabase.COL_PAGE_ID} = fts.rowid
            WHERE ${SearchDatabase.TABLE_FTS} MATCH ?
            $domainWhere
            $lengthWhere
            ORDER BY $orderBy
            LIMIT ? OFFSET ?
        """.trimIndent()

        val results = mutableListOf<SearchResult>()
        try {
            val cursor = db.rawQuery(sql, arrayOf(searchTerms, options.maxResults.toString(), options.offset.toString()))
            cursor?.use {
                while (it.moveToNext()) {
                    results.add(SearchResult(
                        id = it.getLong(0),
                        url = it.getString(1),
                        title = it.getString(2) ?: "",
                        snippet = it.getString(3) ?: "",
                        domain = it.getString(4) ?: "",
                        score = it.getDouble(5),
                        contentLength = it.getInt(6),
                        crawledAt = it.getLong(7)
                    ))
                }
            }
        } catch (_: Exception) {}

        return results
    }

    fun searchExact(query: String, maxResults: Int = 50): List<SearchResult> {
        if (query.isBlank()) return emptyList()
        val escapedQuery = "\"${query.replace("\"", "")}\""
        return search(escapedQuery, SearchOptions(maxResults = maxResults))
    }

    fun getSuggestions(prefix: String, limit: Int = 10): List<String> {
        if (prefix.isBlank() || prefix.length < 2) return emptyList()

        val results = mutableListOf<String>()
        try {
            val cursor = db.rawQuery("""
                SELECT DISTINCT ${SearchDatabase.COL_PAGE_TITLE}
                FROM ${SearchDatabase.TABLE_PAGES}
                WHERE ${SearchDatabase.COL_PAGE_TITLE} LIKE ? 
                  AND ${SearchDatabase.COL_PAGE_TITLE} IS NOT NULL
                  AND length(${SearchDatabase.COL_PAGE_TITLE}) > 3
                ORDER BY ${SearchDatabase.COL_PAGE_CRAWLED_AT} DESC
                LIMIT ?
            """, arrayOf("$prefix%", limit.toString()))

            cursor?.use {
                while (it.moveToNext()) {
                    results.add(it.getString(0))
                }
            }
        } catch (_: Exception) {}
        return results
    }

    fun getRandomPages(limit: Int = 5): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        try {
            val cursor = db.rawQuery("""
                SELECT 
                    ${SearchDatabase.COL_PAGE_ID},
                    ${SearchDatabase.COL_PAGE_URL},
                    ${SearchDatabase.COL_PAGE_TITLE},
                    ${SearchDatabase.COL_PAGE_SNIPPET},
                    ${SearchDatabase.COL_PAGE_DOMAIN},
                    0.0 as score,
                    ${SearchDatabase.COL_PAGE_CONTENT_LENGTH},
                    ${SearchDatabase.COL_PAGE_CRAWLED_AT}
                FROM ${SearchDatabase.TABLE_PAGES}
                WHERE ${SearchDatabase.COL_PAGE_TITLE} IS NOT NULL
                  AND length(${SearchDatabase.COL_PAGE_TITLE}) > 0
                ORDER BY RANDOM()
                LIMIT ?
            """, arrayOf(limit.toString()))

            cursor?.use {
                while (it.moveToNext()) {
                    results.add(SearchResult(
                        id = it.getLong(0),
                        url = it.getString(1),
                        title = it.getString(2) ?: "",
                        snippet = it.getString(3) ?: "",
                        domain = it.getString(4) ?: "",
                        score = 0.0,
                        contentLength = it.getInt(6),
                        crawledAt = it.getLong(7)
                    ))
                }
            }
        } catch (_: Exception) {}
        return results
    }
}
