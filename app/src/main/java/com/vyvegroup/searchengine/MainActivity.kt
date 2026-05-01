package com.vyvegroup.searchengine

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.FloatingActionButton

class MainActivity : AppCompatActivity() {

    private lateinit var searchInput: EditText
    private lateinit var btnSearch: ImageButton
    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var emptyState: LinearLayout
    private lateinit var fabSettings: FloatingActionButton
    private lateinit var searchCard: MaterialCardView
    private lateinit var engineChips: ChipGroup
    private lateinit var chipDuckDuckGo: Chip
    private lateinit var chipBrave: Chip
    private lateinit var chipStartpage: Chip
    private lateinit var chipQwant: Chip
    private lateinit var chipCustom: Chip

    private lateinit var prefs: PreferencesManager
    private lateinit var historyManager: SearchHistoryManager
    private var isSearching = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = PreferencesManager(this)
        historyManager = SearchHistoryManager(this)

        initViews()
        setupWebView()
        setupChips()
        setupSearch()
        setupSwipeRefresh()
        loadSavedEngine()
    }

    private fun initViews() {
        searchInput = findViewById(R.id.searchInput)
        btnSearch = findViewById(R.id.btnSearch)
        webView = findViewById(R.id.webView)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        progressBar = findViewById(R.id.progressBar)
        emptyState = findViewById(R.id.emptyState)
        fabSettings = findViewById(R.id.fabSettings)
        searchCard = findViewById(R.id.searchCard)
        engineChips = findViewById(R.id.engineChips)
        chipDuckDuckGo = findViewById(R.id.chipDuckDuckGo)
        chipBrave = findViewById(R.id.chipBrave)
        chipStartpage = findViewById(R.id.chipStartpage)
        chipQwant = findViewById(R.id.chipQwant)
        chipCustom = findViewById(R.id.chipCustom)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = prefs.javaScriptEnabled
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            userAgentString = SearchEngineConfig.getMobileUserAgent()
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url.toString()
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    view?.loadUrl(url)
                    return true
                }
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                } catch (_: Exception) {}
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
                isSearching = true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                isSearching = false
                swipeRefresh.isRefreshing = false
                if (url != null && url.contains("duckduckgo") || url?.contains("brave") == true ||
                    url?.contains("startpage") == true || url?.contains("qwant") == true) {
                    emptyState.visibility = View.GONE
                    webView.visibility = View.VISIBLE
                }
            }
        }

        webView.webChromeClient = WebChromeClient()
        webView.visibility = View.GONE
        emptyState.visibility = View.VISIBLE
    }

    private fun setupChips() {
        val chipMap = mapOf(
            SearchEngineConfig.Engine.DUCKDUCKGO to chipDuckDuckGo,
            SearchEngineConfig.Engine.BRAVE to chipBrave,
            SearchEngineConfig.Engine.STARTPAGE to chipStartpage,
            SearchEngineConfig.Engine.QWANT to chipQwant,
            SearchEngineConfig.Engine.CUSTOM to chipCustom
        )

        chipMap.forEach { (engine, chip) ->
            chip.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    prefs.currentEngine = engine.name
                }
            }
        }
    }

    private fun loadSavedEngine() {
        val savedEngine = try {
            SearchEngineConfig.Engine.valueOf(prefs.currentEngine)
        } catch (_: Exception) {
            SearchEngineConfig.Engine.DUCKDUCKGO
        }

        val chipId = when (savedEngine) {
            SearchEngineConfig.Engine.DUCKDUCKGO -> R.id.chipDuckDuckGo
            SearchEngineConfig.Engine.BRAVE -> R.id.chipBrave
            SearchEngineConfig.Engine.STARTPAGE -> R.id.chipStartpage
            SearchEngineConfig.Engine.QWANT -> R.id.chipQwant
            SearchEngineConfig.Engine.CUSTOM -> R.id.chipCustom
        }
        engineChips.check(chipId)
    }

    private fun setupSearch() {
        btnSearch.setOnClickListener {
            performSearch()
        }

        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.KEYCODE_ENTER) {
                performSearch()
                true
            } else false
        }

        searchInput.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
                performSearch()
                true
            } else false
        }

        fabSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun performSearch() {
        val query = searchInput.text.toString().trim()
        if (query.isBlank()) {
            searchInput.error = "Enter a search query"
            return
        }

        val config = prefs.getSearchConfig()
        val url = SearchEngineConfig.buildSearchUrl(query, config)

        historyManager.addQuery(query)

        webView.settings.javaScriptEnabled = config.javaScriptEnabled
        emptyState.visibility = View.GONE
        webView.visibility = View.VISIBLE
        webView.loadUrl(url)

        android.view.inputmethod.InputMethodManager
            .hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    private fun setupSwipeRefresh() {
        swipeRefresh.setColorSchemeResources(R.color.primary, R.color.secondary)
        swipeRefresh.setOnRefreshListener {
            if (isSearching) {
                swipeRefresh.isRefreshing = false
            } else {
                webView.reload()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        val config = prefs.getSearchConfig()
        webView.settings.javaScriptEnabled = config.javaScriptEnabled
    }
}
