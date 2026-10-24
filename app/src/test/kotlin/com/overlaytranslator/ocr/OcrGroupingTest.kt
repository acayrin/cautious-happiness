package com.overlaytranslator.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure-JVM tests for [Ocr.groupLines]. */
class OcrGroupingTest {

    @Test
    fun `name plate stays separate from dialog lines`() {
        // 24px-tall name plate at y=0..24, dialog lines at y=60..84 and 88..112
        // (gap 36 > 0.7*24) — plate must not merge with the wrapped dialog.
        val groups = Ocr.groupLines(
            tops = intArrayOf(60, 88, 0),
            bottoms = intArrayOf(84, 112, 24),
        )
        assertEquals(2, groups.size)
        assertEquals(listOf(2), groups[0])
        assertEquals(listOf(0, 1), groups[1])
    }

    @Test
    fun `wrapped dialog lines stay together`() {
        val groups = Ocr.groupLines(
            tops = intArrayOf(0, 30, 60),
            bottoms = intArrayOf(24, 54, 84),
        )
        assertEquals(listOf(listOf(0, 1, 2)), groups)
    }

    @Test
    fun `empty input yields no groups`() {
        assertEquals(emptyList<List<Int>>(), Ocr.groupLines(intArrayOf(), intArrayOf()))
    }
}
