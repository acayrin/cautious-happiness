package com.overlaytranslator.ocr

import android.graphics.Rect

/**
 * A block of text detected by OCR, together with where it sits on the screen.
 */
data class OcrBlock(
    val bbox: Rect,
    val sourceText: String,
    val confidence: Float,
)

/**
 * A block ready to be painted over the original text.
 */
data class RenderedBlock(
    val bbox: Rect,
    val translatedText: String,
    val backgroundColor: Int,
)
