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
     * Average RGB of the ring just outside [box], clamped to the bitmap bounds.
     * Falls back to dark grey when the box touches every edge.
     */
    fun sampleAround(bitmap: Bitmap, box: Rect): Int {
        val left = (box.left - RING_PX).coerceAtLeast(0)
        val top = (box.top - RING_PX).coerceAtLeast(0)
        val right = (box.right + RING_PX).coerceAtMost(bitmap.width)
        val bottom = (box.bottom + RING_PX).coerceAtMost(bitmap.height)

        if (right <= left || bottom <= top) return FALLBACK

        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0

        // Top strip
        if (top < box.top) {
            for (y in top until box.top.coerceAtMost(bottom)) {
                for (x in left until right) {
                    accumulate(bitmap.getPixel(x, y)).let { (pr, pg, pb) ->
                        r += pr; g += pg; b += pb; n++
                    }
                }
            }
        }
        // Bottom strip
        if (bottom > box.bottom) {
            for (y in box.bottom.coerceAtLeast(top) until bottom) {
                for (x in left until right) {
                    accumulate(bitmap.getPixel(x, y)).let { (pr, pg, pb) ->
                        r += pr; g += pg; b += pb; n++
                    }
                }
            }
        }
        // Left strip
        if (left < box.left) {
            for (y in top until bottom) {
                for (x in left until box.left.coerceAtMost(right)) {
                    accumulate(bitmap.getPixel(x, y)).let { (pr, pg, pb) ->
                        r += pr; g += pg; b += pb; n++
                    }
                }
            }
        }
        // Right strip
        if (right > box.right) {
            for (y in top until bottom) {
                for (x in box.right.coerceAtLeast(left) until right) {
                    accumulate(bitmap.getPixel(x, y)).let { (pr, pg, pb) ->
                        r += pr; g += pg; b += pb; n++
                    }
                }
            }
        }

        if (n == 0) return FALLBACK
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
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

    private fun accumulate(pixel: Int): Triple<Int, Int, Int> {
        return Triple(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
    }

    private val FALLBACK = Color.rgb(32, 32, 32)
}
