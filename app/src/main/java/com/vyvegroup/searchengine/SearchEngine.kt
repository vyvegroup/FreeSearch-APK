package com.vyvegroup.searchengine

import android.database.sqlite.SQLiteDatabase

class SearchEngine(private val db: SQLiteDatabase) {

    fun search(query: String, maxResults: Int = 50): List<SearchResult> {
        if (query.isBlank()) return emptyList()
        val terms = query.trim().split(Regex("\\s+")).filter { it.length >= 2 }.joinToString(" ") { "$it*" }
        if (terms.isBlank()) return emptyList()

        val results = mutableListOf<SearchResult>()
        try {
            val cursor = db.rawQuery("""
                SELECT p.id, p.url, COALESCE(p.title, ''), 
                       COALESCE(snippet(${SearchDatabase.TABLE_FTS}, 2, '<b>', '</b>', '...', 32), COALESCE(p.snippet, '')),
                       COALESCE(p.domain, ''), bm25(${SearchDatabase.TABLE_FTS})
                FROM ${SearchDatabase.TABLE_FTS} fts
                JOIN ${SearchDatabase.TABLE_PAGES} p ON p.id = fts.rowid
                WHERE ${SearchDatabase.TABLE_FTS} MATCH ?
                ORDER BY rank LIMIT ?
            """, arrayOf(terms, maxResults.toString()))

            cursor?.use {
                while (it.moveToNext()) {
                    results.add(SearchResult(
                        id = it.getLong(0), url = it.getString(1), title = it.getString(2),
                        snippet = it.getString(3), domain = it.getString(4), score = it.getDouble(5)
                    ))
                }
            }
        } catch (_: Exception) {}
        return results
    }

    fun indexPage(url: String, title: String, content: String, domain: String): Long {
        if (content.length < 30) return -1
        val snippet = if (content.length > 300) content.take(300) + "..." else content
        val values = ContentValues().apply {
            put("url", url); put("title", title); put("content", content)
            put("snippet", snippet); put("domain", domain)
            put("crawled_at", System.currentTimeMillis())
            put("content_length", content.length)
        }
        return db.insertWithOnConflict(SearchDatabase.TABLE_PAGES, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun addToQueue(url: String, depth: Int, priority: Int, parentUrl: String = "") {
        val values = ContentValues().apply {
            put("url", url); put("depth", depth); put("priority", priority); put("parent_url", parentUrl)
        }
        db.insertWithOnConflict(SearchDatabase.TABLE_QUEUE, null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun getNextFromQueue(): Triple<String, Int, String>? {
        var result: Triple<String, Int, String>? = null
        val cursor = db.query(SearchDatabase.TABLE_QUEUE, null, "status = ?", arrayOf("pending"), "priority DESC", null, "1")
        cursor?.use {
            if (it.moveToFirst()) {
                result = Triple(
                    it.getString(it.getColumnIndexOrThrow("url")),
                    it.getInt(it.getColumnIndexOrThrow("depth")),
                    it.getString(it.getColumnIndexOrThrow("parent_url"))
                )
            }
        }
        return result
    }

    fun markQueueDone(url: String) {
        db.execSQL("UPDATE ${SearchDatabase.TABLE_QUEUE} SET status = 'done' WHERE url = ?", arrayOf(url))
    }

    fun queueSize(): Int {
        var c = 0
        val cur = db.rawQuery("SELECT COUNT(*) FROM ${SearchDatabase.TABLE_QUEUE} WHERE status = 'pending'", null)
        cur?.use { if (it.moveToFirst()) c = it.getInt(0) }
        return c
    }

    fun pageCount(): Int {
        var c = 0
        val cur = db.rawQuery("SELECT COUNT(*) FROM ${SearchDatabase.TABLE_PAGES}", null)
        cur?.use { if (it.moveToFirst()) c = it.getInt(0) }
        return c
    }

    fun clearAll() {
        db.delete(SearchDatabase.TABLE_PAGES, null, null)
        db.execSQL("INSERT INTO ${SearchDatabase.TABLE_FTS}(${SearchDatabase.TABLE_FTS}) VALUES('rebuild')")
        db.delete(SearchDatabase.TABLE_QUEUE, null, null)
    }

    fun addHistory(url: String, title: String) {
        val values = ContentValues().apply {
            put("url", url); put("title", title); put("visited_at", System.currentTimeMillis())
        }
        db.insert(SearchDatabase.TABLE_HISTORY, null, values)
    }

    fun getHistory(limit: Int = 50): List<HistoryItem> {
        val items = mutableListOf<HistoryItem>()
        val cursor = db.query(SearchDatabase.TABLE_HISTORY, null, null, null, null, null, "visited_at DESC", limit.toString())
        cursor?.use {
            while (it.moveToNext()) {
                items.add(HistoryItem(
                    id = it.getLong(0), url = it.getString(1),
                    title = it.getString(2), visitedAt = it.getLong(3)
                ))
            }
        }
        return items
    }

    fun clearHistory() {
        db.delete(SearchDatabase.TABLE_HISTORY, null, null)
    }
}
