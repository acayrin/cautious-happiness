package com.overlaytranslator.cache

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure-JVM tests for [TranslationKeys.normalizeForCache] and normalized hashing. */
class NormalizeForCacheTest {

    @Test
    fun `punctuation symbols and newlines are ignored`() {
        val a = "あかっ泣いても、蓋とるな～……♪"
        val b = "あかっ泣いても蓋とるな\n♪"
        assertEquals(
            TranslationKeys.normalizeForCache(a),
            TranslationKeys.normalizeForCache(b),
        )
    }

    @Test
    fun `full width and half width fold together`() {
        assertEquals(
            TranslationKeys.normalizeForCache("ＡＢＣ１２３"),
            TranslationKeys.normalizeForCache("abc123"),
        )
    }

    @Test
    fun `normalized hash matches across ocr variants`() {
        val a = TranslationKeys.sourceHash("こんにちは、世界。", normalize = true)
        val b = TranslationKeys.sourceHash("こんにちは　世界♪", normalize = true)
        assertEquals(a, b)
    }

    @Test
    fun `distinct text still hashes differently when normalized`() {
        val a = TranslationKeys.sourceHash("こんにちは", normalize = true)
        val b = TranslationKeys.sourceHash("さようなら", normalize = true)
        assertNotEquals(a, b)
    }

    private fun assertNotEquals(a: String, b: String) {
        org.junit.Assert.assertNotEquals(a, b)
    }
}
