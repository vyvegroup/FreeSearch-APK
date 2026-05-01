package com.vyvegroup.searchengine.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vyvegroup.searchengine.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI

class BrowserActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var urlBar: EditText
    private lateinit var btnGo: ImageButton
    private lateinit var btnBack: ImageButton
    private lateinit var btnForward: ImageButton
    private lateinit var btnHome: ImageButton
    private lateinit var btnSearch: ImageButton
    private lateinit var btnMenu: ImageButton
    private lateinit var progressBar: ProgressBar
    private lateinit var bottomBar: LinearLayout
    private lateinit var searchPanel: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var searchResults: RecyclerView
    private lateinit var emptySearch: LinearLayout
    private lateinit var crawlStatus: TextView
    private lateinit var btnStartCrawl: Button
    private lateinit var etSeedUrls: EditText
    private lateinit var tvPageCount: TextView

    private lateinit var searchEngine: SearchEngine
    private var isCrawling = false
    private var currentUrl = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser)

        val db = SearchDatabase(this)
        searchEngine = SearchEngine(db.writableDatabase)

        initViews()
        setupWebView()
        setupSearchPanel()
        updatePageCount()
    }

    private fun initViews() {
        webView = findViewById(R.id.webView)
        urlBar = findViewById(R.id.urlBar)
        btnGo = findViewById(R.id.btnGo)
        btnBack = findViewById(R.id.btnBack)
        btnForward = findViewById(R.id.btnForward)
        btnHome = findViewById(R.id.btnHome)
        btnSearch = findViewById(R.id.btnSearch)
        btnMenu = findViewById(R.id.btnMenu)
        progressBar = findViewById(R.id.progressBar)
        bottomBar = findViewById(R.id.bottomBar)
        searchPanel = findViewById(R.id.searchPanel)
        searchInput = findViewById(R.id.searchInput)
        searchResults = findViewById(R.id.searchResults)
        emptySearch = findViewById(R.id.emptySearch)
        crawlStatus = findViewById(R.id.crawlStatus)
        btnStartCrawl = findViewById(R.id.btnStartCrawl)
        etSeedUrls = findViewById(R.id.etSeedUrls)
        tvPageCount = findViewById(R.id.tvPageCount)

        btnBack.setOnClickListener { if (webView.canGoBack()) webView.goBack() }
        btnForward.setOnClickListener { if (webView.canGoForward()) webView.goForward() }
        btnHome.setOnClickListener { loadUrl("https://www.google.com") }
        btnSearch.setOnClickListener { toggleSearchPanel() }

        btnGo.setOnClickListener { navigateFromUrlBar() }
        urlBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_NEXT) {
                navigateFromUrlBar(); true
            } else false
        }

        urlBar.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) { navigateFromUrlBar(); true } else false
        }

        btnMenu.setOnClickListener { showMenu() }

        btnStartCrawl.setOnClickListener { startCrawl() }
        searchResults.layoutManager = LinearLayoutManager(this)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            userAgentString = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowFileAccess = true
            mediaPlaybackRequiresUserGesture = false
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
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
                urlBar.setText(url ?: "")
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url, favicon)
                progressBar.visibility = View.GONE
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                if (newProgress == 100) progressBar.visibility = View.GONE
            }
            override fun onReceivedTitle(view: WebView?, title: String?) {
                title?.let {
                    if (it.isNotBlank() && !it.startsWith("http")) {
                        searchEngine.addHistory(currentUrl, it)
                    }
                }
            }
        }

        loadUrl("https://www.google.com")
    }

    private fun setupSearchPanel() {
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                performSearch(); true
            } else false
        }

        findViewById<ImageButton>(R.id.btnDoSearch).setOnClickListener { performSearch() }
    }

    private fun navigateFromUrlBar() {
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
        currentUrl = url
        urlBar.setText(url)
        webView.loadUrl(url)
    }

    private fun toggleSearchPanel() {
        if (searchPanel.visibility == View.VISIBLE) {
            searchPanel.visibility = View.GONE
        } else {
            searchPanel.visibility = View.VISIBLE
            updatePageCount()
            searchInput.requestFocus()
        }
    }

    private fun performSearch() {
        val query = searchInput.text.toString().trim()
        if (query.isBlank()) return

        emptySearch.visibility = View.GONE
        findViewById<TextView>(R.id.tvSearchTitle).text = "Results for: $query"

        lifecycleScope.launch(Dispatchers.IO) {
            val results = searchEngine.search(query, 100)
            launch(Dispatchers.Main) {
                if (results.isEmpty()) {
                    emptySearch.visibility = View.VISIBLE
                    searchResults.adapter = null
                } else {
                    emptySearch.visibility = View.GONE
                    searchResults.adapter = SearchAdapter(results) { result ->
                        loadUrl(result.url)
                        searchPanel.visibility = View.GONE
                    }
                }
            }
        }
        hideKeyboard()
    }

    private fun startCrawl() {
        if (isCrawling) return

        val urlsText = etSeedUrls.text.toString().trim()
        if (urlsText.isBlank()) {
            etSeedUrls.error = "Enter URLs"
            return
        }

        val urls = urlsText.split("\n").map { it.trim() }.filter { it.startsWith("http") }
        if (urls.isEmpty()) { Toast.makeText(this, "Enter valid URLs", Toast.LENGTH_SHORT).show(); return }

        isCrawling = true
        btnStartCrawl.text = "Crawling..."
        btnStartCrawl.isEnabled = false
        var crawled = 0
        var indexed = 0

        lifecycleScope.launch(Dispatchers.IO) {
            for (url in urls) {
                searchEngine.addToQueue(url, 0, 10 - crawled)
            }

            while (isCrawling) {
                val task = searchEngine.getNextFromQueue() ?: break
                val (taskUrl, depth, parentUrl) = task
                if (crawled >= 500) break

                launch(Dispatchers.Main) {
                    crawlStatus.text = "Crawling ($crawled/$indexed indexed): ${taskUrl.take(40)}..."
                }

                try {
                    kotlinx.coroutines.delay(300)
                    val doc = Jsoup.connect(taskUrl)
                        .userAgent("FreeSearchBot/2.0")
                        .timeout(15000)
                        .followRedirects(true)
                        .maxBodySize(2 * 1024 * 1024)
                        .ignoreHttpErrors(true)
                        .get()

                    val title = doc.title()?.trim() ?: ""
                    val text = doc.body()?.text()?.trim() ?: ""
                    val domain = try { URI(taskUrl).host ?: "" } catch (_: Exception) { "" }

                    if (text.length > 50) {
                        val rowId = searchEngine.indexPage(taskUrl, title, text, domain)
                        if (rowId > 0) indexed++

                        // Extract links
                        if (depth < 2) {
                            val links = doc.select("a[href]")
                            for (el in links) {
                                val href = el.attr("abs:href").trim()
                                if (href.startsWith("http") && href.contains(domain) && href != taskUrl) {
                                    val clean = href.split("#").firstOrNull() ?: href
                                    if (clean.isNotBlank()) {
                                        searchEngine.addToQueue(clean, depth + 1, 0, taskUrl)
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}

                searchEngine.markQueueDone(taskUrl)
                crawled++
            }

            launch(Dispatchers.Main) {
                crawlStatus.text = "Done! Indexed $indexed pages from $crawled crawled"
                btnStartCrawl.text = "Start Crawl"
                btnStartCrawl.isEnabled = true
                isCrawling = false
                updatePageCount()
            }
        }
    }

    private fun updatePageCount() {
        lifecycleScope.launch(Dispatchers.IO) {
            val count = searchEngine.pageCount()
            launch(Dispatchers.Main) {
                tvPageCount.text = "Indexed: $count pages | Queue: ${searchEngine.queueSize()}"
            }
        }
    }

    private fun showMenu() {
        val options = arrayOf(
            "FreeSearch (local search)",
            "History",
            "Clear Indexed Data",
            "Clear History",
            "About"
        )
        AlertDialog.Builder(this)
            .setTitle("Menu")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> toggleSearchPanel()
                    1 -> showHistory()
                    2 -> { searchEngine.clearAll(); updatePageCount(); Toast.makeText(this, "Data cleared", Toast.LENGTH_SHORT).show() }
                    3 -> { searchEngine.clearHistory(); Toast.makeText(this, "History cleared", Toast.LENGTH_SHORT).show() }
                    4 -> AlertDialog.Builder(this).setTitle("FreeSearch Browser v2.0")
                        .setMessage("Self-hosted search engine browser.\nNo content filtering.\n\nCrawl websites → Search offline.")
                        .setPositiveButton("OK", null).show()
                }
            }
            .show()
    }

    private fun showHistory() {
        lifecycleScope.launch(Dispatchers.IO) {
            val history = searchEngine.getHistory(30)
            launch(Dispatchers.Main) {
                if (history.isEmpty()) {
                    Toast.makeText(this@BrowserActivity, "No history", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val items = history.map { "${it.title.ifBlank { it.url.take(40) }}\n${it.url}" }.toTypedArray()
                AlertDialog.Builder(this@BrowserActivity)
                    .setTitle("History")
                    .setItems(items) { _, which -> loadUrl(history[which].url) }
                    .setNegativeButton("Clear") { _, _ -> searchEngine.clearHistory() }
                    .show()
            }
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(urlBar.windowToken, 0)
    }

    override fun onBackPressed() {
        if (searchPanel.visibility == View.VISIBLE) {
            searchPanel.visibility = View.GONE
        } else if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}

class SearchAdapter(
    private val items: List<SearchResult>,
    private val onClick: (SearchResult) -> Unit
) : RecyclerView.Adapter<SearchAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView = view.findViewById(android.R.id.text1)
        val tvUrl: TextView = view.findViewById(android.R.id.text2)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_2, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.tvTitle.text = item.title.ifBlank { item.url }
        holder.tvUrl.text = "${item.domain} - ${item.snippet.take(80)}"
        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount() = items.size
}
