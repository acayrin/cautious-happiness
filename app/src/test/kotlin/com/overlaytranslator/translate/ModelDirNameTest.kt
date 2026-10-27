package com.overlaytranslator.translate

import com.overlaytranslator.overlay.OverlayConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/** Japanese has a second model; every other language must keep its old dir. */
class ModelDirNameTest {

    @Test
    fun japaneseUsesHpltDirByDefault() {
        assertEquals("ja-hplt-en", OpusMtTranslator.modelDirName("ja"))
        assertEquals("ja-hplt-en", OpusMtTranslator.modelDirName("ja", OverlayConfig.JA_HPLT))
    }

    @Test
    fun japaneseCanFallBackToHelsinki() {
        assertEquals("opus-mt-ja-en", OpusMtTranslator.modelDirName("ja", OverlayConfig.JA_OPUS))
    }

    @Test
    fun otherLanguagesIgnoreTheJapaneseSetting() {
        for (lang in listOf("zh", "ko", "fr", "pt", "hi")) {
            assertEquals("opus-mt-$lang-en", OpusMtTranslator.modelDirName(lang, OverlayConfig.JA_HPLT))
            assertEquals("opus-mt-$lang-en", OpusMtTranslator.modelDirName(lang, OverlayConfig.JA_OPUS))
        }
    }
}
