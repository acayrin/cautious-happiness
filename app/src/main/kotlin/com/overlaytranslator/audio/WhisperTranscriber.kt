package com.overlaytranslator.audio

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** JNI bindings to the whisper.cpp shim (libwhisperjni.so). */
object WhisperNative {
    init {
        System.loadLibrary("whisperjni")
    }

    external fun nativeCreate(modelPath: String, language: String): Long
    external fun nativeTranscribe(handle: Long, samples: FloatArray): String?
    external fun nativeDestroy(handle: Long)
}

/**
 * On-device speech-to-text via whisper.cpp, quantized ggml models.
 *
 * Models are NOT bundled: [ensureModel] downloads `<baseUrl>/whisper/<file>` into
 * app storage on first use. "fast" = ggml-tiny-q5_1 (~32 MB, default),
 * "accurate" = ggml-base-q5_1 (~57 MB).
 */
class WhisperTranscriber(
    context: Context,
    language: String,
    quality: String,
) {
    private var handle: Long = WhisperNative.nativeCreate(
        modelFile(File(context.applicationContext.filesDir, "whisper"), quality).absolutePath,
        language,
    ).also {
        if (it == 0L) Log.w(TAG, "whisper init failed for '$language' ($quality)")
    }

    fun transcribe(samples: FloatArray): String? {
        if (handle == 0L) return null
        return WhisperNative.nativeTranscribe(handle, samples)?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun close() {
        if (handle != 0L) {
            WhisperNative.nativeDestroy(handle)
            handle = 0L
        }
    }

    companion object {
        private const val TAG = "Whisper"

        fun modelFile(dir: File, quality: String): File =
            File(dir, if (quality == "accurate") "ggml-base-q5_1.bin" else "ggml-tiny-q5_1.bin")

        /**
         * Ensures the whisper model for [quality] exists locally. Returns true
         * when ready to use.
         */
        suspend fun ensureModel(context: Context, quality: String, baseUrl: String): Boolean =
            withContext(Dispatchers.IO) {
                val dest = File(context.filesDir, "whisper")
                val file = modelFile(dest, quality)
                if (File(dest, "${file.name}.ready").exists() && file.exists()) {
                    return@withContext true
                }
                val base = baseUrl.trimEnd('/')
                Log.i(TAG, "Downloading ${file.name} from $base (one-time)…")
                return@withContext try {
                    dest.mkdirs()
                    val conn = URL("$base/whisper/${file.name}").openConnection() as HttpURLConnection
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 300_000
                    val code = conn.responseCode
                    if (code !in 200..299) throw java.io.IOException("${file.name}: HTTP $code")
                    conn.inputStream.use { input ->
                        file.outputStream().use { input.copyTo(it) }
                    }
                    File(dest, "${file.name}.ready").writeText("ok")
                    Log.i(TAG, "whisper model ready: ${file.name}")
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "whisper model download failed from $base: $e")
                    false
                }
            }
    }
}
