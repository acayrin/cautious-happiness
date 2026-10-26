package com.overlaytranslator.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.SystemClock
import android.util.Log
import com.overlaytranslator.cache.TranslationCache
import com.overlaytranslator.cache.TranslationKeys
import com.overlaytranslator.translate.TextTranslator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Captures the audio of the captured app (AudioPlaybackCapture), transcribes it
 * on-device with whisper.cpp and reports the English translation as a subtitle.
 *
 * 4 s tumbling windows; silent (or music-only) windows are dropped before they
 * reach the recogniser. When translation fails the transcript itself is shown.
 */
class AudioSubtitleEngine(
    private val context: Context,
    private val translator: TextTranslator,
    private val cache: TranslationCache,
    private val normalizeCache: Boolean,
    private val clearAfterSeconds: Int,
    private val scope: CoroutineScope,
    private val onSubtitle: (String) -> Unit,
    private val onCleared: () -> Unit,
) {
    private val appContext = context.applicationContext

    @Volatile
    private var running = false
    private var captureThread: Thread? = null
    private var whisper: WhisperTranscriber? = null

    private var windows = Channel<FloatArray>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    @Volatile
    private var lastEmitMillis = 0L

    @Volatile
    private var cleared = true

    /** Downloads the model if needed, then starts capture + transcription. */
    fun start(projection: MediaProjection, language: String, quality: String, baseUrl: String) {
        stop()
        windows = Channel(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        running = true
        scope.launch {
            if (!WhisperTranscriber.ensureModel(appContext, quality, baseUrl)) {
                Log.w(TAG, "whisper model unavailable — subtitles disabled")
                running = false
                return@launch
            }
            whisper = WhisperTranscriber(appContext, language, quality)
            launch(Dispatchers.Default) { transcribeLoop() }
            captureThread = Thread({ captureLoop(projection) }, "audio-capture").also { it.start() }
        }
    }

    fun stop() {
        running = false
        runCatching { windows.close() }
        runCatching { captureThread?.join(1000) }
        captureThread = null
        whisper?.close()
        whisper = null
    }

    // ------------------------------------------------------------ capture

    private fun captureLoop(projection: MediaProjection) {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        var record: AudioRecord? = null
        var rate = 16000
        for (r in intArrayOf(16000, 48000)) {
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(r)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            val rec = runCatching {
                AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(config)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(r / 10 * 2 * 4)
                    .build()
            }.getOrNull()
            if (rec != null && rec.state == AudioRecord.STATE_INITIALIZED) {
                record = rec
                rate = r
                break
            }
            runCatching { rec?.release() }
        }
        if (record == null) {
            Log.w(TAG, "AudioRecord unavailable — no audio capture on this device/app")
            running = false
            return
        }
        Log.i(TAG, "Audio capture started at $rate Hz")

        val chunker = SubtitleChunker()
        val buf = ShortArray(rate / 10)
        runCatching { record.startRecording() }
        while (running) {
            val n = record.read(buf, 0, buf.size)
            if (n <= 0) continue
            val samples = if (rate == 16000) buf.copyOf(n) else decimate3(buf, n)
            val window = chunker.add(samples) ?: continue
            windows.trySend(window)
            expireIfNeeded()
        }
        runCatching { record.stop() }
        record.release()
        Log.i(TAG, "Audio capture stopped")
    }

    /** 48 kHz -> 16 kHz by averaging groups of 3 (whisper wants 16 kHz mono). */
    private fun decimate3(src: ShortArray, n: Int): ShortArray {
        val out = ShortArray(n / 3)
        for (i in out.indices) {
            val j = i * 3
            out[i] = ((src[j].toInt() + src[j + 1] + src[j + 2]) / 3).toShort()
        }
        return out
    }

    // -------------------------------------------------------- transcribe

    private suspend fun transcribeLoop() {
        for (window in windows) {
            if (!running) break
            val w = whisper ?: break
            val text = runCatching { w.transcribe(window) }.getOrNull() ?: continue
            if (text.isBlank()) continue
            val key = TranslationKeys.sourceHash(text, normalizeCache)
            val translated = cache.get(key)
                ?: runCatching { translator.translateAll(listOf(text))[0] }.getOrNull()
                    ?.also { cache.put(key, text, it) }
                ?: text
            lastEmitMillis = SystemClock.elapsedRealtime()
            cleared = false
            onSubtitle(translated)
        }
    }

    private fun expireIfNeeded() {
        if (cleared) return
        if (SystemClock.elapsedRealtime() - lastEmitMillis > clearAfterSeconds * 1000L) {
            cleared = true
            onCleared()
        }
    }

    companion object {
        private const val TAG = "AudioSubtitle"
    }
}
