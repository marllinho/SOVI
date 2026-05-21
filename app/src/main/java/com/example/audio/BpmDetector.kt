package com.example.audio

import kotlin.math.max
import kotlin.math.min

object BpmDetector {
    /**
     * Estimates the Beats Per Minute (BPM) of the provided PCM float audio channel.
     * Uses energy envelope sub-window extraction followed by onset difference amplification
     * and autocorrelation lag analysis in the common music range (60-180 BPM).
     */
    fun detectBpm(data: FloatArray, sampleRate: Int): Float {
        // We need at least 3 seconds of sound to get reliable tempo metrics
        if (data.size < sampleRate * 3) {
            return 120.0f
        }

        // Sub-sample check to keep calculations fast under larger records
        // Max analyze 60 seconds to avoid lag on long tracks
        val durationSamples = data.size
        val maxSamplesToAnalyze = sampleRate * 60
        val dataToAnalyze = if (durationSamples > maxSamplesToAnalyze) {
            data.sliceArray(0 until maxSamplesToAnalyze)
        } else {
            data
        }

        // Window size of 2048 samples (~46ms at 44.1kHz) and overlapping hop size of 1024
        val windowSize = 2048
        val hopSize = 1024
        val numWindows = (dataToAnalyze.size - windowSize) / hopSize
        if (numWindows < 100) {
            return 120.0f
        }

        // 1. Calculate local short-term energy envelope
        val envelope = FloatArray(numWindows)
        for (w in 0 until numWindows) {
            var energy = 0f
            val startIdx = w * hopSize
            for (i in 0 until windowSize) {
                val sample = dataToAnalyze[startIdx + i]
                energy += sample * sample
            }
            envelope[w] = energy
        }

        // 2. Compute transient onsets (positive first-order differences)
        val onsets = FloatArray(numWindows - 1)
        for (i in 0 until numWindows - 1) {
            val diff = envelope[i + 1] - envelope[i]
            onsets[i] = if (diff > 0f) diff else 0f
        }

        // 3. Autocorrelation of the onset detection signal
        // Typical tempo search bounds: 60 to 180 BPM
        val minBpm = 60
        val maxBpm = 180
        val oscRate = sampleRate.toFloat() / hopSize // rate of envelope samples
        val maxLag = ((60f * oscRate) / minBpm).toInt().coerceAtMost(onsets.size - 2)
        val minLag = ((60f * oscRate) / maxBpm).toInt().coerceAtLeast(1)

        if (minLag >= maxLag) {
            return 120.0f
        }

        val autocorrelation = FloatArray(maxLag + 1)
        var maxCorr = 0f
        var bestLag = -1

        for (lag in minLag..maxLag) {
            var corr = 0f
            var count = 0
            for (i in 0 until (onsets.size - lag)) {
                corr += onsets[i] * onsets[i + lag]
                count++
            }
            if (count > 0) {
                corr /= count
            }
            autocorrelation[lag] = corr
            if (corr > maxCorr) {
                maxCorr = corr
                bestLag = lag
            }
        }

        if (bestLag <= 0) {
            return 120.0f
        }

        // Calculate final estimated BPM
        val bpm = (60f * oscRate) / bestLag
        return if (bpm in 45f..240f) bpm else 120.0f
    }
}
