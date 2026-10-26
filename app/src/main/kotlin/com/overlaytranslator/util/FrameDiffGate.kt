package com.overlaytranslator.util

import android.graphics.Bitmap
import kotlin.math.abs

/**
 * Skips processing when the current frame is visually identical to the last one.
 *
 * Compares 32x32 luma grids cell by cell: any real UI change — even just text
 * swapping inside an unchanged panel layout — flips enough cells to count.
 */
class FrameDiffGate(
    private val minChangedCells: Int = MIN_CHANGED_CELLS,
) {
    private var lastLuma: IntArray? = null

    /**
     * Returns true when the frame differs enough to be worth OCR-ing.
     * On first call always returns true.
     */
    @Synchronized
    fun shouldProcess(bitmap: Bitmap): Boolean = shouldProcess(lumaGrid(bitmap))

    /** Pure-ish overload: [luma] is a fresh GRID*GRID grid sampled from the frame. */
    @Synchronized
    fun shouldProcess(luma: IntArray): Boolean {
        val last = lastLuma
        lastLuma = luma
        if (last == null) return true
        return countChanged(last, luma, CELL_LUMA_DELTA) >= minChangedCells
    }

    @Synchronized
    fun reset() {
        lastLuma = null
    }

    companion object {
        /** Luma difference per grid cell before it counts as "changed" (0..255). */
        const val CELL_LUMA_DELTA = 12

        /** Grid cells that must change before the frame is worth OCR-ing. */
        const val MIN_CHANGED_CELLS = 6

        private const val GRID = 32

        /** 32x32 luma grid. Cheap and stable against encoder jitter. */
        fun lumaGrid(bitmap: Bitmap): IntArray {
            val scaled = Bitmap.createScaledBitmap(bitmap, GRID, GRID, true)
            val pixels = IntArray(GRID * GRID)
            scaled.getPixels(pixels, 0, GRID, 0, 0, GRID, GRID)
            if (scaled != bitmap) scaled.recycle()
            return luma(pixels)
        }

        /**
         * 32x32 luma grid sampled straight from an RGBA_8888 frame plane —
         * no Bitmap allocation and no full-frame copy. Stride-aware, JVM-testable.
         */
        fun lumaGridRgba(
            buffer: java.nio.ByteBuffer,
            width: Int,
            height: Int,
            rowStride: Int,
            pixelStride: Int,
        ): IntArray {
            val grid = IntArray(GRID * GRID)
            for (gy in 0 until GRID) {
                val row = (gy * height / GRID) * rowStride
                for (gx in 0 until GRID) {
                    val i = row + (gx * width / GRID) * pixelStride
                    val r = buffer.get(i).toInt() and 0xFF
                    val g = buffer.get(i + 1).toInt() and 0xFF
                    val b = buffer.get(i + 2).toInt() and 0xFF
                    grid[gy * GRID + gx] = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                }
            }
            return grid
        }

        /** Pure-logic: number of cells whose luma differs by more than [cellDelta]. */
        fun countChanged(a: IntArray, b: IntArray, cellDelta: Int): Int {
            require(a.size == b.size) { "grids must be the same size" }
            var count = 0
            for (i in a.indices) {
                if (abs(a[i] - b[i]) > cellDelta) count++
            }
            return count
        }

        /** Pure-logic: per-pixel luma (0..255) of packed ARGB pixels. */
        fun luma(pixels: IntArray): IntArray {
            return IntArray(pixels.size) { i ->
                val p = pixels[i]
                (0.299 * ((p shr 16) and 0xFF) +
                        0.587 * ((p shr 8) and 0xFF) +
                        0.114 * (p and 0xFF)).toInt()
            }
        }

        /**
         * 16x16 average hash. Downsamples to 16x16 grayscale and sets a bit for
         * every pixel above the frame mean. Deterministic across processes.
         * Used as the translation-cache screen key.
         */
        fun perceptualHash(bitmap: Bitmap): Long {
            val w = 16
            val h = 16
            val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
            val pixels = IntArray(w * h)
            scaled.getPixels(pixels, 0, w, 0, 0, w, h)
            if (scaled != bitmap) scaled.recycle()
            return perceptualHash(pixels, w, h)
        }

        /**
         * Pure-logic overload so the algorithm is testable on the JVM without
         * Android stubs. [pixels] is ARGB packed, [w]x[h] is the pixel grid.
         */
        fun perceptualHash(pixels: IntArray, w: Int, h: Int): Long {
            require(pixels.size == w * h) { "pixels.size must equal w*h" }
            val luma = luma(pixels)
            val mean = luma.sumOf { it.toLong() } / pixels.size
            var hash = 0L
            for (i in luma.indices) {
                if (luma[i] > mean) {
                    hash = hash or (1L shl i)
                }
            }
            return hash
        }

        fun hammingDistance(a: Long, b: Long): Int {
            var x = a xor b
            var count = 0
            while (x != 0L) {
                x = x and (x - 1)
                count++
            }
            return count
        }

        /** Hamming distance as a 0..1 fraction, useful for tests and thresholds. */
        fun similarity(a: Long, b: Long): Double {
            return 1.0 - hammingDistance(a, b).toDouble() / 64.0
        }
    }
}
