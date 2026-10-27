package com.overlaytranslator.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.overlaytranslator.audio.AudioSubtitleEngine
import com.overlaytranslator.cache.TranslationCache
import com.overlaytranslator.overlay.OverlayConfig
import com.overlaytranslator.ocr.MlKitOcrProcessor
import com.overlaytranslator.translate.Glossary
import com.overlaytranslator.translate.TranslatePipeline
import com.overlaytranslator.translate.OpusMtTranslator
import com.overlaytranslator.translate.RemoteTranslator
import com.overlaytranslator.translate.TranslationChain
import com.overlaytranslator.translate.Translator
import com.overlaytranslator.util.FrameDiffGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Foreground service that captures the screen, runs OCR + translation, and
 * pushes the results to [OverlayService].
 *
 * Lifecycle: MainActivity obtains a MediaProjection consent via
 * [MediaProjectionManager.createScreenCaptureIntent], then starts this service
 * with the resultCode / resultData as extras.
 */
class CaptureService : Service() {

    companion object {
        private const val TAG = "CaptureService"
        private const val HEAL_INTERVAL_MS = 1_000L

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        const val ACTION_START = "com.overlaytranslator.action.START"
        const val ACTION_STOP = "com.overlaytranslator.action.STOP"
        const val ACTION_HIDE_OVERLAY = "com.overlaytranslator.action.HIDE_OVERLAY"

        private const val NOTIFICATION_ID = 42

        /** True while a capture pipeline is live. UI state must derive from this,
         *  not from the activity's own field (activities get recreated). */
        @Volatile
        var isRunning = false
            private set

        fun start(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, CaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, CaptureService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private lateinit var notificationHelper: NotificationHelper
    private lateinit var overlay: OverlayService
    private lateinit var pipeline: TranslatePipeline

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var frameGate = FrameDiffGate()
    private var lastTickMillis = 0L
    private var framesSeen = 0
    private var skippedFrames = 0
    private val pendingFrame = AtomicReference<Bitmap?>()
    private val pipelineBusy = AtomicBoolean(false)
    private val displayManager by lazy { getSystemService(DisplayManager::class.java) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var screenDensityDpi = 0
    private var screenWidth = 0
    private var screenHeight = 0

    /**
     * The virtual display must be created while the source display is landscape
     * (the game). A mismatched source makes the WM mirror rotate content into
     * the buffer (garbage OCR coordinates) and then wedge permanently when the
     * source rotates. So: wait for landscape before creating, and re-kick the
     * surface when the feed goes silent (screen off, mid-session rotation).
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            ensureCaptureSurfaces()
        }
    }

    private val healTick = object : Runnable {
        private var ticks = 0
        override fun run() {
            ensureCaptureSurfaces()
            overlay.poke()
            if (++ticks % 5 == 0) {
                Log.i(TAG, "feed: $framesSeen frames in last 5s")
                framesSeen = 0
            }
            captureHandler?.postDelayed(this, HEAL_INTERVAL_MS)
        }
    }

    /**
     * Event-driven frame consumer. Runs on [captureHandler]. Every frame is
     * released within milliseconds — if the reader's queue backs up,
     * SurfaceFlinger's virtual-display producer wedges permanently and the
     * feed dies. Processing is throttled to [configCaptureInterval]; all other
     * frames are just closed.
     */
    private val imageListener = ImageReader.OnImageAvailableListener { reader ->
        framesSeen++
        val image = reader.acquireLatestImage() ?: return@OnImageAvailableListener

        val now = SystemClock.uptimeMillis()
        if (now - lastTickMillis < configCaptureInterval()) {
            image.close()
            return@OnImageAvailableListener
        }
        lastTickMillis = now
        processImage(image)
    }

    private var config = OverlayConfig()
    private var sourceLang: String = OverlayConfig.LANG_ZH
    private var backendCode: String = OverlayConfig.BACKEND_OPUSMT
    private var subtitleEngine: AudioSubtitleEngine? = null
    private lateinit var sharedTranslator: TranslationChain

    private fun loadConfig(): OverlayConfig {
        val p = getSharedPreferences(OverlayConfig.PREFS_NAME, MODE_PRIVATE)
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
            modelBaseUrl = OverlayConfig.modelUrl(
                p.getString(OverlayConfig.KEY_MODEL_URL, OverlayConfig.DEFAULT_MODEL_URL),
            ),
            audioEnabled = p.getBoolean(OverlayConfig.KEY_AUDIO_ENABLED, false),
            audioQuality = p.getString(
                OverlayConfig.KEY_AUDIO_QUALITY, OverlayConfig.AUDIO_FAST,
            ) ?: OverlayConfig.AUDIO_FAST,
        )
    }

    override fun onCreate() {
        super.onCreate()
        config = loadConfig()
        frameGate = FrameDiffGate(config.changedCells)
        RemoteTranslator.minIntervalMs = config.requestPacingMs.toLong().coerceIn(100L, 2000L)
        notificationHelper = NotificationHelper(this)
        notificationHelper.ensureChannel()
        overlay = OverlayService(this)
        overlay.setAllowOverflow(config.allowTextOverflow)

        val db = TranslationCache.get(this)
        sourceLang = getSharedPreferences(OverlayConfig.PREFS_NAME, MODE_PRIVATE)
            .getString(OverlayConfig.KEY_LANG, OverlayConfig.LANG_ZH) ?: OverlayConfig.LANG_ZH
        val localTranslator = Translator(sourceLang)
        backendCode = getSharedPreferences(OverlayConfig.PREFS_NAME, MODE_PRIVATE)
            .getString(OverlayConfig.KEY_BACKEND, OverlayConfig.BACKEND_OPUSMT) ?: OverlayConfig.BACKEND_OPUSMT
        val offline = OpusMtTranslator(this, sourceLang, config.modelBaseUrl)
        val translator = TranslationChain(sourceLang, localTranslator, backendCode, offline)
        sharedTranslator = translator
        scope.launch {
            Log.i(TAG, "Translation model ($sourceLang→en) downloaded=${localTranslator.ensureModelDownloaded()}")
        }
        pipeline = TranslatePipeline(
            ocr = MlKitOcrProcessor(sourceLang, config.multilineGap),
            translator = translator,
            glossary = loadGlossary(),
            cache = db,
            minConfidence = config.ocrConfidence,
            requireCjk = sourceLang == "zh" || sourceLang == "ja" || sourceLang == "ko",
            maxDefer = config.typewriterDefer,
            synchronousDisplay = config.synchronousDisplay,
            clearAfterSeconds = config.clearAfterSeconds,
            normalizeCache = config.normalizeCacheKeys,
        )
        // Fetch the offline translation model in the background when missing.
        scope.launch {
            OpusMtTranslator.ensureModel(this@CaptureService, sourceLang, config.modelBaseUrl)
        }
        // Warm up the OCR model so the first real frame doesn't pay its load time.
        scope.launch {
            runCatching { pipeline.process(Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)) }
            Log.i(TAG, "OCR model warmed up")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                if (resultData == null) {
                    Log.e(TAG, "Missing projection consent; refusing to start")
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (isRunning) {
                    // A stale "Start" tap must restart, never stack a second
                    // pipeline (two overlay windows kill all touch input).
                    Log.i(TAG, "Capture already running — restarting with new projection")
                    stopCapture()
                }
                startForegroundWithNotification()
                startCapture(resultCode, resultData)
            }
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
            }
            ACTION_HIDE_OVERLAY -> {
                overlay.hideTemporarily()
            }
            else -> {
                // Service restarted by the system without a projection — cannot continue.
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopCapture()
        pipeline.close()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------- capture

    private fun startForegroundWithNotification() {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, CaptureService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = notificationHelper.buildServiceNotification(stopIntent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        overlay.activate()
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val projection = projectionManager.getMediaProjection(resultCode, resultData) ?: run {
            Log.e(TAG, "getMediaProjection returned null")
            stopSelf()
            return
        }
        mediaProjection = projection

        captureThread = HandlerThread("capture").also { it.start() }
        captureHandler = Handler(captureThread!!.looper)

        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // Fires also when WE stop an old projection during a restart —
                // only a user-initiated stop may tear everything down.
                if (mediaProjection !== projection) return
                Log.i(TAG, "MediaProjection stopped by user")
                stopCapture()
                stopSelf()
            }
        }.also { projectionCallback = it }, captureHandler)

        displayManager.registerDisplayListener(displayListener, captureHandler)
        ensureCaptureSurfaces()
        captureHandler?.post(healTick)
        startSubtitles(projection)
        isRunning = true
        Log.i(TAG, "Capture started${if (imageReader != null) " at ${screenWidth}x$screenHeight" else ": waiting for landscape"}")
    }

    /** Audio subtitles: transcribe the captured app's audio to English. */
    private fun startSubtitles(projection: MediaProjection) {
        if (!config.audioEnabled) return
        subtitleEngine = AudioSubtitleEngine(
            context = this,
            // Shared chain (mutex-serialized): a second translator instance
            // would load a whole extra OPUS-MT model into RAM — on a device at
            // its memory limit that gets the game killed by the system cleaner.
            translator = sharedTranslator,
            cache = TranslationCache.get(this),
            normalizeCache = config.normalizeCacheKeys,
            clearAfterSeconds = config.clearAfterSeconds,
            scope = scope,
            onSubtitle = { overlay.subtitle(it) },
            onCleared = { overlay.subtitle(null) },
        ).also {
            it.start(projection, sourceLang, config.audioQuality, config.modelBaseUrl)
        }
    }

    private fun stopCapture() {
        isRunning = false
        subtitleEngine?.stop()
        subtitleEngine = null
        runCatching { displayManager.unregisterDisplayListener(displayListener) }
        captureHandler?.removeCallbacks(healTick)
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null

        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
        projectionCallback?.let { cb -> runCatching { mediaProjection?.unregisterCallback(cb) } }
        projectionCallback = null
        runCatching { mediaProjection?.stop() }
        mediaProjection = null

        overlay.destroy()
        frameGate.reset()
    }

    private fun configCaptureInterval(): Long = config.captureIntervalMillis

    /**
     * Creates the capture surfaces on first call and adapts them whenever the
     * display size or rotation changes (works in all 4 rotations). Android 14+
     * forbids a second MediaProjection#createVirtualDisplay, so the display is
     * adapted via setSurface + resize instead.
     */
    @Synchronized
    private fun ensureCaptureSurfaces() {
        val oldW = screenWidth
        val oldH = screenHeight
        readDisplayMetrics()
        if (imageReader == null) {
            createCaptureSurfaces()
            frameGate.reset()
            imageReader?.setOnImageAvailableListener(imageListener, captureHandler)
            Log.i(TAG, "Capture surfaces ready at ${screenWidth}x$screenHeight")
        } else if (screenWidth != oldW || screenHeight != oldH) {
            Log.i(TAG, "Display is ${screenWidth}x$screenHeight; adapting capture surfaces")
            val oldReader = imageReader
            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            runCatching {
                virtualDisplay?.setSurface(imageReader!!.surface)
                virtualDisplay?.resize(screenWidth, screenHeight, screenDensityDpi)
            }.onFailure { Log.w(TAG, "Capture adapt failed", it) }
            runCatching { oldReader?.close() }
            frameGate.reset()
            imageReader?.setOnImageAvailableListener(imageListener, captureHandler)
        }
    }

    private fun createCaptureSurfaces() {
        imageReader = ImageReader.newInstance(
            screenWidth,
            screenHeight,
            PixelFormat.RGBA_8888,
            2,
        )

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "overlay-translator",
            screenWidth,
            screenHeight,
            screenDensityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            null,
        )
    }

    /**
     * Called on [captureHandler] with an acquired image. Converts it to a
     * bitmap and, if it differs enough from the last one we processed, runs
     * the translation pipeline.
     */
    private fun processImage(image: Image) {
        // Gate first on a tiny grid sampled from the plane: converting every
        // frame to a Bitmap just to diff it burns a full-frame copy per tick.
        val changed = try {
            val plane = image.planes[0]
            frameGate.shouldProcess(
                FrameDiffGate.lumaGridRgba(
                    plane.buffer, image.width, image.height,
                    plane.rowStride, plane.pixelStride,
                ),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Frame gate failed", t)
            true
        }
        if (!changed && ++skippedFrames < config.forceEvery) {
            Log.d(TAG, "Frame skipped (unchanged)")
            overlay.status("skipped (unchanged)")
            image.close()
            return
        }
        skippedFrames = 0

        val bitmap: Bitmap? = try {
            imageToBitmap(image)
        } catch (t: Throwable) {
            Log.w(TAG, "Frame conversion failed", t)
            null
        } finally {
            image.close()
        }

        bitmap?.let { submitFrame(it) }
    }

    /**
     * Coalesces pipeline runs: one at a time, and a newer frame replaces the
     * pending one, so stale queued frames never burn translation requests.
     * Combined with the forced reprocess every [config] forceEvery skipped
     * frames, a stale partial result (e.g. mid-typewriter dialog) always gets
     * healed.
     */
    private fun submitFrame(frame: Bitmap) {
        pendingFrame.getAndSet(frame)?.recycle()
        if (pipelineBusy.compareAndSet(false, true)) {
            scope.launch { drainFrames() }
        }
    }

    private suspend fun drainFrames() {
        while (true) {
            val current = pendingFrame.getAndSet(null) ?: break
            val result = try {
                pipeline.process(current) { partial ->
                    overlay.update(partial.blocks, config.backgroundAlpha)
                    overlay.status(
                        if (partial.translateMs == 0L) {
                            "cached · ${partial.blocks.size} blocks"
                        } else {
                            "ocr ${partial.ocrMs}ms · tr ${partial.translateMs}ms · ${partial.blocks.size} blocks"
                        }
                    )
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Translation pipeline failed", t)
                TranslatePipeline.FrameResult(emptyList(), 0, 0)
            }
            // Results are emitted via the onUpdate callback (cache hits first,
            // then the full result) — processing is strictly in frame order.
            val superseded = pendingFrame.get() != null
            Log.i(
                TAG,
                "Frame processed: ${result.blocks.size} blocks in ocr ${result.ocrMs}ms + tr ${result.translateMs}ms" +
                        " · conf ${(result.avgConfidence * 100).toInt()}%" +
                        if (superseded) " (newer frame queued)" else "",
            )
            current.recycle()
        }
        pipelineBusy.set(false)
        if (pendingFrame.get() != null && pipelineBusy.compareAndSet(false, true)) {
            scope.launch { drainFrames() }
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888,
        )
        bitmap.copyPixelsFromBuffer(buffer)
        return if (rowPadding == 0) {
            bitmap
        } else {
            Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height).also {
                bitmap.recycle()
            }
        }
    }

    private fun readDisplayMetrics() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenDensityDpi = metrics.densityDpi
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
    }

    private fun loadGlossary(): Glossary {
        return runCatching {
            val prefs = getSharedPreferences("glossary", Context.MODE_PRIVATE)
            val raw = prefs.getString("terms", "") ?: ""
            if (raw.isEmpty()) Glossary.EMPTY else Glossary.parse(raw)
        }.getOrDefault(Glossary.EMPTY)
    }
}
