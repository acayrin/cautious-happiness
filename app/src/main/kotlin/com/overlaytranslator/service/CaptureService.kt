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
import com.overlaytranslator.cache.TranslationCache
import com.overlaytranslator.overlay.OverlayConfig
import com.overlaytranslator.ocr.MlKitOcrProcessor
import com.overlaytranslator.translate.Glossary
import com.overlaytranslator.translate.TranslatePipeline
import com.overlaytranslator.translate.Translator
import com.overlaytranslator.util.FrameDiffGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

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
    private val frameGate = FrameDiffGate()
    private var lastTickMillis = 0L
    private var framesSeen = 0
    private val displayManager by lazy { getSystemService(DisplayManager::class.java) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pipelineSeq = AtomicInteger()

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

    override fun onCreate() {
        super.onCreate()
        notificationHelper = NotificationHelper(this)
        notificationHelper.ensureChannel()
        overlay = OverlayService(this)

        val db = TranslationCache.get(this)
        val lang = getSharedPreferences(OverlayConfig.PREFS_NAME, MODE_PRIVATE)
            .getString(OverlayConfig.KEY_LANG, OverlayConfig.LANG_ZH) ?: OverlayConfig.LANG_ZH
        val translator = Translator(lang)
        scope.launch {
            Log.i(TAG, "Translation model ($lang→en) downloaded=${translator.ensureModelDownloaded()}")
        }
        pipeline = TranslatePipeline(
            ocr = MlKitOcrProcessor(lang),
            translator = translator,
            glossary = loadGlossary(),
            cache = db,
        )
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
                Log.i(TAG, "MediaProjection stopped by user")
                stopCapture()
                stopSelf()
            }
        }, captureHandler)

        displayManager.registerDisplayListener(displayListener, captureHandler)
        ensureCaptureSurfaces()
        captureHandler?.post(healTick)
        Log.i(TAG, "Capture started${if (imageReader != null) " at ${screenWidth}x$screenHeight" else ": waiting for landscape"}")
    }

    private fun stopCapture() {
        runCatching { displayManager.unregisterDisplayListener(displayListener) }
        captureHandler?.removeCallbacks(healTick)
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null

        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
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
        val bitmap: Bitmap? = try {
            imageToBitmap(image)
        } catch (t: Throwable) {
            Log.w(TAG, "Frame conversion failed", t)
            null
        } finally {
            image.close()
        }

        val frame = bitmap ?: return
        if (!frameGate.shouldProcess(frame)) {
            Log.d(TAG, "Frame skipped (unchanged)")
            frame.recycle()
            return
        }

        scope.launch {
            val seq = pipelineSeq.incrementAndGet()
            val rendered = try {
                pipeline.process(frame)
            } catch (t: Throwable) {
                Log.w(TAG, "Translation pipeline failed", t)
                emptyList()
            }
            // A slower run from an older frame must not overwrite a newer one.
            val stale = seq != pipelineSeq.get()
            Log.i(TAG, "Frame processed: ${rendered.size} blocks${if (stale) " (stale, dropped)" else ""}")
            if (!stale) overlay.update(rendered, config.backgroundAlpha)
            frame.recycle()
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
