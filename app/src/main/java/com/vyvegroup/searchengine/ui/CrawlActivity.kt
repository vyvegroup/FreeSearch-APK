package com.vyvegroup.searchengine.ui

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.vyvegroup.searchengine.R
import com.vyvegroup.searchengine.crawler.WebCrawler
import com.vyvegroup.searchengine.data.CrawlConfig
import com.vyvegroup.searchengine.data.SearchDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CrawlActivity : AppCompatActivity() {

    private lateinit var etSeedUrls: EditText
    private lateinit var seekDepth: SeekBar
    private lateinit var tvDepth: TextView
    private lateinit var seekMaxPages: SeekBar
    private lateinit var tvMaxPages: TextView
    private lateinit var switchExternal: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvCrawlStatus: TextView
    private lateinit var tvCrawlLog: TextView
    private lateinit var btnClearData: Button

    private lateinit var database: SearchDatabase
    private lateinit var crawler: WebCrawler
    private val logLines = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crawl)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Crawl Manager"

        database = SearchDatabase(this)
        crawler = WebCrawler(database.writableDatabase)

        initViews()
        setupListeners()
    }

    private fun initViews() {
        etSeedUrls = findViewById(R.id.etSeedUrls)
        seekDepth = findViewById(R.id.seekDepth)
        tvDepth = findViewById(R.id.tvDepth)
        seekMaxPages = findViewById(R.id.seekMaxPages)
        tvMaxPages = findViewById(R.id.tvMaxPages)
        switchExternal = findViewById(R.id.switchExternal)
        btnStart = findViewById(R.id.btnStartCrawl)
        btnStop = findViewById(R.id.btnStopCrawl)
        tvCrawlStatus = findViewById(R.id.tvCrawlStatus)
        tvCrawlLog = findViewById(R.id.tvCrawlLog)
        btnClearData = findViewById(R.id.btnClearData)

        seekDepth.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                tvDepth.text = progress.toString()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        seekMaxPages.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val pages = (progress + 1) * 100
                tvMaxPages.text = pages.toString()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
    }

    private fun setupListeners() {
        btnStart.setOnClickListener { startCrawl() }
        btnStop.setOnClickListener { stopCrawl() }

        btnClearData.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear All Data?")
                .setMessage("This will delete all indexed pages. This cannot be undone.")
                .setPositiveButton("Delete") { _, _ ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        val count = database.writableDatabase.delete(SearchDatabase.TABLE_PAGES, null, null)
                        database.writableDatabase.execSQL("INSERT INTO ${SearchDatabase.TABLE_FTS}(${SearchDatabase.TABLE_FTS}) VALUES('rebuild')")
                        database.writableDatabase.delete(SearchDatabase.TABLE_QUEUE, null, null)
                        database.writableDatabase.execSQL("UPDATE ${SearchDatabase.TABLE_STATS} SET ${SearchDatabase.COL_STATS_TOTAL_CRAWLED}=0, ${SearchDatabase.COL_STATS_TOTAL_INDEXED}=0 WHERE ${SearchDatabase.COL_STATS_ID}=1")
                        launch(Dispatchers.Main) {
                            addLog("Cleared $count pages")
                            Toast.makeText(this@CrawlActivity, "All data cleared", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun startCrawl() {
        val urlsText = etSeedUrls.text.toString().trim()
        if (urlsText.isBlank()) {
            etSeedUrls.error = "Enter at least one URL"
            return
        }

        val urls = urlsText.split("\n")
            .map { it.trim() }
            .filter { it.isNotBlank() && (it.startsWith("http://") || it.startsWith("https://")) }

        if (urls.isEmpty()) {
            Toast.makeText(this, "Enter valid URLs starting with http:// or https://", Toast.LENGTH_LONG).show()
            return
        }

        val config = CrawlConfig(
            maxDepth = seekDepth.progress,
            maxPagesTotal = (seekMaxPages.progress + 1) * 100,
            followExternalLinks = switchExternal.isChecked
        )

        crawler = WebCrawler(database.writableDatabase, config)

        btnStart.isEnabled = false
        btnStop.isEnabled = true
        etSeedUrls.isEnabled = false
        logLines.clear()
        tvCrawlLog.text = ""
        addLog("Starting crawl of ${urls.size} URL(s)...")

        crawler.startCrawl(urls, { event ->
            lifecycleScope.launch(Dispatchers.Main) {
                when (event) {
                    is WebCrawler.CrawlEvent.Progress -> {
                        tvCrawlStatus.text = "Crawled: ${event.crawled} | Indexed: ${event.indexed} | Errors: ${event.errors}"
                    }
                    is WebCrawler.CrawlEvent.PageFound -> {
                        addLog("[+] ${event.title.take(60)}")
                    }
                    is WebCrawler.CrawlEvent.Error -> {
                        addLog("[!] ${event.url.take(50)}: ${event.message}")
                    }
                    is WebCrawler.CrawlEvent.Log -> {
                        addLog(event.message)
                    }
                    is WebCrawler.CrawlEvent.Completed -> {
                        addLog("Done! Indexed: ${event.totalIndexed}, Errors: ${event.totalErrors}")
                        tvCrawlStatus.text = "Completed! Indexed: ${event.totalIndexed} pages"
                        btnStart.isEnabled = true
                        btnStop.isEnabled = false
                        etSeedUrls.isEnabled = true
                    }
                }
            }
        }, lifecycleScope)
    }

    private fun stopCrawl() {
        crawler.stopCrawl()
        btnStart.isEnabled = true
        btnStop.isEnabled = false
        etSeedUrls.isEnabled = true
        addLog("Crawl stopped by user")
    }

    private fun addLog(msg: String) {
        logLines.add(msg)
        if (logLines.size > 100) logLines.removeAt(0)
        tvCrawlLog.text = logLines.takeLast(30).joinToString("\n")
    }

    override fun onDestroy() {
        crawler.stopCrawl()
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
