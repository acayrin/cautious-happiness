package com.overlaytranslator.translate

import android.text.Html
import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray

/**
 * Keyless web translation backend: Google2 (Google's translate-pa web API, as
 * used by kiss-translator). Requests are paced to one per [MIN_INTERVAL_MS] —
 * the keyless endpoints rate-limit bursts — and the backend is skipped for
 * [COOLDOWN_MS] after it fails. Failure falls through to the on-device model.
 * (Bing and DeepL removed: unreliable / rate-limited.)
 */
object RemoteTranslator {

    private const val TAG = "RemoteTranslator"
    private const val TIMEOUT_MS = 3_000L
    // Requests are serialized anyway; pacing keeps bursts from tripping the
    // keyless endpoint's rate limit while staying responsive. Set from config
    // at capture start.
    @Volatile
    var minIntervalMs: Long = 250L
    private const val COOLDOWN_MS = 30_000L

    /** Google's public web-client key, shipped in Google's own web clients. */
    private const val GOOGLE2_KEY = "AIzaSyATBXajvzQLTDHEQbcpq0Ihe0vWDHmO520"

    const val BACKEND_AUTO = "auto"
    const val BACKEND_GOOGLE2 = "google2"

    private val gate = Mutex()
    private val cooldownUntil = ConcurrentHashMap<String, Long>()
    private var nextAllowedAt = 0L

    suspend fun translateAll(
        texts: List<String>,
        sourceLang: String,
        backend: String = BACKEND_AUTO,
    ): List<String>? = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext emptyList()
        gate.withLock {
            val wait = nextAllowedAt - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            val result = attempt("google2") { google2(texts, sourceLang) }
            nextAllowedAt = System.currentTimeMillis() + minIntervalMs
            result
        }
    }

    private suspend fun <T> attempt(name: String, block: () -> T?): T? {
        val now = System.currentTimeMillis()
        if ((cooldownUntil[name] ?: 0L) > now) return null
        val result = runCatching { withTimeout(TIMEOUT_MS) { block() } }
        result.onFailure {
            cooldownUntil[name] = now + COOLDOWN_MS
            Log.d(TAG, "$name failed: $it")
        }
        val out = result.getOrNull()
        if (out != null) Log.d(TAG, "[$name] -> \"$out\"")
        return out
    }

    /**
     * Google's translate-pa API ("Google2" in kiss-translator). Request body is
     * JSON+protobuf shaped: [[[texts], from, to], "wt_lib"]; the response's
     * res[0] holds the translations, one per input. Batches in one request.
     */
    private fun google2(texts: List<String>, sourceLang: String): List<String>? {
        val inputs = JSONArray().also { arr ->
            texts.forEach {
                arr.put(it.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
            }
        }
        val payload = JSONArray().apply {
            put(
                JSONArray().apply {
                    put(inputs)
                    put(googleLang(sourceLang))
                    put("en")
                }
            )
            put("wt_lib")
        }.toString()
        val body = http(
            "https://translate-pa.googleapis.com/v1/translateHtml",
            method = "POST",
            body = payload,
            headers = mapOf(
                "Content-Type" to "application/json+protobuf",
                "X-Goog-API-Key" to GOOGLE2_KEY,
            ),
        )
        val out = JSONArray(body).getJSONArray(0)
        return texts.indices.map { i ->
            val translated = out.optString(i)
            if (translated.isBlank()) return null
            Html.fromHtml(translated, Html.FROM_HTML_MODE_LEGACY).toString().trim().ifBlank { return null }
        }
    }

    private fun http(
        url: String,
        method: String = "GET",
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 3_000
        conn.readTimeout = 3_000
        conn.requestMethod = method
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        if (body != null) {
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = conn.responseCode
        if (code !in 200..299) throw IOException("HTTP $code")
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    internal fun googleLang(code: String): String = if (code == "zh") "zh-CN" else code
}
