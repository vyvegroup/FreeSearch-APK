package com.vyvegroup.searchengine.ui

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.vyvegroup.searchengine.data.SearchDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class StatsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stats)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Index Statistics"

        loadStats()
    }

    private fun loadStats() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = SearchDatabase(this@StatsActivity).readableDatabase

            // Total pages
            var totalPages = 0
            val c1 = db.rawQuery("SELECT COUNT(*) FROM ${SearchDatabase.TABLE_PAGES}", null)
            c1?.use { if (it.moveToFirst()) totalPages = it.getInt(0) }

            // Total content size
            var totalSize = 0L
            val c2 = db.rawQuery("SELECT COALESCE(SUM(${SearchDatabase.COL_PAGE_CONTENT_LENGTH}), 0) FROM ${SearchDatabase.TABLE_PAGES}", null)
            c2?.use { if (it.moveToFirst()) totalSize = it.getLong(0) }

            // Domains
            val domains = mutableListOf<Pair<String, Int>>()
            val c3 = db.rawQuery("""
                SELECT ${SearchDatabase.COL_PAGE_DOMAIN}, COUNT(*) as cnt 
                FROM ${SearchDatabase.TABLE_PAGES} 
                GROUP BY ${SearchDatabase.COL_PAGE_DOMAIN} 
                ORDER BY cnt DESC LIMIT 50
            """, null)
            c3?.use {
                while (it.moveToNext()) {
                    domains.add(Pair(it.getString(0), it.getInt(1)))
                }
            }

            // Queue size
            var queueSize = 0
            val c4 = db.rawQuery("SELECT COUNT(*) FROM ${SearchDatabase.TABLE_QUEUE} WHERE ${SearchDatabase.COL_QUEUE_STATUS} = 'pending'", null)
            c4?.use { if (it.moveToFirst()) queueSize = it.getInt(0) }

            // Avg content length
            var avgLen = 0.0
            val c5 = db.rawQuery("SELECT COALESCE(AVG(${SearchDatabase.COL_PAGE_CONTENT_LENGTH}), 0) FROM ${SearchDatabase.TABLE_PAGES}", null)
            c5?.use { if (it.moveToFirst()) avgLen = it.getDouble(0) }

            launch(Dispatchers.Main) {
                val sb = StringBuilder()
                sb.appendLine("Total Pages: $totalPages")
                sb.appendLine("Total Content: ${formatSize(totalSize)}")
                sb.appendLine("Avg Page Size: ${formatSize(avgLen.toLong())}")
                sb.appendLine("Queue Pending: $queueSize")
                sb.appendLine("Domains: ${domains.size}")
                sb.appendLine()
                sb.appendLine("── Top Domains ──")
                domains.take(20).forEach { (domain, count) ->
                    sb.appendLine("  $domain: $count pages")
                }

                findViewById<TextView>(android.R.id.text1)?.let { v ->
                    v.text = sb.toString()
                    v.textSize = 14f
                    v.setPadding(24, 24, 24, 24)
                }
            }
        }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
