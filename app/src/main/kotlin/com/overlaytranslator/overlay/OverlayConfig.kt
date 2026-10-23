package com.overlaytranslator.overlay

/**
 * Tunables for the overlay. Read from SharedPreferences so they survive restarts.
 */
data class OverlayConfig(
    /** 0.5 .. 2.0 captures per second. 1.0 is the default. */
    val fps: Float = 1.0f,
    /** Background opacity behind each translation, 0..255. */
    val backgroundAlpha: Int = 230,
    /** When true the overlay is temporarily invisible (used by the "hide" toggle). */
    val hidden: Boolean = false,
) {
    val captureIntervalMillis: Long
        get() = (1000L / fps.coerceIn(MIN_FPS, MAX_FPS)).toLong()

    companion object {
        const val MIN_FPS = 0.5f
        const val MAX_FPS = 2.0f

        const val PREFS_NAME = "overlay_config"
        const val KEY_FPS = "fps"
        const val KEY_ALPHA = "background_alpha"
        const val KEY_LANG = "source_language"

        const val LANG_ZH = "zh"
        const val LANG_JA = "ja"
        const val LANG_KO = "ko"
        const val LANG_FR = "fr"
        const val LANG_ES = "es"
        const val LANG_VI = "vi"
    }
}
