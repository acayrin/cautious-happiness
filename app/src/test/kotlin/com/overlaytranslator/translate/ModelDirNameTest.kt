package com.overlaytranslator.translate

import org.junit.Assert.assertEquals
import org.junit.Test

/** HPLT languages get `hplt-<lang>-en`; the rest keep `opus-mt-<lang>-en`. */
class ModelDirNameTest {

    @Test
    fun hpltLanguagesUseHpltDirs() {
        for (lang in listOf("ja", "ko", "vi", "hi")) {
            assertEquals("hplt-$lang-en", OpusMtTranslator.modelDirName(lang))
        }
    }

    @Test
    fun languagesWithoutHpltModelsKeepOpusPaths() {
        for (lang in listOf("zh", "fr", "es", "de", "it", "pt", "nl", "pl", "sv", "tr", "id", "cs")) {
            assertEquals("opus-mt-$lang-en", OpusMtTranslator.modelDirName(lang))
        }
    }
}
