package com.overlaytranslator.overlay

/**
 * Tunables for the overlay. Read from SharedPreferences so they survive restarts.
 */
data class OverlayConfig(
    /** Capture/translate cadence in milliseconds (100 .. 2000, 500 default). */
    val captureIntervalMs: Int = 500,
    /** Background opacity behind each translation, 0..255. */
    val backgroundAlpha: Int = 230,
    /** Re-run OCR + translation after this many unchanged frames. */
    val forceEvery: Int = 5,
    /** Percent of screen area that must change before a frame is re-translated. */
    val changePct: Float = 0.6f,
    /** Minimum ML Kit confidence for a text block to be translated. */
    val ocrConfidence: Float = 0.5f,
    /** Gap (in line heights) above which OCR lines are separate text blocks. */
    val multilineGap: Float = 0.7f,
    /** Minimum spacing between remote translation requests, ms (100..2000). */
    val requestPacingMs: Int = 250,
    /** Frames a still-growing (typewriter) block is held back (0 = translate immediately). */
    val typewriterDefer: Int = 0,
    /** True = show translations only when the whole frame is translated. */
    val synchronousDisplay: Boolean = false,
    /** Seconds a vanished translation lingers before being cleared (1..5). */
    val clearAfterSeconds: Int = 2,
    /** True = translation may extend beyond the OCR box (natural wrapping, no shrink). */
    val allowTextOverflow: Boolean = false,
    /** True = cache lookups use normalized text (ignores punctuation/spacing variants). */
    val normalizeCacheKeys: Boolean = false,
    /** Base URL for downloading OPUS-MT models (default: project GitHub raw). */
    val modelBaseUrl: String = DEFAULT_MODEL_URL,
    /** When true the overlay is temporarily invisible (used by the "hide" toggle). */
    val hidden: Boolean = false,
) {
    val captureIntervalMillis: Long
        get() = captureIntervalMs.toLong().coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)

    /** [changePct] of the 32x32 gate grid; 0 (0%) disables skipping entirely. */
    val changedCells: Int
        get() = (changePct * GRID_CELLS / 100f).toInt()

    companion object {
        const val MIN_INTERVAL_MS = 100L
        const val MAX_INTERVAL_MS = 2000L

        const val PREFS_NAME = "overlay_config"
        const val KEY_INTERVAL_MS = "capture_interval_ms"

        /** Legacy key, kept only to migrate old installs from fps to ms. */
        const val KEY_FPS = "fps"
        const val KEY_ALPHA = "background_alpha"
        const val KEY_LANG = "source_language"
        const val KEY_FORCE_EVERY = "force_every"
        const val KEY_CHANGE_PCT = "change_pct"
        const val KEY_OCR_CONF = "ocr_confidence"
        const val KEY_LINE_GAP = "multiline_gap"
        const val KEY_PACING_MS = "request_pacing_ms"
        const val KEY_TW_DEFER = "typewriter_defer"
        const val KEY_SYNC_DISPLAY = "sync_display"
        const val KEY_CLEAR_AFTER = "clear_after_seconds"
        const val KEY_TEXT_OVERFLOW = "text_overflow"
        const val KEY_NORMALIZE_CACHE = "normalize_cache"
        const val KEY_MODEL_URL = "model_base_url"

        const val DEFAULT_MODEL_URL =
            "https://raw.githubusercontent.com/acayrin/cautious-happiness/main/models"
        const val KEY_BACKEND = "translation_backend"

        const val BACKEND_AUTO = "auto"
        const val BACKEND_OPUSMT = "opusmt"
        const val BACKEND_GOOGLE2 = "google2"
        const val BACKEND_LOCAL = "local"

        private const val GRID_CELLS = 32 * 32

        const val LANG_ZH = "zh"
        const val LANG_JA = "ja"
        const val LANG_KO = "ko"
        const val LANG_FR = "fr"
        const val LANG_ES = "es"
        const val LANG_VI = "vi"
    }
}
