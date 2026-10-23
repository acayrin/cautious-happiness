package com.overlaytranslator.translate

import android.util.Log
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * Wraps ML Kit's on-device translator (source language → English).
 *
 * The model (~30 MB, e.g. zh→en ships as en_zh.zip) is downloaded on demand by
 * Google Play Services; callers must [ensureModelDownloaded] before the first
 * [translate].
 */
class Translator(sourceLanguage: String = DEFAULT_SOURCE) : AutoCloseable {

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
    suspend fun translate(text: String): String? {
        if (text.isBlank()) return text
        val i = nextClient.getAndIncrement().mod(pool.size)
        return locks[i].withPermit {
            suspendCancellableCoroutine { cont ->
                pool[i].translate(text)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener {
                        Log.w(TAG, "Translation failed", it)
                        cont.resume(null)
                    }
            }
        }
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
            else -> TranslateLanguage.CHINESE
        }
    }
}
