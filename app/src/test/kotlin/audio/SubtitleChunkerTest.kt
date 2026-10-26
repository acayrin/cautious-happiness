package com.overlaytranslator.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleChunkerTest {

    private fun tone(seconds: Int = 4, amp: Int = 8000): ShortArray {
        val n = seconds * 16000
        return ShortArray(n) { i ->
            (amp * kotlin.math.sin(2.0 * Math.PI * 440.0 * i / 16000.0)).toInt().toShort()
        }
    }

    @Test
    fun silentWindowIsDropped() {
        val c = SubtitleChunker()
        assertNull(c.add(ShortArray(4 * 16000)))
    }

    @Test
    fun quietWindowBelowThresholdIsDropped() {
        val c = SubtitleChunker()
        assertNull(c.add(tone(amp = 100)))
    }

    @Test
    fun voicedWindowIsEmittedAsFloat32() {
        val c = SubtitleChunker()
        val out = c.add(tone())
        assertNotNull(out)
        assertEquals(4 * 16000, out!!.size)
        assertTrue(out.all { it in -1f..1f })
    }

    @Test
    fun partialInputAccumulatesUntilWindowIsFull() {
        val c = SubtitleChunker()
        val s = tone()
        assertNull(c.add(s.copyOf(8000)))
        assertNotNull(c.add(s.copyOfRange(8000, s.size)))
    }

    @Test
    fun mostlySilentWindowIsDropped() {
        val c = SubtitleChunker()
        // 0.4 s voiced out of 4 s = 10 % of blocks < 25 % speech ratio.
        val mix = ShortArray(4 * 16000)
        tone(seconds = 1).copyInto(mix, 0, 0, 6400)
        assertNull(c.add(mix))
    }
}
