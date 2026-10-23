package com.overlaytranslator.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for cache key derivation. Uses the primitive [TranslationKeys.bboxKey]
 * overload so Android's `Rect` stub isn't involved.
 */
class TranslationCacheKeyTest {

    @Test
    fun `bbox key normalises across resolutions`() {
        // Same logical box at 1080 and 1440 wide must map to the same key.
        val a = TranslationKeys.bboxKey(100, 200, 400, 300, 1080)
        val b = TranslationKeys.bboxKey(133, 267, 533, 400, 1440)
        assertEquals(a, b)
    }

    @Test
    fun `bbox key distinguishes different positions`() {
        val a = TranslationKeys.bboxKey(100, 200, 400, 300, 1080)
        val b = TranslationKeys.bboxKey(500, 200, 800, 300, 1080)
        assertNotEquals(a, b)
    }

    @Test
    fun `bbox key handles zero width screen gracefully`() {
        assertEquals("10,20,30,40", TranslationKeys.bboxKey(10, 20, 30, 40, 0))
    }

    @Test
    fun `bbox key is deterministic`() {
        val a = TranslationKeys.bboxKey(0, 0, 100, 50, 1080)
        val b = TranslationKeys.bboxKey(0, 0, 100, 50, 1080)
        assertEquals(a, b)
    }

    @Test
    fun `source hash is stable and content sensitive`() {
        val h1 = TranslationKeys.sourceHash("魔女兵器")
        val h2 = TranslationKeys.sourceHash("魔女兵器")
        val h3 = TranslationKeys.sourceHash("魔女 兵器")
        assertEquals(h1, h2)
        assertNotEquals(h1, h3)
    }

    @Test
    fun `source hash has expected length and charset`() {
        val h = TranslationKeys.sourceHash("hello")
        assertEquals(64, h.length) // sha-256 hex
        assertTrue(h.all { it in "0123456789abcdef" })
    }

    @Test
    fun `source hash differs for different text`() {
        assertNotEquals(
            TranslationKeys.sourceHash("a"),
            TranslationKeys.sourceHash("b"),
        )
    }

    @Test
    fun `source hash handles empty string`() {
        // sha256 of empty input is a known constant
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            TranslationKeys.sourceHash(""),
        )
    }

    @Test
    fun `screen hash is hex of the long`() {
        assertEquals("ff", TranslationKeys.screenHash(255L))
        assertEquals("0", TranslationKeys.screenHash(0L))
        assertEquals("10", TranslationKeys.screenHash(16L))
    }
}
