package com.overlaytranslator.translate

import android.util.Log
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/** Anything that can translate texts to English; null marks an individual failure. */
interface TextTranslator : AutoCloseable {
    suspend fun translateAll(texts: List<String>): List<String?>
}

/**
 * Routes translations by backend preference: "opusmt" (default) uses the
 * bundled offline OPUS-MT engine and falls back to the full chain when its
 * model is not bundled for the language; "auto" runs offline → Google2 →
 * on-device; "google2" uses only the remote backend; "local" only ML Kit.
 */
class TranslationChain(
    private val sourceLanguage: String,
    private val local: TextTranslator,
    private val backend: String = "opusmt",
    private val offline: TextTranslator? = null,
) : TextTranslator {

    override suspend fun translateAll(texts: List<String>): List<String?> {
        if (texts.isEmpty()) return emptyList()
        if (backend == "local") return local.translateAll(texts)
        if (backend == "google2") {
            // Fixed remote backend: no fallback — failures fall back to showing
            // the source text at the display layer.
            return RemoteTranslator.translateAll(texts, sourceLanguage, "google2")
                ?: texts.map { null }
        }
        // Offline OPUS-MT first (opusmt and auto), then remote, then ML Kit.
        val off = offline?.translateAll(texts)
        if (off != null && off.size == texts.size && off.all { it != null }) return off

        val remote = RemoteTranslator.translateAll(texts, sourceLanguage, "auto")
        if (remote != null && remote.size == texts.size) return remote
        return local.translateAll(texts)
    }

    override fun close() {
        offline?.close()
        local.close()
    }
}

/**
 * Wraps ML Kit's on-device translator (source language → English).
 *
 * The model (~30 MB, e.g. zh→en ships as en_zh.zip) is downloaded on demand by
 * Google Play Services; callers must [ensureModelDownloaded] before the first
 * [translate].
 */
class Translator(sourceLanguage: String = DEFAULT_SOURCE) : TextTranslator {

    private val options = TranslatorOptions.Builder()
        .setSourceLanguage(mlKitLanguage(sourceLanguage))
        .setTargetLanguage(TranslateLanguage.ENGLISH)
        .build()

    // ML Kit queues translate() calls FIFO per client instance, so one client
    // serialises everything. Several clients translate in parallel; they share
    // the same on-disk model.
    private val pool = Array(POOL_SIZE) { Translation.getClient(options) }
    private val locks = Array(POOL_SIZE) { Semaphore(1) }
    private val nextClient = AtomicInteger()

    /**
     * Returns true when the model is available locally after the call.
     * Returns false when download failed (offline, no storage, etc).
     */
    suspend fun ensureModelDownloaded(): Boolean = suspendCancellableCoroutine { cont ->
        pool[0].downloadModelIfNeeded()
            .addOnSuccessListener { cont.resume(true) }
            .addOnFailureListener { cont.resume(false) }
    }

    /**
     * Translates Chinese to English. Returns null on failure (typically the
     * model is not downloaded) so callers can avoid caching bogus results.
     */
    override suspend fun translateAll(texts: List<String>): List<String?> = coroutineScope {
        // Parallel across the client pool — the ML Kit Task API is per-string.
        texts.map { async { translate(it) } }.awaitAll()
    }

    private suspend fun translate(text: String): String? {
        if (text.isBlank()) return text
        // The tiny on-device model mangles long inputs (repetition, invented
        // words); translate sentence-sized pieces and join the results.
        val results = splitSentences(text).map { translatePart(it) }
        return if (results.any { it == null }) null else results.filterNotNull().joinToString(" ")
    }

    private suspend fun translatePart(text: String): String? {
        val i = nextClient.getAndIncrement().mod(pool.size)
        return locks[i].withPermit {
            suspendCancellableCoroutine { cont ->
                pool[i].translate(text)
                    .addOnSuccessListener {
                    Log.d(TAG, "\"$text\" -> \"$it\"")
                    cont.resume(it)
                    }
                    .addOnFailureListener {
                    Log.w(TAG, "Translation failed", it)
                    cont.resume(null)
                    }
            }
        }
    }

    /** Splits on sentence terminators and line breaks; never returns empty. */
    private fun splitSentences(text: String): List<String> {
        val parts = text.split(Regex("(?<=[。！？])|\\n+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return parts.ifEmpty { listOf(text) }
    }

    override fun close() {
        pool.forEach { it.close() }
    }

    companion object {
        private const val TAG = "Translator"
        private const val POOL_SIZE = 8
        private const val DEFAULT_SOURCE = "zh"

        fun mlKitLanguage(code: String): String = when (code) {
            "ja" -> TranslateLanguage.JAPANESE
            "ko" -> TranslateLanguage.KOREAN
            "fr" -> TranslateLanguage.FRENCH
            "es" -> TranslateLanguage.SPANISH
            "vi" -> TranslateLanguage.VIETNAMESE
            "de" -> TranslateLanguage.GERMAN
            "it" -> TranslateLanguage.ITALIAN
            "pt" -> TranslateLanguage.PORTUGUESE
            "nl" -> TranslateLanguage.DUTCH
            "pl" -> TranslateLanguage.POLISH
            "sv" -> TranslateLanguage.SWEDISH
            "tr" -> TranslateLanguage.TURKISH
            "id" -> TranslateLanguage.INDONESIAN
            "hi" -> TranslateLanguage.HINDI
            "cs" -> TranslateLanguage.CZECH
            else -> TranslateLanguage.CHINESE
        }
    }
}
