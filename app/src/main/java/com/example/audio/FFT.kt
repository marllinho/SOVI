package com.example.audio

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object FFT {
    /**
     * Computes the 1D Radix-2 FFT in-place.
     * @param real Real part array, must be power of 2 size
     * @param imag Imaginary part array, must be same size as real
     */
    fun compute(real: FloatArray, imag: FloatArray) {
        val n = real.size
        if (n and (n - 1) != 0) {
            throw IllegalArgumentException("FFT size must be a power of 2 (received $n).")
        }

        // Bit-reversal swap sorting
        var j = 0
        for (i in 0 until n) {
            if (i < j) {
                val tempR = real[i]
                real[i] = real[j]
                real[j] = tempR

                val tempI = imag[i]
                imag[i] = imag[j]
                imag[j] = tempI
            }
            var m = n shr 1
            while (m >= 1 && j >= m) {
                j -= m
                m = m shr 1
            }
            j += m
        }

        // Cooley-Tukey decimation-in-time radix-2 loop
        var len = 2
        while (len <= n) {
            val angle = -2.0 * Math.PI / len
            val wlenR = cos(angle).toFloat()
            val wlenI = sin(angle).toFloat()

            val halfLen = len shr 1
            for (i in 0 until n step len) {
                var wR = 1.0f
                var wI = 0.0f
                for (k in 0 until halfLen) {
                    val uR = real[i + k]
                    val uI = imag[i + k]

                    val trVal = real[i + k + halfLen]
                    val tiVal = imag[i + k + halfLen]

                    val tR = trVal * wR - tiVal * wI
                    val tI = trVal * wI + tiVal * wR

                    real[i + k] = uR + tR
                    imag[i + k] = uI + tI

                    real[i + k + halfLen] = uR - tR
                    imag[i + k + halfLen] = uI - tI

                    val nextWR = wR * wlenR - wI * wlenI
                    val nextWI = wI * wlenR + wR * wlenI
                    wR = nextWR
                    wI = nextWI
                }
            }
            len = len shl 1
        }
    }

    /**
     * Computes magnitudes of the complex spectrum.
     */
    fun computeMagnitudes(real: FloatArray, imag: FloatArray, magnitudes: FloatArray) {
        val half = magnitudes.size.coerceAtMost(real.size / 2)
        for (i in 0 until half) {
            magnitudes[i] = sqrt(real[i] * real[i] + imag[i] * imag[i])
        }
    }
}
