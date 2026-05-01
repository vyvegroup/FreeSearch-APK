package com.vyvegroup.freesearch

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jsoup.Jsoup
import java.net.URI

// ========== DATABASE ==========
class SearchDB(ctx: Context) : SQLiteOpenHelper(ctx, "freesearch.db", null, 2) {
    companion object {
        const val T_PAGES = "pages"
        const val T_FTS = "pages_fts"
        const val T_QUEUE = "crawl_queue"
        const val T_HISTORY = "browse_history"
    }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS $T_PAGES(id INTEGER PRIMARY KEY AUTOINCREMENT,url TEXT UNIQUE,title TEXT,content TEXT,snippet TEXT,domain TEXT,crawled_at INTEGER)")
        db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS $T_FTS USING fts5(title,content,snippet,domain,content=$T_PAGES,content_rowid=id)")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS pg_ai AFTER INSERT ON $T_PAGES BEGIN INSERT INTO $T_FTS(rowid,title,content,snippet,domain) VALUES(new.id,new.title,new.content,new.snippet,new.domain);END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS pg_au AFTER UPDATE ON $T_PAGES BEGIN INSERT INTO $T_FTS($T_FTS,rowid,title,content,snippet,domain) VALUES('delete',old.id,old.title,old.content,old.snippet,old.domain);INSERT INTO $T_FTS(rowid,title,content,snippet,domain) VALUES(new.id,new.title,new.content,new.snippet,new.domain);END")
        db.execSQL("CREATE TABLE IF NOT EXISTS $T_QUEUE(id INTEGER PRIMARY KEY AUTOINCREMENT,url TEXT UNIQUE,depth INTEGER DEFAULT 0,priority INTEGER DEFAULT 0,status TEXT DEFAULT 'pending')")
        db.execSQL("CREATE TABLE IF NOT EXISTS $T_HISTORY(id INTEGER PRIMARY KEY AUTOINCREMENT,url TEXT,title TEXT,visited_at INTEGER)")
    }
    override fun onUpgrade(db: SQLiteDatabase, ov: Int, nv: Int) {
        db.execSQL("DROP TABLE IF EXISTS $T_FTS"); db.execSQL("DROP TRIGGER IF EXISTS pg_ai"); db.execSQL("DROP TRIGGER IF EXISTS pg_au")
        db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS $T_FTS USING fts5(title,content,snippet,domain,content=$T_PAGES,content_rowid=id)")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS pg_ai AFTER INSERT ON $T_PAGES BEGIN INSERT INTO $T_FTS(rowid,title,content,snippet,domain) VALUES(new.id,new.title,new.content,new.snippet,new.domain);END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS pg_au AFTER UPDATE ON $T_PAGES BEGIN INSERT INTO $T_FTS($T_FTS,rowid,title,content,snippet,domain) VALUES('delete',old.id,old.title,old.content,old.snippet,old.domain);INSERT INTO $T_FTS(rowid,title,content,snippet,domain) VALUES(new.id,new.title,new.content,new.snippet,new.domain);END")
    }
}

// ========== SEARCH ENGINE ==========
class SearchEngine(private val db: SQLiteDatabase) {
    fun search(query: String, max: Int = 50): List<Array<Any>> {
        if (query.isBlank()) return emptyList()
        val terms = query.trim().split(Regex("\\s+")).filter { it.length >= 2 }.joinToString(" ") { "$it*" }
        if (terms.isBlank()) return emptyList()
        val r = mutableListOf<Array<Any>>()
        try {
            val c = db.rawQuery("SELECT p.id,p.url,COALESCE(p.title,''),COALESCE(snippet($T_FTS,2,'<b>','</b>','...',32),COALESCE(p.snippet,'')),COALESCE(p.domain,''),bm25($T_FTS) FROM $T_FTS f JOIN $T_PAGES p ON p.id=f.rowid WHERE $T_FTS MATCH ? ORDER BY rank LIMIT ?", arrayOf(terms, max.toString()))
            c?.use { while (it.moveToNext()) r.add(arrayOf(it.getLong(0), it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getDouble(5))) }
        } catch (_: Exception) {}
        return r
    }
    fun indexPage(url: String, title: String, content: String, domain: String): Long {
        if (content.length < 30) return -1
        val snippet = if (content.length > 300) content.take(300) + "..." else content
        val v = ContentValues().apply { put("url", url); put("title", title); put("content", content); put("snippet", snippet); put("domain", domain); put("crawled_at", System.currentTimeMillis()) }
        return db.insertWithOnConflict(SearchDB.T_PAGES, null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun enqueue(url: String, depth: Int, priority: Int) {
        val v = ContentValues().apply { put("url", url); put("depth", depth); put("priority", priority) }
        db.insertWithOnConflict(SearchDB.T_QUEUE, null, v, SQLiteDatabase.CONFLICT_IGNORE)
    }
    fun dequeue(): Triple<String, Int, String>? {
        var r: Triple<String, Int, String>? = null
        val c = db.query(SearchDB.T_QUEUE, null, "status = ?", arrayOf("pending"), "priority DESC", null, "1")
        c?.use { if (it.moveToFirst()) r = Triple(it.getString(1), it.getInt(2), "") }
        return r
    }
    fun doneQueue(url: String) { db.execSQL("UPDATE ${SearchDB.T_QUEUE} SET status='done' WHERE url=?", arrayOf(url)) }
    fun queueSize(): Int { var c = 0; val cur = db.rawQuery("SELECT COUNT(*) FROM ${SearchDB.T_QUEUE} WHERE status='pending'", null); cur?.use { if (it.moveToFirst()) c = it.getInt(0) }; return c }
    fun pageCount(): Int { var c = 0; val cur = db.rawQuery("SELECT COUNT(*) FROM ${SearchDB.T_PAGES}", null); cur?.use { if (it.moveToFirst()) c = it.getInt(0) }; return c }
    fun clearAll() { db.delete(SearchDB.T_PAGES, null, null); db.execSQL("INSERT INTO ${SearchDB.T_FTS}(${SearchDB.T_FTS}) VALUES('rebuild')"); db.delete(SearchDB.T_QUEUE, null, null) }
    fun addHistory(url: String, title: String) { val v = ContentValues().apply { put("url", url); put("title", title); put("visited_at", System.currentTimeMillis()) }; db.insert(SearchDB.T_HISTORY, null, v) }
    fun getHistory(limit: Int = 50): List<Array<Any>> {
        val items = mutableListOf<Array<Any>>()
        val c = db.query(SearchDB.T_HISTORY, null, null, null, null, null, "visited_at DESC", limit.toString())
        c?.use { while (it.moveToNext()) items.add(arrayOf(it.getLong(0), it.getString(1), it.getString(2), it.getLong(3))) }
        return items
    }
    fun clearHistory() { db.delete(SearchDB.T_HISTORY, null, null) }
}

// ========== MAIN ACTIVITY ==========
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var urlBar: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var searchEngine: SearchEngine
    private var isCrawling = false
    private var currentUrl = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        searchEngine = SearchEngine(SearchDB(this).writableDatabase)

        // Build layout programmatically to avoid XML R-reference issues
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FFF5F5F5"))
        }

        // === TOP BAR (Chrome-style) ===
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#FFFFFFFF"))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4, 4, 4, 4)
            elevation = 4f
        }

        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }

        val btnBack = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_revert)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            contentDescription = "Back"
        }
        btnBack.setOnClickListener { if (webView.canGoBack()) webView.goBack() }

        val btnFwd = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_sort_by_size)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            contentDescription = "Forward"
        }
        btnFwd.setOnClickListener { if (webView.canGoForward()) webView.goForward() }

        urlBar = EditText(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(4); marginEnd = dp(4) }
            setSingleLine(true)
            setImeOptions(EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_FULLSCREEN)
            setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI)
            hint = "Tìm kiếm hoặc nhập URL"
            setHintTextColor(Color.parseColor("#FF9E9E9E"))
            setTextColor(Color.parseColor("#FF212121"))
            textSize = 14f
            setPadding(dp(14), 0, dp(8), 0)
            gravity = Gravity.CENTER_VERTICAL
            background = ColorDrawable(Color.parseColor("#FFE8EAED"))
        }
        urlBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_NEXT) {
                navigateFromBar(); true
            } else false
        }

        val btnMenu = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_more)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            contentDescription = "Menu"
        }
        btnMenu.setOnClickListener { showChromeMenu() }

        topBar.addView(btnBack)
        topBar.addView(btnFwd)
        topBar.addView(urlBar)
        topBar.addView(btnMenu)

        // === PROGRESS BAR ===
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3))
            max = 100; isIndeterminate = false
            visibility = View.GONE
        }

        // === WEBVIEW ===
        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        // === BOTTOM BAR ===
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#FFFFFFFF"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
            elevation = 4f
        }
        val btnHome = Button(this).apply {
            text = "🏠"
            textSize = 18f
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        btnHome.setOnClickListener { loadUrl("https://www.google.com") }

        val btnTabs = Button(this).apply {
            text = "📑"
            textSize = 18f
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        btnTabs.setOnClickListener { Toast.makeText(this@MainActivity, "Tabs: 1 tab open", Toast.LENGTH_SHORT).show() }

        val btnSearch = Button(this).apply {
            text = "🔍 FreeSearch"
            textSize = 13f
            setTextColor(Color.parseColor("#FF1A73E8"))
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        btnSearch.setOnClickListener { showFreeSearchPanel() }

        bottomBar.addView(btnHome)
        bottomBar.addView(btnTabs)
        bottomBar.addView(btnSearch)

        root.addView(topBar)
        root.addView(progressBar)
        root.addView(webView)
        root.addView(bottomBar)

        setContentView(root)

        // Setup WebView
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            userAgentString = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.230 Mobile Safari/537.36"
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowFileAccess = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            blockNetworkImage = false
            loadsImagesAutomatically = true
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url.toString()
                currentUrl = url
                urlBar.setText(url)
                searchEngine.addHistory(url, view?.title ?: "")
                view?.loadUrl(url)
                return true
            }
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progressBar.visibility = View.VISIBLE; urlBar.setText(url ?: "")
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.visibility = View.GONE
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, progress: Int) {
                progressBar.progress = progress
                if (progress == 100) progressBar.visibility = View.GONE
            }
            override fun onReceivedTitle(view: WebView?, title: String?) {
                title?.let { if (it.isNotBlank() && !it.startsWith("http")) searchEngine.addHistory(currentUrl, it) }
            }
        }

        loadUrl("https://www.google.com")
    }

    private fun navigateFromBar() {
        var url = urlBar.text.toString().trim()
        if (url.isBlank()) return
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            if (url.contains(".") && !url.contains(" ")) url = "https://$url"
            else url = "https://www.google.com/search?q=${java.net.URLEncoder.encode(url, "UTF-8")}"
        }
        loadUrl(url)
        hideKeyboard()
    }

    private fun loadUrl(url: String) {
        currentUrl = url; urlBar.setText(url); webView.loadUrl(url)
    }

    // ========== CHROME MENU ==========
    private fun showChromeMenu() {
        val items = arrayOf(
            "📄 Tab mới",
            "📥 Tệp đã tải xuống",
            "─────────────",
            "🔍 FreeSearch Extension",
            "─────────────",
            "📖 Lịch sử",
            "⭐ Bookmark",
            "⚙️ Cài đặt",
            "ℹ️ Về FreeSearch"
        )
        AlertDialog.Builder(this)
            .setTitle("Menu")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> { urlBar.setText(""); urlBar.requestFocus(); showKeyboard() }
                    1 -> showDownloadsPanel()
                    3 -> showFreeSearchPanel()
                    5 -> showHistoryPanel()
                    6 -> Toast.makeText(this, "Bookmarks coming soon", Toast.LENGTH_SHORT).show()
                    7 -> showSettingsPanel()
                    8 -> AlertDialog.Builder(this).setTitle("FreeSearch Browser v3.0")
                        .setMessage("Chromium-based browser\nSelf-hosted search engine\nNo content filtering\n\nPackage: com.vyvegroup.freesearch\nEngine: Chromium WebView")
                        .setPositiveButton("OK", null).show()
                }
            }
            .show()
    }

    // ========== FREESEARCH PANEL ==========
    private fun showFreeSearchPanel() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val dpVal = { v: Int -> (v * resources.displayMetrics.density).toInt() }

        val title = TextView(this).apply {
            text = "🔍 FreeSearch Extension"
            textSize = 20f; setTextColor(Color.parseColor("#FF1A73E8"))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        val tvStats = TextView(this).apply {
            textSize = 12f; setTextColor(Color.parseColor("#FF757575"))
            text = "Indexed: ${searchEngine.pageCount()} pages | Queue: ${searchEngine.queueSize()}"
        }
        val searchLabel = TextView(this).apply {
            text = "Tìm kiếm trong dữ liệu đã index:"
            textSize = 13f; setPadding(0, dpVal(12), 0, dpVal(4))
        }
        val searchBox = EditText(this).apply {
            hint = "Nhập từ khóa tìm kiếm..."; setSingleLine(true)
            textSize = 14f; setPadding(dpVal(12), dpVal(8), dpVal(12), dpVal(8))
            background = ColorDrawable(Color.parseColor("#FFE8EAED"))
        }
        val btnSearch = Button(this).apply {
            text = "Tìm kiếm"; textSize = 14f; setAllCaps(false)
        }

        val crawlLabel = TextView(this).apply {
            text = "Crawl website (nhập URL, mỗi dòng 1 URL):"
            textSize = 13f; setPadding(0, dpVal(16), 0, dpVal(4))
        }
        val seedBox = EditText(this).apply {
            hint = "https://example.com\nhttps://site.com"
            minLines = 3; textSize = 13f; setPadding(dpVal(12), dpVal(8), dpVal(12), dpVal(8))
            background = ColorDrawable(ColorDrawable(Color.parseColor("#FFE8EAED")).color)
            setTextColor(Color.parseColor("#FF212121"))
        }
        val btnCrawl = Button(this).apply {
            text = "▶ Bắt đầu Crawl"; textSize = 14f; setAllCaps(false)
        }
        val crawlLog = TextView(this).apply {
            textSize = 11f; setTextColor(Color.parseColor("#FF5F6368"))
            setPadding(dpVal(8), dpVal(8), dpVal(8), dpVal(8))
            background = ColorDrawable(Color.parseColor("#FFF1F3F4"))
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val btnClear = Button(this).apply {
            text = "Xóa toàn bộ dữ liệu"; textSize = 12f; setAllCaps(false)
            setTextColor(Color.parseColor("#FFD93025"))
            setPadding(0, dpVal(12), 0, 0)
        }

        panel.addView(title)
        panel.addView(tvStats)
        panel.addView(searchLabel)
        panel.addView(searchBox)
        panel.addView(btnSearch)
        panel.addView(crawlLabel)
        panel.addView(seedBox)
        panel.addView(btnCrawl)
        panel.addView(crawlLog)
        panel.addView(btnClear)

        val dialog = AlertDialog.Builder(this)
            .setTitle("FreeSearch Extension")
            .setView(panel)
            .setNegativeButton("Đóng", null)
            .show()

        // Search button
        btnSearch.setOnClickListener {
            val query = searchBox.text.toString().trim()
            if (query.isBlank()) return@setOnClickListener
            lifecycleScope.launch(Dispatchers.IO) {
                val results = searchEngine.search(query, 100)
                launch(Dispatchers.Main) {
                    if (results.isEmpty()) {
                        crawlLog.text = "Không tìm thấy kết quả cho \"$query\""
                    } else {
                        val sb = StringBuilder("Tìm thấy ${results.size} kết quả cho \"$query\":\n\n")
                        results.take(20).forEach { r ->
                            val url = r[1] as String
                            val t = (r[2] as String).ifBlank { url }
                            val d = r[4] as String
                            val s = (r[3] as String).replace(Regex("<[^>]+>"), "")
                            sb.appendLine("📄 $t")
                            sb.appendLine("   $d | ${url.take(60)}")
                            sb.appendLine("   ${s.take(100)}")
                            sb.appendLine()
                        }
                        if (results.size > 20) sb.appendLine("... và ${results.size - 20} kết quả khác")
                        crawlLog.text = sb.toString()
                    }
                    tvStats.text = "Indexed: ${searchEngine.pageCount()} pages | Queue: ${searchEngine.queueSize()}"
                }
            }
        }

        // Crawl button
        btnCrawl.setOnClickListener {
            if (isCrawling) return@setOnClickListener
            val urls = seedBox.text.toString().trim().split("\n").map { it.trim() }.filter { it.startsWith("http") }
            if (urls.isEmpty()) { Toast.makeText(this, "Nhập URL hợp lệ", Toast.LENGTH_SHORT).show(); return@setOnClickListener }

            isCrawling = true
            btnCrawl.text = "⏳ Đang crawl..."
            btnCrawl.isEnabled = false
            var crawled = 0; var indexed = 0
            val logLines = mutableListOf<String>()

            lifecycleScope.launch(Dispatchers.IO) {
                for (url in urls) searchEngine.enqueue(url, 0, 10 - crawled)

                while (isCrawling) {
                    val task = searchEngine.dequeue() ?: break
                    val (taskUrl, depth, _) = task
                    if (crawled >= 500) break

                    launch(Dispatchers.Main) {
                        crawlLog.text = "Crawling [$crawled] $taskUrl..."
                        tvStats.text = "Indexed: $indexed | Crawled: $crawled | Queue: ${searchEngine.queueSize()}"
                    }

                    try {
                        kotlinx.coroutines.delay(300)
                        val doc = Jsoup.connect(taskUrl).userAgent("FreeSearchBot/2.0").timeout(15000).followRedirects(true).maxBodySize(2 * 1024 * 1024).ignoreHttpErrors(true).get()
                        val title = doc.title()?.trim() ?: ""
                        val text = doc.body()?.text()?.trim() ?: ""
                        val domain = try { URI(taskUrl).host ?: "" } catch (_: Exception) { "" }

                        if (text.length > 50) {
                            val rowId = searchEngine.indexPage(taskUrl, title, text, domain)
                            if (rowId > 0) { indexed++; logLines.add("[+] $title") }

                            if (depth < 2) {
                                for (el in doc.select("a[href]")) {
                                    val href = el.attr("abs:href").trim()
                                    if (href.startsWith("http") && href.contains(domain) && href != taskUrl) {
                                        val clean = href.split("#").firstOrNull() ?: href
                                        if (clean.isNotBlank()) searchEngine.enqueue(clean, depth + 1, 0)
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {}
                    searchEngine.doneQueue(taskUrl)
                    crawled++
                }

                launch(Dispatchers.Main) {
                    crawlLog.text = "✅ Hoàn thành! Indexed: $indexed / Crawled: $crawled\n\n" + logLines.takeLast(15).joinToString("\n")
                    tvStats.text = "Indexed: ${searchEngine.pageCount()} pages | Queue: ${searchEngine.queueSize()}"
                    btnCrawl.text = "▶ Bắt đầu Crawl"
                    btnCrawl.isEnabled = true
                    isCrawling = false
                }
            }
        }

        // Clear button
        btnClear.setOnClickListener {
            AlertDialog.Builder(this).setTitle("Xóa toàn bộ?")
                .setMessage("Xóa tất cả dữ liệu đã index? Không thể hoàn tác.")
                .setPositiveButton("Xóa") { _, _ ->
                    searchEngine.clearAll()
                    crawlLog.text = "Đã xóa toàn bộ dữ liệu"
                    tvStats.text = "Indexed: 0 | Queue: 0"
                }
                .setNegativeButton("Hủy", null)
                .show()
        }
    }

    // ========== DOWNLOADS PANEL ==========
    private fun showDownloadsPanel() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(24), dp(24), dp(24))
            gravity = Gravity.CENTER
        }
        val icon = TextView(this).apply {
            text = "📥"; textSize = 48f; gravity = Gravity.CENTER
        }
        val msg = TextView(this).apply {
            text = "Chưa có tệp tải xuống nào.\nTệp tải xuống sẽ xuất hiện ở đây."
            textSize = 14f; setTextColor(Color.parseColor("#FF757575")); gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        }
        panel.addView(icon); panel.addView(msg)
        AlertDialog.Builder(this).setTitle("Tệp đã tải xuống").setView(panel)
            .setNegativeButton("Đóng", null).show()
    }

    // ========== HISTORY PANEL ==========
    private fun showHistoryPanel() {
        lifecycleScope.launch(Dispatchers.IO) {
            val history = searchEngine.getHistory(30)
            launch(Dispatchers.Main) {
                if (history.isEmpty()) {
                    AlertDialog.Builder(this@MainActivity).setTitle("📖 Lịch sử")
                        .setMessage("Chưa có lịch sử duyệt web.")
                        .setNegativeButton("Đóng", null).show()
                    return@launch
                }
                val items = history.map { h -> "${(h[2] as String).ifBlank { (h[1] as String).take(40) }}\n${h[1]}" }.toTypedArray()
                AlertDialog.Builder(this@MainActivity).setTitle("📖 Lịch sử")
                    .setItems(items) { _, which -> loadUrl(history[which][1] as String) }
                    .setNeutralButton("Xóa lịch sử") { _, _ -> searchEngine.clearHistory(); Toast.makeText(this@MainActivity, "Đã xóa", Toast.LENGTH_SHORT).show() }
                    .setNegativeButton("Đóng", null).show()
            }
        }
    }

    // ========== SETTINGS PANEL ==========
    private fun showSettingsPanel() {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(16), dp(24), dp(16)) }
        val info = arrayOf(
            "Package: com.vyvegroup.freesearch",
            "Version: 3.0.0 (Build 3)",
            "Engine: Chromium WebView",
            "Min Android: 7.0 (API 24)",
            "Signing: v2 + v3",
            "Search: SQLite FTS5 + BM25",
            "Filter: NONE (No filtering)"
        )
        val tv = TextView(this).apply {
            text = info.joinToString("\n"); textSize = 14f
            setTextColor(Color.parseColor("#FF212121"))
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        panel.addView(tv)
        AlertDialog.Builder(this).setTitle("⚙️ Cài đặt").setView(panel).setNegativeButton("Đóng", null).show()
    }

    // ========== UTILS ==========
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(urlBar.windowToken, 0)
    }

    private fun showKeyboard() {
        urlBar.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(urlBar, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() { webView.destroy(); super.onDestroy() }
}
