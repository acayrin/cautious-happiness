package com.overlaytranslator.translate

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import com.overlaytranslator.cache.TranslationCache
import com.overlaytranslator.cache.TranslationKeys
import com.overlaytranslator.ocr.Ocr
import com.overlaytranslator.ocr.OcrEngine
import com.overlaytranslator.ocr.RenderedBlock
import com.overlaytranslator.util.ColorSampler

/**
 * Orchestrates one captured frame through OCR → glossary → cache → batched
 * translation.
 */
class TranslatePipeline(
    private val ocr: OcrEngine,
    private val translator: TextTranslator,
    private val glossary: Glossary,
    private val cache: TranslationCache,
    private val minConfidence: Float = Ocr.MIN_CONFIDENCE,
    /** CJK source languages skip blocks with no Han/Kana/Hangul (English UI noise). */
    private val requireCjk: Boolean = false,
    /** Frames a still-growing (typewriter) block is held back (0 = immediate). */
    private val maxDefer: Int = 0,
    /** True = emit only the complete frame (no partial/cache-first updates). */
    private val synchronousDisplay: Boolean = false,
    /** Seconds a vanished block stays on screen before being cleared. */
    clearAfterSeconds: Int = 2,
    /** True = cache keys use normalized text (OCR-variant tolerant). */
    private val normalizeCache: Boolean = false,
) : AutoCloseable {

    private val clearAfterMs: Long = clearAfterSeconds.coerceIn(1, 5) * 1000L

    override fun close() {
        ocr.close()
        translator.close()
    }

    /** One frame's result plus where the time went. */
    data class FrameResult(
        val blocks: List<RenderedBlock>,
        val ocrMs: Long,
        val translateMs: Long,
        val avgConfidence: Float = 0f,
    )

    /**
     * Runs the full pipeline on [bitmap]: OCR → glossary → cache → batched
     * translation. [onUpdate] fires as soon as cache hits are known (phase 1)
     * and again with the complete result (phase 2) — cached translations never
     * wait for the network. Blocks whose text is still growing (typewriter
     * reveal) are held back until they stabilize, so partial text never costs a
     * translation request.
     */
    suspend fun process(
        bitmap: Bitmap,
        onUpdate: (FrameResult) -> Unit = {},
    ): FrameResult {
        val ocrStart = SystemClock.uptimeMillis()
        val blocks = ocr.process(bitmap).filter {
            Ocr.isWorthTranslating(it, minConfidence) &&
                (!requireCjk || Ocr.containsCjk(it.sourceText))
        }
        val ocrMs = SystemClock.uptimeMillis() - ocrStart
        val avgConfidence = if (blocks.isEmpty()) 0f else blocks.map { it.confidence }.average().toFloat()
        if (blocks.isEmpty()) {
            // Nothing on screen: keep recent translations briefly, then clear all.
            val now = SystemClock.uptimeMillis()
            val lingering = if (synchronousDisplay) emptyList() else {
                seen.filter { now - it.lastSeenMs < clearAfterMs && it.display.isNotBlank() }
                    .map { RenderedBlock(it.box, it.display, ColorSampler.sampleAround(bitmap, it.box)) }
            }
            return FrameResult(lingering, ocrMs, 0, avgConfidence).also(onUpdate)
        }

        val sources = blocks.map { glossary.apply(it.sourceText) }
        val sourceHashes = sources.map { TranslationKeys.sourceHash(it, normalizeCache) }
        val cached = sourceHashes.map { cache.get(it) }

        // Stability gate: a block whose text changed since the previous frame
        // (typewriter reveal) is held back until it stops growing, so partial
        // text never costs a request. Escape hatch: translate anyway after
        // MAX_DEFER growths (churning text like counters must not deadlock).
        val prevIdx = blocks.indices.map { i -> seen.indexOfFirst { overlaps(it.box, blocks[i].bbox) } }
        val prevs = blocks.indices.map { i -> prevIdx[i].takeIf { it >= 0 }?.let { seen[it] } }
        val stable = blocks.indices.map { i ->
            val p = prevs[i]
            p == null || p.text == blocks[i].sourceText || p.deferCount >= maxDefer
        }

        val missing = blocks.indices.filter { cached[it] == null && stable[it] }

        // Phase 1: cache hits are displayed immediately, without waiting for
        // the network translation of the remaining blocks.
        if (!synchronousDisplay && missing.isNotEmpty() && cached.any { it != null }) {
            onUpdate(
                FrameResult(
                    blocks.indices.filter { cached[it] != null }.map { i ->
                        RenderedBlock(blocks[i].bbox, cached[i]!!, ColorSampler.sampleAround(bitmap, blocks[i].bbox))
                    },
                    ocrMs,
                    0,
                )
            )
        }

        val trStart = SystemClock.uptimeMillis()
        val fresh: Map<Int, String> = if (missing.isEmpty()) emptyMap() else {
            val results = translator.translateAll(missing.map { sources[it] })
            buildMap {
                missing.forEachIndexed { j, i ->
                    results.getOrNull(j)?.let { put(i, it) }
                }
            }
        }
        val translateMs = SystemClock.uptimeMillis() - trStart

        // Null = translation not ready yet: the block is not displayed at all,
        // so the raw OCR text never flashes before the translation.
        // (Previous rendering is inherited only when the source text is identical.)
        val texts: List<String?> = blocks.indices.map { i ->
            cached[i] ?: fresh[i]
                ?: prevs[i]?.takeIf { it.text == blocks[i].sourceText }
                    ?.display?.takeIf { it.isNotBlank() }
        }
        val resultBlocks = blocks.indices.mapNotNull { i ->
            val text = texts[i] ?: return@mapNotNull null
            fresh[i]?.let {
                cache.put(
                    sourceHash = sourceHashes[i],
                    sourceText = sources[i],
                    translated = it,
                )
            }
            RenderedBlock(blocks[i].bbox, text, ColorSampler.sampleAround(bitmap, blocks[i].bbox))
        }

        // OCR flicker: a block the detector misses for a frame or two keeps its
        // last translation instead of blinking out and back in.
        val matched = prevIdx.filter { it >= 0 }.toSet()
        val unmatched = seen.filterIndexed { idx, _ -> idx !in matched }
        val now = SystemClock.uptimeMillis()
        val ghosts = if (synchronousDisplay) emptyList() else {
            unmatched
                .filter { now - it.lastSeenMs < clearAfterMs && it.display.isNotBlank() }
                .filter { g -> resultBlocks.none { overlaps(it.bbox, g.box) } }
                .map { RenderedBlock(it.box, it.display, ColorSampler.sampleAround(bitmap, it.box)) }
        }

        seen = blocks.indices.map { i ->
            Seen(
                box = blocks[i].bbox,
                text = blocks[i].sourceText,
                display = texts[i] ?: "",
                deferCount = if (stable[i]) 0 else (prevs[i]?.deferCount ?: 0) + 1,
                lastSeenMs = now,
            )
        } + unmatched.map { Seen(it.box, it.text, it.display, it.deferCount, it.misses + 1, it.lastSeenMs) }

        return FrameResult(resultBlocks + ghosts, ocrMs, translateMs, avgConfidence).also(onUpdate)
    }

    private class Seen(
        val box: Rect,
        val text: String,
        val display: String,
        val deferCount: Int,
        val misses: Int = 0,
        val lastSeenMs: Long = 0L,
    )

    private var seen: List<Seen> = emptyList()

    private fun overlaps(a: Rect, b: Rect): Boolean {
        val interW = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val interH = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        return interW > 0 && interH > 0
    }

    companion object {
        /** Cosmetic helper: drops blocks whose box is degenerate. */
        fun isValidBox(box: Rect): Boolean = box.width() > 0 && box.height() > 0

        /** Default for [maxDefer]: translate every change immediately
         *  (remote latency ~120 ms). */
        const val DEFAULT_MAX_DEFER = 0


    }
}
