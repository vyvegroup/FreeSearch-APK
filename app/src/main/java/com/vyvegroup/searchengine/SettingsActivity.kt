package com.vyvegroup.searchengine

import android.app.Activity
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: PreferencesManager
    private lateinit var toolbar: MaterialToolbar
    private lateinit var engineSpinner: android.widget.Spinner
    private lateinit var customUrlLayout: TextInputLayout
    private lateinit var customUrlInput: TextInputEditText
    private lateinit var switchDarkMode: MaterialSwitch
    private lateinit var switchJs: MaterialSwitch
    private lateinit var btnClearHistory: com.google.android.material.button.MaterialButton
    private lateinit var tvVersion: android.widget.TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = PreferencesManager(this)

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        engineSpinner = findViewById(R.id.engineSpinner)
        customUrlLayout = findViewById(R.id.customUrlLayout)
        customUrlInput = findViewById(R.id.customUrlInput)
        switchDarkMode = findViewById(R.id.switchDarkMode)
        switchJs = findViewById(R.id.switchJs)
        btnClearHistory = findViewById(R.id.btnClearHistory)
        tvVersion = findViewById(R.id.tvVersion)

        setupEngineSpinner()
        loadPreferences()

        engineSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val engine = SearchEngineConfig.Engine.values()[position]
                prefs.currentEngine = engine.name
                customUrlLayout.visibility =
                    if (engine == SearchEngineConfig.Engine.CUSTOM) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        customUrlInput.setOnEditorActionListener { _, _, _ ->
            prefs.customUrl = customUrlInput.text.toString()
            true
        }

        switchDarkMode.setOnCheckedChangeListener { _, isChecked ->
            prefs.darkMode = isChecked
        }

        switchJs.setOnCheckedChangeListener { _, isChecked ->
            prefs.javaScriptEnabled = isChecked
        }

        btnClearHistory.setOnClickListener {
            SearchHistoryManager(this).clearHistory()
            Toast.makeText(this, R.string.clear_history, Toast.LENGTH_SHORT).show()
        }

        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            tvVersion.text = getString(R.string.version, pInfo.versionName)
        } catch (_: Exception) {}
    }

    private fun setupEngineSpinner() {
        val engines = SearchEngineConfig.Engine.values().map { it.displayName }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, engines)
        engineSpinner.adapter = adapter
    }

    private fun loadPreferences() {
        val config = prefs.getSearchConfig()
        val index = SearchEngineConfig.Engine.values().indexOf(config.engine)
        if (index >= 0) engineSpinner.setSelection(index)
        customUrlInput.setText(config.customUrl)
        switchDarkMode.isChecked = config.darkMode
        switchJs.isChecked = config.javaScriptEnabled
        customUrlLayout.visibility =
            if (config.engine == SearchEngineConfig.Engine.CUSTOM) View.VISIBLE else View.GONE
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
