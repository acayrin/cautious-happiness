package com.overlaytranslator.audio

/**
 * Accumulates 16-bit mono PCM at [sampleRate] and emits whole windows as
 * float32 in [-1, 1] for the speech recogniser, but only when the window
 * actually contains speech (a tumbling RMS gate so silence/music-only gaps
 * never reach whisper).
 */
class SubtitleChunker(
    private val sampleRate: Int = 16000,
    private val windowSeconds: Int = 4,
    /** RMS (0..1) above which a 100 ms block counts as speech. */
    private val rmsThreshold: Float = 0.01f,
    /** Fraction of loud blocks a window needs to count as speech. */
    private val speechRatio: Float = 0.25f,
) {
    private val windowSize = sampleRate * windowSeconds
    private val blockSize = sampleRate / 10
    private val pending = ShortArray(windowSize)
    private var filled = 0

    /** Adds samples; returns one float32 window when ready and voiced, else null. */
    @Synchronized
    fun add(samples: ShortArray): FloatArray? {
        var out: FloatArray? = null
        var i = 0
        while (i < samples.size) {
            val n = minOf(windowSize - filled, samples.size - i)
            System.arraycopy(samples, i, pending, filled, n)
            filled += n
            i += n
            if (filled == windowSize) {
                out = emitIfVoiced() ?: out
                filled = 0
            }
        }
        return out
    }

    private fun emitIfVoiced(): FloatArray? {
        var loud = 0
        var blocks = 0
        var b = 0
        while (b < windowSize) {
            val end = minOf(b + blockSize, windowSize)
            var sum = 0.0
            for (j in b until end) {
                val v = pending[j] / 32768.0
                sum += v * v
            }
            if (Math.sqrt(sum / (end - b)) >= rmsThreshold) loud++
            blocks++
            b = end
        }
        if (loud.toFloat() / blocks < speechRatio) return null
        return FloatArray(windowSize) { pending[it] / 32768f }
    }
}
