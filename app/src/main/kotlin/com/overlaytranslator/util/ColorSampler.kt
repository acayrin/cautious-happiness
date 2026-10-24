package com.overlaytranslator.util

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect

/**
 * Samples the pixels around a bounding box to pick a background colour that
 * roughly matches the surrounding UI. Cheap approximation of inpainting.
 */
object ColorSampler {

    private const val RING_PX = 10

    /**
     * Median RGB of the ring just outside [box], clamped to the bitmap bounds.
     * Median instead of mean so mixed edges (text spill, neighbouring UI) can't
     * skew the brightness decision. Falls back to dark grey when the box
     * touches every edge.
     */
    fun sampleAround(bitmap: Bitmap, box: Rect): Int {
        val left = (box.left - RING_PX).coerceAtLeast(0)
        val top = (box.top - RING_PX).coerceAtLeast(0)
        val right = (box.right + RING_PX).coerceAtMost(bitmap.width)
        val bottom = (box.bottom + RING_PX).coerceAtMost(bitmap.height)

        if (right <= left || bottom <= top) return FALLBACK

        val pixels = ArrayList<Int>()
        fun add(x: Int, y: Int) {
            if (x in left until right && y in top until bottom) pixels.add(bitmap.getPixel(x, y))
        }
        // Ring: full rows above/below the box, full columns left/right of it.
        for (y in top until bottom) {
            for (x in left until right) {
                if (y in box.top until box.bottom && x in box.left until box.right) continue
                add(x, y)
            }
        }

        if (pixels.isEmpty()) return FALLBACK
        val n = pixels.size
        val rs = IntArray(n)
        val gs = IntArray(n)
        val bs = IntArray(n)
        for (i in 0 until n) {
            val p = pixels[i]
            rs[i] = Color.red(p)
            gs[i] = Color.green(p)
            bs[i] = Color.blue(p)
        }
        return Color.rgb(median(rs), median(gs), median(bs))
    }

    /** Middle value of a sorted copy. Robust against a few outlier pixels. */
    fun median(values: IntArray): Int {
        if (values.isEmpty()) return 0
        val sorted = values.copyOf().also { it.sort() }
        return sorted[sorted.size / 2]
    }

    /** Perceived luminance in 0..1. Used to flip text colour for contrast. */
    fun luminance(@Suppress("UNUSED_PARAMETER") color: Int): Double {
        val r = Color.red(color) / 255.0
        val g = Color.green(color) / 255.0
        val b = Color.blue(color) / 255.0
        return 0.299 * r + 0.587 * g + 0.114 * b
    }

    /** Black or white, whichever contrasts better with [background]. */
    fun contrastingText(background: Int): Int {
        return if (luminance(background) > 0.5) Color.BLACK else Color.WHITE
    }

    private val FALLBACK = Color.rgb(32, 32, 32)
}
