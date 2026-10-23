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
import com.overlaytranslator.translate.Translator
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
    private lateinit var languageSpinner: android.widget.Spinner

    private var running = false

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshPermissionUi() }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshPermissionUi() }

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
        languageSpinner = findViewById(R.id.languageSpinner)
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
                val fps = (OverlayConfig.MIN_FPS + progress * STEP_FPS)
                    .coerceIn(OverlayConfig.MIN_FPS, OverlayConfig.MAX_FPS)
                fpsLabel.text = getString(R.string.settings_fps_value, fps)
                if (fromUser) {
                    config = config.copy(fps = fps)
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
        findViewById<MaterialButton>(R.id.glossaryButton).setOnClickListener {
            startActivity(Intent(this, GlossaryActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.clearCacheButton).setOnClickListener {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    TranslationCache.get(this@MainActivity).clear()
                }
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
        fpsLabel.text = getString(R.string.settings_fps_value, config.fps)
        fpsSeekBar.progress = ((config.fps - OverlayConfig.MIN_FPS) / STEP_FPS).toInt()

        val pct = config.backgroundAlpha * 100 / 255
        opacityLabel.text = getString(R.string.settings_opacity_value, pct)
        opacitySeekBar.progress = pct

        val codes = resources.getStringArray(R.array.source_language_codes)
        languageSpinner.setSelection(codes.indexOf(currentLang()).coerceAtLeast(0))
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

    // ---------------------------------------------------------------- config

    private fun loadConfig(): OverlayConfig {
        val p = getSharedPreferences(OverlayConfig.PREFS_NAME, Context.MODE_PRIVATE)
        return OverlayConfig(
            fps = p.getFloat(OverlayConfig.KEY_FPS, 1.0f),
            backgroundAlpha = p.getInt(OverlayConfig.KEY_ALPHA, 230),
        )
    }

    private fun saveConfig() {
        getSharedPreferences(OverlayConfig.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(OverlayConfig.KEY_FPS, config.fps)
            .putInt(OverlayConfig.KEY_ALPHA, config.backgroundAlpha)
            .apply()
    }

    // ---------------------------------------------------------------- model

    private fun downloadModel() {
        val btn = findViewById<MaterialButton>(R.id.downloadModelButton)
        btn.isEnabled = false
        Toast.makeText(this, R.string.model_downloading, Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                Translator(currentLang()).use { it.ensureModelDownloaded() }
            }
            withContext(Dispatchers.Main) {
                btn.isEnabled = true
                Toast.makeText(
                    this@MainActivity,
                    if (ok) R.string.model_ready else R.string.model_failed,
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    companion object {
        private const val STEP_FPS = 0.1f
    }
}
