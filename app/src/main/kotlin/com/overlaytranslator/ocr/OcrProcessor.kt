package com.overlaytranslator.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Wraps ML Kit's on-device Chinese text recogniser.
 *
 * Kept behind a small interface so the implementation can be swapped (e.g. to
 * PaddleOCR via ONNX Runtime) if ML Kit turns out to be weak on the target
 * game's fonts — see the plan's risk table.
 */
interface OcrEngine {
    suspend fun process(bitmap: Bitmap): List<OcrBlock>
    fun close()
}

class MlKitOcrProcessor(
    language: String = "zh",
    private val gapRatio: Float = Ocr.DEFAULT_GAP_RATIO,
) : OcrEngine {

    private val recognizer = TextRecognition.getClient(
        when (language) {
            "zh" -> ChineseTextRecognizerOptions.Builder().build()
            "ja" -> JapaneseTextRecognizerOptions.Builder().build()
            "ko" -> KoreanTextRecognizerOptions.Builder().build()
            else -> TextRecognizerOptions.DEFAULT_OPTIONS // Latin script: fr, es, vi, …
        }
    )

    override suspend fun process(bitmap: Bitmap): List<OcrBlock> {
        val image = InputImage.fromBitmap(bitmap, 0)
        val result = suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
        return result.textBlocks.flatMap { block ->
            val lines = block.lines.filter { it.boundingBox != null && it.text.isNotBlank() }
            if (lines.isEmpty()) return@flatMap emptyList<OcrBlock>()
            val tops = IntArray(lines.size) { lines[it].boundingBox!!.top }
            val bottoms = IntArray(lines.size) { lines[it].boundingBox!!.bottom }
            Ocr.groupLines(tops, bottoms, gapRatio).map { idx ->
                val box = Rect(lines[idx[0]].boundingBox!!)
                for (k in 1 until idx.size) box.union(lines[idx[k]].boundingBox!!)
                val text = idx.joinToString("\n") { lines[it].text.trim() }
                val confidence = idx.maxOf { i ->
                    lines[i].confidence ?: 1f
                }
                OcrBlock(box, text, confidence)
            }
        }
    }

    override fun close() {
        recognizer.close()
    }
}

object Ocr {
    /** Blocks worth translating: confident enough and long enough to not be noise. */
    const val MIN_CONFIDENCE = 0.5f
    const val MIN_TEXT_LENGTH = 2

    fun isWorthTranslating(block: OcrBlock, minConfidence: Float = MIN_CONFIDENCE): Boolean {
        return block.confidence >= minConfidence && block.sourceText.length >= MIN_TEXT_LENGTH
    }

    /**
     * True when the text contains at least one Han, Kana or Hangul character.
     * Used for CJK source languages to skip blocks that are already English /
     * numeric / symbols (UI labels, URLs, counts) and would only waste requests.
     */
    fun containsCjk(text: String): Boolean {
        for (ch in text) {
            val c = ch.code
            if (c in 0x4E00..0x9FFF || c in 0x3400..0x4DBF ||      // Han + ext A
                c in 0x3040..0x30FF ||                            // Hiragana + Katakana
                c in 0xAC00..0xD7AF || c in 0x1100..0x11FF ||     // Hangul syllables + Jamo
                c in 0x3130..0x318F                               // Hangul compat Jamo
            ) return true
        }
        return false
    }

    /** Default grouping threshold: gap > 0.7 x line height starts a new group. */
    const val DEFAULT_GAP_RATIO = 0.7f

    /**
     * Splits OCR lines into groups of vertically adjacent lines. A gap larger
     * than [gapRatio] line heights starts a new group — that separates UI
     * elements ML Kit merged into one block (e.g. a character-name plate above
     * the dialog box) while keeping wrapped dialog lines together.
     */
    fun groupLines(
        tops: IntArray,
        bottoms: IntArray,
        gapRatio: Float = DEFAULT_GAP_RATIO,
    ): List<List<Int>> {
        require(tops.size == bottoms.size) { "tops and bottoms must match" }
        if (tops.isEmpty()) return emptyList()
        val order = tops.indices.sortedBy { tops[it] }
        val groups = mutableListOf<List<Int>>()
        var current = mutableListOf(order.first())
        for (k in 1 until order.size) {
            val i = order[k]
            val last = current.last()
            val gap = tops[i] - bottoms[last]
            val height = (bottoms[last] - tops[last]).coerceAtLeast(1)
            if (gap > height * gapRatio) {
                groups.add(current)
                current = mutableListOf(i)
            } else {
                current.add(i)
            }
        }
        groups.add(current)
        return groups
    }
}
