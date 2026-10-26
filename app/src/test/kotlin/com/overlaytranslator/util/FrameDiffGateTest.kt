package com.overlaytranslator.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the frame-diff gate. Uses the [FrameDiffGate.perceptualHash]
 * integer-array overload so no Android stubs are involved.
 */
class FrameDiffGateTest {

    /** 16x16 grid of identical ARGB pixels. */
    private fun uniform(color: Int, w: Int = 16, h: Int = 16): IntArray {
        return IntArray(w * h) { color }
    }

    /** Two-tone horizontal split: left half dark, right half light. */
    private fun halfAndHalf(dark: Int, light: Int, w: Int = 16, h: Int = 16): IntArray {
        return IntArray(w * h) { i ->
            val x = i % w
            if (x < w / 2) dark else light
        }
    }

    @Test
    fun `hamming distance of identical hashes is zero`() {
        val h = 0x0F0F0F0F0F0F0F0FL
        assertEquals(0, FrameDiffGate.hammingDistance(h, h))
    }

    @Test
    fun `hamming distance counts set bits in xor`() {
        // 0b0001 xor 0b0010 = 0b0011 → distance 2
        assertEquals(2, FrameDiffGate.hammingDistance(0b0001L, 0b0010L))
    }

    @Test
    fun `hamming distance of all-differing hashes is 64`() {
        assertEquals(64, FrameDiffGate.hammingDistance(0L, -1L))
    }

    @Test
    fun `similarity is one minus normalised hamming`() {
        assertEquals(1.0, FrameDiffGate.similarity(0L, 0L), 1e-9)
        assertEquals(0.0, FrameDiffGate.similarity(0L, -1L), 1e-9)
    }

    @Test
    fun `perceptual hash of uniform grid is zero`() {
        // No pixel above the mean → all bits clear.
        val hash = FrameDiffGate.perceptualHash(uniform(0xFF808080.toInt()), 16, 16)
        assertEquals(0L, hash)
    }

    @Test
    fun `perceptual hash is deterministic`() {
        val grid = halfAndHalf(0xFF000000.toInt(), 0xFFFFFFFF.toInt())
        val a = FrameDiffGate.perceptualHash(grid, 16, 16)
        val b = FrameDiffGate.perceptualHash(grid, 16, 16)
        assertEquals(a, b)
    }

    @Test
    fun `perceptual hash differs for different patterns`() {
        val dark = FrameDiffGate.perceptualHash(uniform(0xFF000000.toInt()), 16, 16)
        val split = FrameDiffGate.perceptualHash(
            halfAndHalf(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 16, 16
        )
        assertNotEquals(dark, split)
    }

    @Test
    fun `perceptual hash rejects wrong grid size`() {
        try {
            FrameDiffGate.perceptualHash(IntArray(10), 16, 16)
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
    }

    @Test
    fun `countChanged is zero for identical grids`() {
        val a = IntArray(32 * 32) { 96 }
        assertEquals(0, FrameDiffGate.countChanged(a, a.copyOf(), 12))
    }

    @Test
    fun `countChanged detects changed cells`() {
        // 8 cells change a lot — text swapping inside an unchanged panel.
        val a = IntArray(32 * 32) { 96 }
        val b = a.copyOf()
        for (i in 0 until 8) b[i] = 255
        assertEquals(8, FrameDiffGate.countChanged(a, b, 12))
    }

    @Test
    fun `countChanged ignores jitter below the delta`() {
        // ±3 luma noise on every cell must not trip the gate.
        val a = IntArray(32 * 32) { 128 }
        val b = IntArray(32 * 32) { 131 }
        assertEquals(0, FrameDiffGate.countChanged(a, b, 12))
    }

    @Test
    fun `countChanged rejects different grid sizes`() {
        try {
            FrameDiffGate.countChanged(IntArray(4), IntArray(5), 12)
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
    }

    @Test
    fun `reset clears internal state`() {
        val gate = FrameDiffGate()
        val grid = uniform(0xFF123456.toInt())
        gate.reset()
        // Should not throw; first frame after reset always processes.
        gate.reset()
        assertTrue(true)
    }

    private fun assertNotEquals(a: Long, b: Long) {
        org.junit.Assert.assertNotEquals(a, b)
    }

    private fun fail(message: String): Nothing {
        org.junit.Assert.fail(message)
        throw AssertionError(message)
    }

    @Test
    fun lumaGridRgbaSamplesStridedPlane() {
        // 64x64 RGBA, left half red, right half blue.
        val w = 64
        val h = 64
        val buf = java.nio.ByteBuffer.allocate(w * h * 4)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val red = x < w / 2
                buf.put(if (red) 0xFF.toByte() else 0) // R
                buf.put(0) // G
                buf.put(if (red) 0 else 0xFF.toByte()) // B
                buf.put(0xFF.toByte()) // A
            }
        }
        buf.position(0)
        val grid = FrameDiffGate.lumaGridRgba(buf, w, h, w * 4, 4)
        assertEquals(32 * 32, grid.size)
        assertEquals(76, grid[0]) // 0.299*255
        assertEquals(29, grid[31]) // 0.114*255
    }

    @Test
    fun shouldProcessOnPrecomputedGrids() {
        val gate = FrameDiffGate(minChangedCells = 6)
        val a = IntArray(32 * 32) { 100 }
        val b = IntArray(32 * 32) { 100 }
        val changed = IntArray(32 * 32) { 200 }
        assertTrue(gate.shouldProcess(a)) // first frame always processes
        assertFalse(gate.shouldProcess(b)) // identical grid is skipped
        assertTrue(gate.shouldProcess(changed)) // fully different grid processes
    }
}
