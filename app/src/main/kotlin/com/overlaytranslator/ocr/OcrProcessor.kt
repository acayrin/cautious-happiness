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

class MlKitOcrProcessor(language: String = "zh") : OcrEngine {

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
        return result.textBlocks.mapNotNull { block ->
            val box = block.boundingBox ?: return@mapNotNull null
            val text = block.text.trim()
            if (text.isEmpty()) return@mapNotNull null
            val confidence = block.lines.map { it.confidence ?: 0f }.maxOrNull() ?: 0f
            OcrBlock(Rect(box), text, confidence)
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

    fun isWorthTranslating(block: OcrBlock): Boolean {
        return block.confidence >= MIN_CONFIDENCE && block.sourceText.length >= MIN_TEXT_LENGTH
    }
}
