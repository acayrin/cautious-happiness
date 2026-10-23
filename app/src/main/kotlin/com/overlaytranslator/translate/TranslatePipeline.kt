package com.overlaytranslator.translate

import android.graphics.Bitmap
import android.graphics.Rect
import com.overlaytranslator.cache.TranslationCache
import com.overlaytranslator.cache.TranslationKeys
import com.overlaytranslator.ocr.Ocr
import com.overlaytranslator.ocr.OcrBlock
import com.overlaytranslator.ocr.OcrEngine
import com.overlaytranslator.ocr.RenderedBlock
import com.overlaytranslator.util.ColorSampler
import com.overlaytranslator.util.FrameDiffGate
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Orchestrates one captured frame through OCR → glossary → cache → translation.
 *
 * Concurrency is capped at [MAX_CONCURRENT_TRANSLATIONS] so we don't hammer the
 * ML Kit runtime (it has its own thread pool and degrades badly under load).
 */
class TranslatePipeline(
    private val ocr: OcrEngine,
    private val translator: Translator,
    private val glossary: Glossary,
    private val cache: TranslationCache,
) : AutoCloseable {

    private val translateSemaphore = Semaphore(MAX_CONCURRENT_TRANSLATIONS)

    override fun close() {
        ocr.close()
        translator.close()
    }

    /**
     * Runs the full pipeline on [bitmap]. Returns blocks positioned at their
     * original bounding boxes with translated text, ready for the overlay.
     */
    suspend fun process(bitmap: Bitmap): List<RenderedBlock> = coroutineScope {
        val blocks = ocr.process(bitmap).filter { Ocr.isWorthTranslating(it) }
        if (blocks.isEmpty()) return@coroutineScope emptyList()

        val screenHash = TranslationKeys.screenHash(FrameDiffGate.perceptualHash(bitmap))
        val screenWidth = bitmap.width

        blocks.map { block ->
            val backgroundColor = ColorSampler.sampleAround(bitmap, block.bbox)
            async {
                processBlock(block, screenHash, screenWidth, backgroundColor)
            }
        }.awaitAll()
    }

    private suspend fun processBlock(
        block: OcrBlock,
        screenHash: String,
        screenWidth: Int,
        backgroundColor: Int,
    ): RenderedBlock {
        val source = glossary.apply(block.sourceText)
        val bboxKey = TranslationKeys.bboxKey(block.bbox, screenWidth)
        val sourceHash = TranslationKeys.sourceHash(source)

        val cached = cache.get(screenHash, bboxKey, sourceHash)
        if (cached != null) {
            return RenderedBlock(block.bbox, cached, backgroundColor)
        }

        val translated = translateSemaphore.withPermit {
            translator.translate(source)
        } ?: return RenderedBlock(block.bbox, source, backgroundColor)

        cache.put(
            screenHash = screenHash,
            bboxKey = bboxKey,
            sourceHash = sourceHash,
            sourceText = source,
            translated = translated,
        )
        return RenderedBlock(block.bbox, translated, backgroundColor)
    }

    companion object {
        const val MAX_CONCURRENT_TRANSLATIONS = 8

        /** Cosmetic helper: drops blocks whose box is degenerate. */
        fun isValidBox(box: Rect): Boolean = box.width() > 0 && box.height() > 0
    }
}
