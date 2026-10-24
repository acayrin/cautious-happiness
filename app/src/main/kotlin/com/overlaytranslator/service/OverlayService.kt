package com.overlaytranslator.service

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.overlaytranslator.ocr.RenderedBlock
import com.overlaytranslator.overlay.TranslationView

/**
 * Owns the single full-screen overlay window that draws every translation,
 * plus a 1x1 invisible "poke" window. Not a [Service] — it is owned and driven
 * by [CaptureService].
 *
 * All WindowManager calls must happen on the main thread. The capture loop
 * posts here via [Handler].
 */
class OverlayService(private val context: Context) {

    private val wm = context.getSystemService(WindowManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var view: TranslationView? = null
    private var pokeView: View? = null
    private var pokeFlip = false
    private var allowOverflow = false

    /** Text may extend beyond the OCR box (applies to new bindings). */
    fun setAllowOverflow(value: Boolean) {
        mainHandler.post {
            allowOverflow = value
            view?.allowOverflow = value
        }
    }

    @Volatile
    private var hidden = false

    /** Replaces the visible set of translations. Safe to call from any thread. */
    fun update(blocks: List<RenderedBlock>, backgroundAlpha: Int) {
        mainHandler.post {
            val v = obtainView()
            v.bind(blocks, backgroundAlpha)
            v.alpha = if (hidden) 0f else 1f
        }
    }

    /**
     * Forces a WindowManager relayout of a 1x1 invisible window. The WM-level
     * screen mirror only recomposites on window events — without a periodic
     * nudge the capture feed can freeze on SurfaceView-based games.
     */
    fun poke() {
        mainHandler.post {
            pokeFlip = !pokeFlip
            val view = pokeView ?: View(context).also {
                pokeView = it
                runCatching { wm.addView(it, pokeParams()) }
            }
            val lp = view.layoutParams as? WindowManager.LayoutParams ?: return@post
            lp.x = if (pokeFlip) 0 else 1
            runCatching { wm.updateViewLayout(view, lp) }
        }
    }

    /** Shows a status line (top-right chip). Safe to call from any thread. */
    fun status(text: String) {
        mainHandler.post {
            obtainView().setStatus(text)
        }
    }

    /** Toggles the overlay invisible for [millis], then restores it. */
    fun hideTemporarily(millis: Long = 3000L) {
        mainHandler.post {
            hidden = true
            view?.alpha = 0f
            mainHandler.postDelayed({
                hidden = false
                view?.alpha = 1f
            }, millis)
        }
    }

    fun destroy() {
        mainHandler.post {
            view?.let { runCatching { wm.removeViewImmediate(it) } }
            view = null
            pokeView?.let { runCatching { wm.removeViewImmediate(it) } }
            pokeView = null
        }
    }

    private fun obtainView(): TranslationView {
        view?.let { return it }
        val created = TranslationView(context).apply { allowOverflow = this@OverlayService.allowOverflow }
        view = created
        runCatching { wm.addView(created, windowParams()) }
        return created
    }

    private fun windowParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            windowFlags(),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Capture frames use full-display coordinates (they include the
            // cutout letterbox bar); the window must span the cutout too or
            // everything shifts by the inset in cutout-left rotations.
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = "overlay-translator"
        }
    }

    private fun pokeParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(1, 1, overlayType(), windowFlags(), PixelFormat.TRANSLUCENT)
            .apply {
                gravity = Gravity.TOP or Gravity.START
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                title = "overlay-translator-poke"
            }
    }

    private fun overlayType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }
    }

    /**
     * FLAG_SECURE keeps the overlay out of screen captures — it must never be
     * visible to the capture pipeline (feedback) or to the WM mirror (its
     * handling of untrusted overlay windows was killing the capture feed).
     */
    private fun windowFlags(): Int {
        return WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_SECURE
    }
}
