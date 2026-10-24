package com.overlaytranslator.ocr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for [Ocr.containsCjk]. */
class OcrCjkFilterTest {

    @Test
    fun `chinese text passes`() {
        assertTrue(Ocr.containsCjk("游戏公告"))
    }

    @Test
    fun `japanese text passes`() {
        assertTrue(Ocr.containsCjk("こんにちは"))
    }

    @Test
    fun `korean text passes`() {
        assertTrue(Ocr.containsCjk("안녕하세요"))
    }

    @Test
    fun `english and numeric UI noise is excluded`() {
        assertFalse(Ocr.containsCjk("SHOP"))
        assertFalse(Ocr.containsCjk("445"))
        assertFalse(Ocr.containsCjk("5/10"))
        assertFalse(Ocr.containsCjk("https://witchweapon.wiki/"))
        assertFalse(Ocr.containsCjk("ANNOUNCEMENT"))
        assertFalse(Ocr.containsCjk(""))
    }

    @Test
    fun `mixed text with latin acronym passes`() {
        assertTrue(Ocr.containsCjk("RANK3 カード"))
        assertTrue(Ocr.containsCjk("WifiをONに"))
    }
}
