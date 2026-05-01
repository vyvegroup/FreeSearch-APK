package com.vyvegroup.searchengine.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vyvegroup.searchengine.R
import com.vyvegroup.searchengine.crawler.WebCrawler
import com.vyvegroup.searchengine.data.CrawledPage
import com.vyvegroup.searchengine.data.CrawlConfig
import com.vyvegroup.searchengine.data.SearchDatabase
import com.vyvegroup.searchengine.data.SearchResult
import com.vyvegroup.searchengine.indexer.SearchEngine
import com.vyvegroup.searchengine.utils.TextUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var searchInput: EditText
    private lateinit var btnSearch: ImageButton
    private lateinit var btnCrawl: ImageButton
    private lateinit var btnStats: ImageButton
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var btnStartCrawl: View

    private lateinit var database: SearchDatabase
    private lateinit var searchEngine: SearchEngine
    private lateinit var adapter: ResultsAdapter
    private var currentQuery = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        database = SearchDatabase(this)
        searchEngine = SearchEngine(database.readableDatabase)

        initViews()
        setupRecyclerView()
        setupSearch()
        checkEmptyState()
    }

    private fun initViews() {
        searchInput = findViewById(R.id.searchInput)
        btnSearch = findViewById(R.id.btnSearch)
        btnCrawl = findViewById(R.id.btnCrawl)
        btnStats = findViewById(R.id.btnStats)
        recyclerView = findViewById(R.id.recyclerView)
        emptyState = findViewById(R.id.emptyState)
        progressBar = findViewById(R.id.progressBar)
        btnStartCrawl = findViewById(R.id.btnStartCrawl)

        btnCrawl.setOnClickListener {
            startActivity(Intent(this, CrawlActivity::class.java))
        }

        btnStats.setOnClickListener {
            startActivity(Intent(this, StatsActivity::class.java))
        }

        btnStartCrawl.setOnClickListener {
            startActivity(Intent(this, CrawlActivity::class.java))
        }
    }

    private fun setupRecyclerView() {
        adapter = ResultsAdapter { result ->
            openUrl(result.url)
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    private fun setupSearch() {
        btnSearch.setOnClickListener {
            performSearch()
        }

        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_GO) {
                performSearch()
                true
            } else false
        }
    }

    private fun performSearch() {
        val query = searchInput.text.toString().trim()
        if (query.isBlank()) {
            searchInput.error = "Enter search query"
            return
        }

        currentQuery = query
        progressBar.visibility = View.VISIBLE
        emptyState.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.IO) {
            val results = searchEngine.search(query, SearchEngine.SearchOptions(maxResults = 50))
            launch(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                adapter.submitList(results)
                if (results.isEmpty()) {
                    emptyState.visibility = View.VISIBLE
                    findViewById<TextView>(R.id.emptyState).let { v ->
                        // Keep existing empty state text
                    }
                } else {
                    emptyState.visibility = View.GONE
                }
            }
        }

        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    private fun checkEmptyState() {
        lifecycleScope.launch(Dispatchers.IO) {
            val count = getItemCount()
            launch(Dispatchers.Main) {
                if (count == 0) {
                    emptyState.visibility = View.VISIBLE
                    recyclerView.visibility = View.GONE
                } else {
                    emptyState.visibility = View.GONE
                    recyclerView.visibility = View.VISIBLE
                    // Show some random results
                    val results = searchEngine.getRandomPages(20)
                    adapter.submitList(results)
                }
            }
        }
    }

    private fun getItemCount(): Int {
        var count = 0
        val cursor = database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM ${SearchDatabase.TABLE_PAGES}", null
        )
        cursor?.use { if (it.moveToFirst()) count = it.getInt(0) }
        return count
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, "Cannot open URL", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (currentQuery.isNotBlank()) {
            performSearch()
        } else {
            checkEmptyState()
        }
    }

    class ResultsAdapter(private val onClick: (SearchResult) -> Unit) :
        RecyclerView.Adapter<ResultsAdapter.ViewHolder>() {

        private val items = mutableListOf<SearchResult>()

        fun submitList(results: List<SearchResult>) {
            items.clear()
            items.addAll(results)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_result, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount() = items.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
            private val tvUrl: TextView = itemView.findViewById(R.id.tvUrl)
            private val tvSnippet: TextView = itemView.findViewById(R.id.tvSnippet)
            private val tvMeta: TextView = itemView.findViewById(R.id.tvMeta)
            private val btnOpen: View = itemView.findViewById(R.id.btnOpen)

            fun bind(result: SearchResult) {
                tvTitle.text = result.title.ifBlank { result.url }
                tvUrl.text = result.url
                tvSnippet.text = result.snippet
                tvMeta.text = "${result.domain} - ${TextUtils.formatFileSize(result.contentLength)}"
                if (result.crawledAt > 0) {
                    tvMeta.text = "${tvMeta.text} - ${TextUtils.formatDate(result.crawledAt)}"
                }
                btnOpen.setOnClickListener { onClick(result) }
            }
        }
    }
}
