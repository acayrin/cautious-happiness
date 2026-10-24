package com.overlaytranslator.translate

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** JNI bindings to the CTranslate2 + SentencePiece shim (libopusmt.so). */
object OpusMtNative {
    init {
        System.loadLibrary("opusmt")
    }

    external fun nativeCreate(modelDir: String, srcSpm: String, tgtSpm: String): Long
    external fun nativeTranslate(handle: Long, texts: Array<String>): Array<String?>?
    external fun nativeDestroy(handle: Long)
}

/**
 * Offline translator: OPUS-MT (Helsinki-NLP Marian) via CTranslate2, int8.
 *
 * Models are NOT bundled: [ensureModel] downloads `<baseUrl>/opus-mt-<lang>-en/…`
 * into app storage on first use (or extracts bundled assets if a build ships
 * them). [baseUrl] comes from the settings field (default: the project's
 * GitHub raw models folder).
 */
class OpusMtTranslator(
    context: Context,
    private val sourceLanguage: String,
    private val baseUrl: String,
) : TextTranslator {

    private val appContext = context.applicationContext
    private var handle = 0L

    private fun ensureHandle(): Long {
        if (handle != 0L) return handle
        val dir = modelDir(appContext, sourceLanguage).takeIf { isReady(appContext, sourceLanguage) }
            ?: return 0L
        handle = OpusMtNative.nativeCreate(
            dir.absolutePath,
            File(dir, "source.spm").absolutePath,
            File(dir, "target.spm").absolutePath,
        )
        Log.i(TAG, "OPUS-MT engine created for $sourceLanguage (handle=$handle)")
        return handle
    }

    override suspend fun translateAll(texts: List<String>): List<String?> =
        withContext(Dispatchers.Default) {
            if (texts.isEmpty()) return@withContext emptyList()
            var h = ensureHandle()
            if (h == 0L) {
                if (!ensureModel(appContext, sourceLanguage, baseUrl)) {
                    return@withContext texts.map { null }
                }
                h = ensureHandle()
                if (h == 0L) return@withContext texts.map { null }
            }
            val out = OpusMtNative.nativeTranslate(h, texts.toTypedArray())
            if (out == null) texts.map { null } else texts.indices.map { out.getOrNull(it) }
        }

    override fun close() {
        if (handle != 0L) {
            OpusMtNative.nativeDestroy(handle)
            handle = 0L
        }
    }

    companion object {
        private const val TAG = "OpusMt"

        private val FILES = listOf(
            "config.json", "model.bin", "shared_vocabulary.json", "source.spm", "target.spm",
        )

        fun modelDir(context: Context, lang: String): File =
            File(context.filesDir, "models/opus-mt-$lang-en")

        fun isReady(context: Context, lang: String): Boolean =
            File(modelDir(context, lang), "ready").exists()

        /**
         * Ensures the model for [lang] exists locally: extracts bundled assets
         * when present, otherwise downloads all files from [baseUrl]. Returns
         * true when the model is ready to use.
         */
        suspend fun ensureModel(context: Context, lang: String, baseUrl: String): Boolean =
            withContext(Dispatchers.IO) {
                val dest = modelDir(context, lang)
                if (File(dest, "ready").exists()) return@withContext true

                val assetDir = "models/opus-mt-$lang-en"
                val names = runCatching { context.assets.list(assetDir) }.getOrNull()
                if (!names.isNullOrEmpty()) {
                    dest.mkdirs()
                    for (name in names) {
                        context.assets.open("$assetDir/$name").use { input ->
                            File(dest, name).outputStream().use { input.copyTo(it) }
                        }
                    }
                    File(dest, "ready").writeText("ok")
                    Log.i(TAG, "Bundled model $lang-en extracted to $dest")
                    return@withContext true
                }

                val base = baseUrl.trimEnd('/')
                Log.i(TAG, "Downloading opus-mt-$lang-en from $base (one-time)…")
                return@withContext try {
                    dest.mkdirs()
                    for (name in FILES) {
                        val conn = URL("$base/opus-mt-$lang-en/$name").openConnection() as HttpURLConnection
                        conn.connectTimeout = 15_000
                        conn.readTimeout = 180_000
                        val code = conn.responseCode
                        if (code !in 200..299) throw java.io.IOException("$name: HTTP $code")
                        conn.inputStream.use { input ->
                            File(dest, name).outputStream().use { input.copyTo(it) }
                        }
                    }
                    File(dest, "ready").writeText("ok")
                    Log.i(TAG, "Model $lang-en ready in $dest")
                    true
                } catch (e: Exception) {
                    runCatching { dest.deleteRecursively() }
                    Log.w(TAG, "Model download failed for '$lang' from $base: $e")
                    false
                }
            }
    }
}
