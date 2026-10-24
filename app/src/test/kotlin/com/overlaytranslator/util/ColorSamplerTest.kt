package com.overlaytranslator.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure-JVM tests for [ColorSampler.median]. */
class ColorSamplerTest {

    @Test
    fun `median ignores outlier pixels`() {
        // A few bright edge pixels must not flip a dark background to light.
        val values = intArrayOf(30, 32, 31, 33, 250, 245, 32)
        assertEquals(32, ColorSampler.median(values))
    }

    @Test
    fun `median of even count returns upper middle`() {
        assertEquals(3, ColorSampler.median(intArrayOf(4, 1, 3, 2)))
    }

    @Test
    fun `median of empty array is zero`() {
        assertEquals(0, ColorSampler.median(intArrayOf()))
    }

    @Test
    fun `median does not mutate its input`() {
        val values = intArrayOf(5, 1, 3)
        ColorSampler.median(values)
        assertEquals(5, values[0])
        assertEquals(1, values[1])
        assertEquals(3, values[2])
    }
}
