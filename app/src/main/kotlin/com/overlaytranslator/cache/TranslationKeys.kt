package com.overlaytranslator.cache

import android.graphics.Rect
import java.security.MessageDigest

/**
 * Builds cache keys. Keying strategy is part of the public contract of the cache —
 * change these functions and every cached row becomes unreachable.
 */
object TranslationKeys {

    /** Width used to normalise bounding boxes across device resolutions. */
    private const val BASE_WIDTH = 1080

    /** Stable key for a bounding box, normalised so 1080p and 1440p map to the same row. */
    fun bboxKey(box: Rect, screenWidth: Int): String {
        return bboxKey(box.left, box.top, box.right, box.bottom, screenWidth)
    }

    /** Primitive overload so tests run on the JVM without Android stubs. */
    fun bboxKey(left: Int, top: Int, right: Int, bottom: Int, screenWidth: Int): String {
        if (screenWidth <= 0) return "$left,$top,$right,$bottom"
        val scale = BASE_WIDTH.toDouble() / screenWidth
        val l = Math.round(left * scale)
        val t = Math.round(top * scale)
        val r = Math.round(right * scale)
        val b = Math.round(bottom * scale)
        return "$l,$t,$r,$b"
    }

    /**
     * sha256 hex of the source text (post-glossary, pre-translation).
     * With [normalize] the text is folded first so OCR variants (punctuation,
     * whitespace/newlines, full-/half-width forms) share one cache entry.
     */
    fun sourceHash(text: String, normalize: Boolean = false): String {
        val input = if (normalize) normalizeForCache(text) else text
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            for (byte in digest) {
                append(HEX[(byte.toInt() shr 4) and 0xF])
                append(HEX[byte.toInt() and 0xF])
            }
        }
    }

    /**
     * Folds OCR variants for translation-memory lookups: NFKC (full-width to
     * half-width), drops punctuation/symbols (♪, ■, ellipsis, brackets),
     * collapses whitespace, lowercases Latin. CJK letters are kept.
     */
    fun normalizeForCache(text: String): String {
        val folded = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)
            .lowercase()
        val sb = StringBuilder(folded.length)
        for (ch in folded) {
            // Whitespace is dropped entirely: CJK has no meaningful spaces, and
            // for Latin it folds spacing variants together harmlessly.
            if (ch.isLetterOrDigit()) sb.append(ch)
        }
        return sb.toString()
    }

    /** Hex representation of the frame's perceptual hash. */
    fun screenHash(hash: Long): String = java.lang.Long.toHexString(hash)

    private const val HEX = "0123456789abcdef"
}
