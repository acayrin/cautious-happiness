package com.overlaytranslator.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

/** The pre-split models URL (models used to live on `main`) must not survive. */
class ModelUrlTest {

    @Test
    fun legacyMainModelsUrlFallsBackToDefault() {
        assertEquals(
            OverlayConfig.DEFAULT_MODEL_URL,
            OverlayConfig.modelUrl(OverlayConfig.LEGACY_MODEL_URL),
        )
    }

    @Test
    fun blankAndNullUseDefault() {
        assertEquals(OverlayConfig.DEFAULT_MODEL_URL, OverlayConfig.modelUrl(null))
        assertEquals(OverlayConfig.DEFAULT_MODEL_URL, OverlayConfig.modelUrl("   "))
    }

    @Test
    fun customUrlIsKept() {
        assertEquals(
            "https://example.com/models",
            OverlayConfig.modelUrl("https://example.com/models"),
        )
    }

    @Test
    fun defaultPointsAtModelsBranch() {
        assertEquals(
            "https://raw.githubusercontent.com/acayrin/cautious-happiness/models",
            OverlayConfig.DEFAULT_MODEL_URL,
        )
    }
}
