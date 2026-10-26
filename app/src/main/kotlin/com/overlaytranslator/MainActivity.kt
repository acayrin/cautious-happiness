package com.overlaytranslator

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.overlaytranslator.cache.TranslationCache
import com.overlaytranslator.overlay.OverlayConfig
import com.overlaytranslator.service.CaptureService
import com.overlaytranslator.translate.OpusMtTranslator
import com.overlaytranslator.util.Permissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings + permission gate. Also hosts the untrusted-touch setup card that
 * tells the user how to make touches pass through the overlay on Android 12+.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var config: OverlayConfig

    private lateinit var statusText: TextView
    private lateinit var startStopButton: MaterialButton
    private lateinit var hideOverlayButton: MaterialButton

    private lateinit var overlayPermStatus: TextView
    private lateinit var overlayPermButton: MaterialButton
    private lateinit var notifPermStatus: TextView
    private lateinit var notifPermButton: MaterialButton

    private lateinit var touchSetupCard: MaterialCardView
    private lateinit var touchSetupStatus: TextView
    private lateinit var touchSetupActions: android.widget.LinearLayout

    private lateinit var fpsSeekBar: SeekBar
    private lateinit var fpsLabel: TextView
    private lateinit var opacitySeekBar: SeekBar
    private lateinit var opacityLabel: TextView
    private lateinit var forceSeekBar: SeekBar
    private lateinit var forceLabel: TextView
    private lateinit var changeSeekBar: SeekBar
    private lateinit var changeLabel: TextView
    private lateinit var ocrConfSeekBar: SeekBar
    private lateinit var ocrConfLabel: TextView
    private lateinit var lineGapSeekBar: SeekBar
    private lateinit var lineGapLabel: TextView
    private lateinit var pacingSeekBar: SeekBar
    private lateinit var pacingLabel: TextView
    private lateinit var twDeferSeekBar: SeekBar
    private lateinit var twDeferLabel: TextView
    private lateinit var syncSwitch: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var clearAfterSeekBar: SeekBar
    private lateinit var clearAfterLabel: TextView
    private lateinit var overflowSwitch: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var normalizeSwitch: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var cacheSizeText: TextView
    private lateinit var modelUrlInput: android.widget.EditText
    private lateinit var languageSpinner: android.widget.Spinner
    private lateinit var backendSpinner: android.widget.Spinner

    private var running = false

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshPermissionUi() }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshPermissionUi() }

    private val exportSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { exportSettings(it) } }

    private val importSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { importSettings(it) } }

    private val exportCacheLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { exportCache(it) } }

    private val importCacheLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { importCache(it) } }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            CaptureService.start(this, result.resultCode, data)
            running = true
            refreshRunningUi()
        } else {
            Toast.makeText(this, R.string.err_projection, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        config = loadConfig()
        bindViews()
        wireListeners()
        refreshPermissionUi()
        refreshTouchSetupUi()
        refreshRunningUi()
        applyConfigToSliders()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionUi()
        refreshTouchSetupUi()
        refreshRunningUi()
        refreshCacheSize()
    }

    private fun refreshCacheSize() {
        val db = getDatabasePath(com.overlaytranslator.cache.TranslationCache.DB_NAME)
        val size = listOf(db, java.io.File(db.path + "-wal"), java.io.File(db.path + "-journal"), java.io.File(db.path + "-shm"))
            .sumOf { if (it.exists()) it.length() else 0L }
        cacheSizeText.text = getString(R.string.cache_size_value, formatSize(size))
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> "%.1f KB".format(bytes / (1L shl 10).toDouble())
        else -> "$bytes B"
    }

    // ---------------------------------------------------------------- setup

    private fun bindViews() {
        statusText = findViewById(R.id.statusText)
        startStopButton = findViewById(R.id.startStopButton)
        hideOverlayButton = findViewById(R.id.hideOverlayButton)

        overlayPermStatus = findViewById(R.id.overlayPermStatus)
        overlayPermButton = findViewById(R.id.overlayPermButton)
        notifPermStatus = findViewById(R.id.notifPermStatus)
        notifPermButton = findViewById(R.id.notifPermButton)

        touchSetupCard = findViewById(R.id.touchSetupCard)
        touchSetupStatus = findViewById(R.id.touchSetupStatus)
        touchSetupActions = findViewById(R.id.touchSetupActions)

        fpsSeekBar = findViewById(R.id.fpsSeekBar)
        fpsLabel = findViewById(R.id.fpsLabel)
        opacitySeekBar = findViewById(R.id.opacitySeekBar)
        opacityLabel = findViewById(R.id.opacityLabel)
        forceSeekBar = findViewById(R.id.forceSeekBar)
        forceLabel = findViewById(R.id.forceLabel)
        changeSeekBar = findViewById(R.id.changeSeekBar)
        changeLabel = findViewById(R.id.changeLabel)
        ocrConfSeekBar = findViewById(R.id.ocrConfSeekBar)
        ocrConfLabel = findViewById(R.id.ocrConfLabel)
        lineGapSeekBar = findViewById(R.id.lineGapSeekBar)
        lineGapLabel = findViewById(R.id.lineGapLabel)
        pacingSeekBar = findViewById(R.id.pacingSeekBar)
        pacingLabel = findViewById(R.id.pacingLabel)
        twDeferSeekBar = findViewById(R.id.twDeferSeekBar)
        twDeferLabel = findViewById(R.id.twDeferLabel)
        syncSwitch = findViewById(R.id.syncSwitch)
        clearAfterSeekBar = findViewById(R.id.clearAfterSeekBar)
        clearAfterLabel = findViewById(R.id.clearAfterLabel)
        overflowSwitch = findViewById(R.id.overflowSwitch)
        normalizeSwitch = findViewById(R.id.normalizeSwitch)
        cacheSizeText = findViewById(R.id.cacheSizeText)
        modelUrlInput = findViewById(R.id.modelUrlInput)
        modelUrlInput.setText(config.modelBaseUrl)
        languageSpinner = findViewById(R.id.languageSpinner)
        backendSpinner = findViewById(R.id.backendSpinner)
    }

    private fun wireListeners() {
        startStopButton.setOnClickListener { toggleService() }
        hideOverlayButton.setOnClickListener {
            val intent = Intent(this, CaptureService::class.java).apply {
                action = CaptureService.ACTION_HIDE_OVERLAY
            }
            startService(intent)
            Toast.makeText(this, R.string.btn_hide_overlay, Toast.LENGTH_SHORT).show()
        }

        overlayPermButton.setOnClickListener {
            overlayPermissionLauncher.launch(Permissions.overlaySettingsIntent(this))
        }
        notifPermButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        findViewById<MaterialButton>(R.id.openDevOptionsButton).setOnClickListener {
            runCatching {
                startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            }.onFailure {
                Toast.makeText(this, "Developer options unavailable", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<MaterialButton>(R.id.copyAdbButton).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("adb", Permissions.UNTRUSTED_TOUCH_ADB_COMMAND))
            Toast.makeText(this, R.string.touch_setup_adb_copied, Toast.LENGTH_SHORT).show()
        }

        fpsSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val ms = 100 + progress * 100
                fpsLabel.text = getString(R.string.settings_fps_value, ms)
                if (fromUser) {
                    config = config.copy(captureIntervalMs = ms)
                    saveConfig()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        opacitySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                opacityLabel.text = getString(R.string.settings_opacity_value, progress)
                if (fromUser) {
                    config = config.copy(backgroundAlpha = (progress * 255 / 100).coerceIn(0, 255))
                    saveConfig()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        findViewById<MaterialButton>(R.id.downloadModelButton).setOnClickListener {
            downloadModel()
        }
        forceSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress + 1
                forceLabel.text = getString(R.string.settings_force_every_value, value)
                if (fromUser) {
                    config = config.copy(forceEvery = value)
                    saveConfig()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        changeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress * 0.1f
                changeLabel.text = if (value <= 0f) {
                    getString(R.string.settings_change_pct_off)
                } else {
                    getString(R.string.settings_change_pct_value, value)
                }
                if (fromUser) {
                    config = config.copy(changePct = value)
                    saveConfig()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        ocrConfSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress / 100f
                ocrConfLabel.text = getString(R.string.settings_ocr_conf_value, value)
                if (fromUser) {
                    config = config.copy(ocrConfidence = value)
                    saveConfig()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        lineGapSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = 0.2f + progress * 0.1f
                lineGapLabel.text = getString(R.string.settings_line_gap_value, value)
                if (fromUser) {
                    config = config.copy(multilineGap = value)
                    saveConfig()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        pacingSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = 100 + progress * 100
                pacingLabel.text = getString(R.string.settings_pacing_value, value)
                if (fromUser) {
                    config = config.copy(requestPacingMs = value)
                    saveConfig()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        twDeferSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                twDeferLabel.text = getString(R.string.settings_tw_defer_value, progress)
                if (fromUser) {
                    config = config.copy(typewriterDefer = progress)
                    saveConfig()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        syncSwitch.setOnCheckedChangeListener { _, checked ->
            config = config.copy(synchronousDisplay = checked)
            saveConfig()
        }
        clearAfterSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress + 1
                clearAfterLabel.text = getString(R.string.settings_clear_after_value, value)
                if (fromUser) {
                    config = config.copy(clearAfterSeconds = value)
                    saveConfig()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        overflowSwitch.setOnCheckedChangeListener { _, checked ->
            config = config.copy(allowTextOverflow = checked)
            saveConfig()
        }
        normalizeSwitch.setOnCheckedChangeListener { _, checked ->
            config = config.copy(normalizeCacheKeys = checked)
            saveConfig()
        }
        modelUrlInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(editable: android.text.Editable) {
                val url = editable.toString().trim()
                    .ifBlank { OverlayConfig.DEFAULT_MODEL_URL }
                if (url != config.modelBaseUrl) {
                    config = config.copy(modelBaseUrl = url)
                    saveConfig()
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        languageSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?, view: android.view.View?,
                position: Int, id: Long,
            ) {
                val code = resources.getStringArray(R.array.source_language_codes)[position]
                if (code != currentLang()) {
                    saveLang(code)
                    Toast.makeText(this@MainActivity, R.string.settings_language_applies, Toast.LENGTH_SHORT).show()
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        backendSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?, view: android.view.View?,
                position: Int, id: Long,
            ) {
                val code = resources.getStringArray(R.array.backend_codes)[position]
                if (code != currentBackend()) {
                    saveBackend(code)
                    Toast.makeText(this@MainActivity, R.string.settings_backend_applies, Toast.LENGTH_SHORT).show()
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        findViewById<MaterialButton>(R.id.glossaryButton).setOnClickListener {
            startActivity(Intent(this, GlossaryActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.exportSettingsButton).setOnClickListener {
            exportSettingsLauncher.launch("overlay-translator-settings.json")
        }
        findViewById<MaterialButton>(R.id.importSettingsButton).setOnClickListener {
            importSettingsLauncher.launch(arrayOf("application/json", "text/plain"))
        }
        findViewById<MaterialButton>(R.id.exportCacheButton).setOnClickListener {
            exportCacheLauncher.launch("overlay-translator-cache.json")
        }
        findViewById<MaterialButton>(R.id.importCacheButton).setOnClickListener {
            importCacheLauncher.launch(arrayOf("application/json", "text/plain"))
        }
        findViewById<MaterialButton>(R.id.clearCacheButton).setOnClickListener {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    TranslationCache.get(this@MainActivity).clear()
                }
                refreshCacheSize()
                Toast.makeText(this@MainActivity, R.string.cache_cleared, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------------------------------------------------------------- state

    private fun toggleService() {
        if (running) {
            CaptureService.stop(this)
            running = false
            refreshRunningUi()
            return
        }

        if (!Permissions.hasOverlayPermission(this)) {
            Toast.makeText(this, R.string.perm_overlay_missing, Toast.LENGTH_SHORT).show()
            refreshPermissionUi()
            return
        }
        if (!Permissions.hasNotificationPermission(this)) {
            Toast.makeText(this, R.string.perm_notifications_missing, Toast.LENGTH_SHORT).show()
            refreshPermissionUi()
            return
        }

        // MediaProjection consent — user must tap "Start now" in the system dialog.
        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as
                android.media.projection.MediaProjectionManager
        projectionLauncher.launch(pm.createScreenCaptureIntent())
    }

    private fun refreshPermissionUi() {
        val overlayOk = Permissions.hasOverlayPermission(this)
        overlayPermStatus.text = getString(
            if (overlayOk) R.string.perm_overlay_granted else R.string.perm_overlay_missing
        )
        overlayPermButton.isEnabled = !overlayOk

        val notifOk = Permissions.hasNotificationPermission(this)
        notifPermStatus.text = getString(
            if (notifOk) R.string.perm_notifications_granted else R.string.perm_notifications_missing
        )
        notifPermButton.isEnabled = !notifOk
    }

    private fun refreshTouchSetupUi() {
        val blocked = Permissions.isUntrustedTouchBlocked(this)
        if (!blocked) {
            touchSetupCard.setCardBackgroundColor(0xFFE8F5E9.toInt())
            touchSetupStatus.text = getString(R.string.touch_setup_ok)
            touchSetupActions.visibility = android.view.View.GONE
        } else {
            touchSetupCard.setCardBackgroundColor(0xFFFFF3E0.toInt())
            touchSetupStatus.text = getString(R.string.touch_setup_blocked)
            touchSetupActions.visibility = android.view.View.VISIBLE
        }
    }

    private fun refreshRunningUi() {
        statusText.text = getString(if (running) R.string.status_running else R.string.status_stopped)
        startStopButton.text = getString(if (running) R.string.btn_stop else R.string.btn_start)
        hideOverlayButton.isEnabled = running
    }

    private fun applyConfigToSliders() {
        fpsLabel.text = getString(R.string.settings_fps_value, config.captureIntervalMs)
        fpsSeekBar.progress = ((config.captureIntervalMs - 100) / 100).coerceIn(0, 19)

        val pct = config.backgroundAlpha * 100 / 255
        opacityLabel.text = getString(R.string.settings_opacity_value, pct)
        opacitySeekBar.progress = pct

        val codes = resources.getStringArray(R.array.source_language_codes)
        languageSpinner.setSelection(codes.indexOf(currentLang()).coerceAtLeast(0))
        val backends = resources.getStringArray(R.array.backend_codes)
        backendSpinner.setSelection(backends.indexOf(currentBackend()).coerceAtLeast(0))

        forceLabel.text = getString(R.string.settings_force_every_value, config.forceEvery)
        forceSeekBar.progress = config.forceEvery - 1
        changeLabel.text = if (config.changePct <= 0f) {
            getString(R.string.settings_change_pct_off)
        } else {
            getString(R.string.settings_change_pct_value, config.changePct)
        }
        changeSeekBar.progress = (config.changePct * 10).toInt().coerceIn(0, 50)

        ocrConfLabel.text = getString(R.string.settings_ocr_conf_value, config.ocrConfidence)
        ocrConfSeekBar.progress = (config.ocrConfidence * 100).toInt().coerceIn(0, 95)

        lineGapLabel.text = getString(R.string.settings_line_gap_value, config.multilineGap)
        lineGapSeekBar.progress = ((config.multilineGap - 0.2f) * 10).toInt().coerceIn(0, 13)

        pacingLabel.text = getString(R.string.settings_pacing_value, config.requestPacingMs)
        pacingSeekBar.progress = ((config.requestPacingMs - 100) / 100).coerceIn(0, 19)
        twDeferLabel.text = getString(R.string.settings_tw_defer_value, config.typewriterDefer)
        twDeferSeekBar.progress = config.typewriterDefer.coerceIn(0, 3)

        syncSwitch.isChecked = config.synchronousDisplay

        clearAfterLabel.text = getString(R.string.settings_clear_after_value, config.clearAfterSeconds)
        clearAfterSeekBar.progress = (config.clearAfterSeconds - 1).coerceIn(0, 4)

        overflowSwitch.isChecked = config.allowTextOverflow
        normalizeSwitch.isChecked = config.normalizeCacheKeys
    }

    private fun currentLang(): String {
        return getSharedPreferences(OverlayConfig.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(OverlayConfig.KEY_LANG, OverlayConfig.LANG_ZH) ?: OverlayConfig.LANG_ZH
    }

    private fun saveLang(code: String) {
        getSharedPreferences(OverlayConfig.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(OverlayConfig.KEY_LANG, code)
            .apply()
    }

    private fun currentBackend(): String {
        return getSharedPreferences(OverlayConfig.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(OverlayConfig.KEY_BACKEND, OverlayConfig.BACKEND_OPUSMT) ?: OverlayConfig.BACKEND_OPUSMT
    }

    private fun saveBackend(code: String) {
        getSharedPreferences(OverlayConfig.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(OverlayConfig.KEY_BACKEND, code)
            .apply()
    }

    // ---------------------------------------------------------------- config

    private fun loadConfig(): OverlayConfig {
        val p = getSharedPreferences(OverlayConfig.PREFS_NAME, Context.MODE_PRIVATE)
        return OverlayConfig(
            captureIntervalMs = p.getInt(
                OverlayConfig.KEY_INTERVAL_MS,
                -1,
            ).takeIf { it > 0 }
                ?: ((1000f / p.getFloat(OverlayConfig.KEY_FPS, 1.0f)).toInt())
                    .coerceIn(OverlayConfig.MIN_INTERVAL_MS.toInt(), OverlayConfig.MAX_INTERVAL_MS.toInt()),
            backgroundAlpha = p.getInt(OverlayConfig.KEY_ALPHA, 230),
            forceEvery = p.getInt(OverlayConfig.KEY_FORCE_EVERY, 5),
            changePct = p.getFloat(OverlayConfig.KEY_CHANGE_PCT, 0.6f),
            ocrConfidence = p.getFloat(OverlayConfig.KEY_OCR_CONF, 0.5f),
            multilineGap = p.getFloat(OverlayConfig.KEY_LINE_GAP, 0.7f),
            requestPacingMs = p.getInt(OverlayConfig.KEY_PACING_MS, 250),
            typewriterDefer = p.getInt(OverlayConfig.KEY_TW_DEFER, 0),
            synchronousDisplay = p.getBoolean(OverlayConfig.KEY_SYNC_DISPLAY, false),
            clearAfterSeconds = p.getInt(OverlayConfig.KEY_CLEAR_AFTER, 2),
            allowTextOverflow = p.getBoolean(OverlayConfig.KEY_TEXT_OVERFLOW, false),
            normalizeCacheKeys = p.getBoolean(OverlayConfig.KEY_NORMALIZE_CACHE, false),
        )
    }

    private fun saveConfig() {
        getSharedPreferences(OverlayConfig.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(OverlayConfig.KEY_INTERVAL_MS, config.captureIntervalMs)
            .putInt(OverlayConfig.KEY_ALPHA, config.backgroundAlpha)
            .putInt(OverlayConfig.KEY_FORCE_EVERY, config.forceEvery)
            .putFloat(OverlayConfig.KEY_CHANGE_PCT, config.changePct)
            .putFloat(OverlayConfig.KEY_OCR_CONF, config.ocrConfidence)
            .putFloat(OverlayConfig.KEY_LINE_GAP, config.multilineGap)
            .putInt(OverlayConfig.KEY_PACING_MS, config.requestPacingMs)
            .putInt(OverlayConfig.KEY_TW_DEFER, config.typewriterDefer)
            .putBoolean(OverlayConfig.KEY_SYNC_DISPLAY, config.synchronousDisplay)
            .putInt(OverlayConfig.KEY_CLEAR_AFTER, config.clearAfterSeconds)
            .putBoolean(OverlayConfig.KEY_TEXT_OVERFLOW, config.allowTextOverflow)
            .putBoolean(OverlayConfig.KEY_NORMALIZE_CACHE, config.normalizeCacheKeys)
            .apply()
    }

    // ------------------------------------------------------- settings/cache io

    private fun exportSettings(uri: android.net.Uri) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val root = org.json.JSONObject().put("version", 1)
                    for ((prefsName, sectionName) in listOf(
                        OverlayConfig.PREFS_NAME to "overlay_config",
                        "glossary" to "glossary",
                    )) {
                        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                        val section = org.json.JSONObject()
                        for ((k, v) in prefs.all) {
                            val typed = org.json.JSONObject()
                            when (v) {
                                is Boolean -> typed.put("t", "b").put("v", v)
                                is Int -> typed.put("t", "i").put("v", v)
                                is Long -> typed.put("t", "l").put("v", v)
                                is Float -> typed.put("t", "f").put("v", v.toDouble())
                                else -> typed.put("t", "s").put("v", v.toString())
                            }
                            section.put(k, typed)
                        }
                        root.put(sectionName, section)
                    }
                    contentResolver.openOutputStream(uri)?.use {
                        it.write(root.toString().toByteArray(Charsets.UTF_8))
                    } ?: error("no output stream")
                }.isSuccess
            }
            Toast.makeText(
                this@MainActivity,
                if (ok) R.string.export_ok else R.string.export_failed,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun importSettings(uri: android.net.Uri) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val text = contentResolver.openInputStream(uri)?.bufferedReader()
                        ?.use { it.readText() } ?: error("no input stream")
                    val root = org.json.JSONObject(text)
                    for ((prefsName, sectionName) in listOf(
                        OverlayConfig.PREFS_NAME to "overlay_config",
                        "glossary" to "glossary",
                    )) {
                        val section = root.optJSONObject(sectionName) ?: continue
                        val editor = getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit()
                        for (k in section.keys()) {
                            val typed = section.getJSONObject(k)
                            val v = typed.get("v")
                            when (typed.optString("t", "s")) {
                                "b" -> editor.putBoolean(k, v as Boolean)
                                "i" -> editor.putInt(k, (v as Number).toInt())
                                "l" -> editor.putLong(k, (v as Number).toLong())
                                "f" -> editor.putFloat(k, (v as Number).toFloat())
                                else -> editor.putString(k, v.toString())
                            }
                        }
                        editor.apply()
                    }
                }.isSuccess
            }
            if (ok) {
                config = loadConfig()
                applyConfigToSliders()
            }
            Toast.makeText(
                this@MainActivity,
                if (ok) R.string.import_ok else R.string.import_failed,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun exportCache(uri: android.net.Uri) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val entries = com.overlaytranslator.cache.TranslationCache.get(this@MainActivity)
                        .allEntries()
                    val array = org.json.JSONArray()
                    for ((source, translated) in entries) {
                        array.put(org.json.JSONObject().put("s", source).put("t", translated))
                    }
                    contentResolver.openOutputStream(uri)?.use {
                        it.write(array.toString().toByteArray(Charsets.UTF_8))
                    } ?: error("no output stream")
                }.isSuccess
            }
            Toast.makeText(
                this@MainActivity,
                if (ok) R.string.export_ok else R.string.export_failed,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun importCache(uri: android.net.Uri) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val text = contentResolver.openInputStream(uri)?.bufferedReader()
                        ?.use { it.readText() } ?: error("no input stream")
                    val array = org.json.JSONArray(text)
                    val cache = com.overlaytranslator.cache.TranslationCache.get(this@MainActivity)
                    val normalize = config.normalizeCacheKeys
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        val source = obj.getString("s")
                        val translated = obj.getString("t")
                        cache.put(
                            com.overlaytranslator.cache.TranslationKeys.sourceHash(source, normalize),
                            source,
                            translated,
                        )
                    }
                }.isSuccess
            }
            if (ok) refreshCacheSize()
            Toast.makeText(
                this@MainActivity,
                if (ok) R.string.import_cache_ok else R.string.import_failed,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    // ---------------------------------------------------------------- model

    private fun downloadModel() {
        val btn = findViewById<MaterialButton>(R.id.downloadModelButton)
        btn.isEnabled = false
        Toast.makeText(this, R.string.model_downloading, Toast.LENGTH_SHORT).show()

        val url = modelUrlInput.text.toString().trim().ifBlank { OverlayConfig.DEFAULT_MODEL_URL }
        val lang = currentLang()
        lifecycleScope.launch {
            val ok = OpusMtTranslator.ensureModel(this@MainActivity, lang, url)
            btn.isEnabled = true
            Toast.makeText(
                this@MainActivity,
                if (ok) R.string.model_ready else R.string.model_failed,
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    companion object
}
