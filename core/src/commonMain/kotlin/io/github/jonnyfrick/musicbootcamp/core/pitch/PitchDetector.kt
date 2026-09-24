package io.github.jonnyfrick.musicbootcamp.core.pitch

import kotlin.math.ln
import kotlin.math.roundToInt

/** A frequency estimate for one analysis window. */
data class PitchEstimate(
    val frequencyHz: Double,
    /** Height of the chosen NSDF peak, 0..1; how periodic the window is. */
    val clarity: Double,
)

/**
 * Single-pitch detection with the McLeod Pitch Method (P. McLeod, G. Wyvill:
 * "A Smarter Way to Find Pitch", ICMC 2005).
 *
 * The normalised square difference function (NSDF) is computed via FFT
 * autocorrelation; the pitch is the first "key maximum" that reaches
 * [peakThreshold] × the highest one. Taking the first sufficiently high peak rather
 * than the highest avoids octave-down errors; the threshold keeps piano tones with
 * strong upper partials from being read an octave too high.
 */
class PitchDetector(
    val sampleRate: Int,
    val windowSize: Int = defaultWindowSize(sampleRate),
    private val minFrequencyHz: Double = 50.0,
    private val maxFrequencyHz: Double = 4500.0,
    private val peakThreshold: Double = 0.9,
) {
    private val paddedSize = nextPowerOfTwo(2 * windowSize)
    private val re = DoubleArray(paddedSize)
    private val im = DoubleArray(paddedSize)
    private val nsdf = DoubleArray(windowSize)

    private val minLag = (sampleRate / maxFrequencyHz).toInt().coerceAtLeast(2)
    private val maxLag = (sampleRate / minFrequencyHz).toInt().coerceAtMost(windowSize - 2)

    private val backgroundPower = DoubleArray(paddedSize)

    /**
     * Estimates the pitch of the last [windowSize] samples of [samples]; null if not periodic.
     *
     * [background] (a window recorded just before the note was struck) is removed from the
     * power spectrum first, so a previous note that is still ringing does not blend with the
     * new one into a common lower pitch (e.g. G + C read as a low C).
     */
    fun detect(samples: FloatArray, background: FloatArray? = null): PitchEstimate? {
        require(samples.size >= windowSize) { "Need $windowSize samples" }
        val offset = samples.size - windowSize

        if (background != null) {
            powerSpectrum(background, background.size - windowSize)
            re.copyInto(backgroundPower)
        }

        // Autocorrelation r(τ) = inverse FFT of the power spectrum (zero-padded, no wrap-around).
        powerSpectrum(samples, offset)
        val rawEnergy = re.sum()
        if (background != null) {
            for (i in 0 until paddedSize) re[i] = (re[i] - BACKGROUND_WEIGHT * backgroundPower[i]).coerceAtLeast(0.0)
        }
        val keptEnergy = re.sum()
        if (keptEnergy <= 0.0) return null
        fft(re, im, inverse = true)

        // m(τ) = Σ x_j² + x_{j+τ}², updated incrementally; NSDF n(τ) = 2 r(τ) / m(τ).
        // With a background removed, m is scaled by the share of energy that was kept.
        val energyShare = keptEnergy / rawEnergy
        var m = 2 * re[0] / energyShare
        if (m <= 0.0) return null
        nsdf[0] = 1.0
        for (tau in 1..maxLag) {
            val left = samples[offset + tau - 1].toDouble()
            val right = samples[offset + windowSize - tau].toDouble()
            m -= left * left + right * right
            nsdf[tau] = if (m > 0) 2 * re[tau] / (m * energyShare) else 0.0
        }

        // Key maxima: the highest point between a positive-going and the next negative-going zero crossing.
        val peaks = mutableListOf<Int>()
        var tau = 1
        while (tau < maxLag && nsdf[tau] > 0) tau++ // skip the lobe around τ = 0
        while (tau < maxLag) {
            while (tau < maxLag && nsdf[tau] <= 0) tau++
            var best = -1
            while (tau < maxLag && nsdf[tau] > 0) {
                if (tau >= minLag && (best < 0 || nsdf[tau] > nsdf[best])) best = tau
                tau++
            }
            if (best > 0) peaks += best
        }
        if (peaks.isEmpty()) return null

        val highest = peaks.maxOf { nsdf[it] }
        val chosen = peaks.first { nsdf[it] >= peakThreshold * highest }

        // Parabolic interpolation around the chosen lag.
        val (lag, height) = interpolate(chosen)
        return PitchEstimate(sampleRate / lag, height.coerceAtMost(1.0))
    }

    /** Leaves the power spectrum of `samples[offset, offset + windowSize)` in [re] (and zeros in [im]). */
    private fun powerSpectrum(samples: FloatArray, offset: Int) {
        re.fill(0.0)
        im.fill(0.0)
        for (i in 0 until windowSize) re[i] = samples[offset + i].toDouble()
        fft(re, im)
        for (i in 0 until paddedSize) {
            re[i] = re[i] * re[i] + im[i] * im[i]
            im[i] = 0.0
        }
    }

    private fun interpolate(tau: Int): Pair<Double, Double> {
        if (tau <= 0 || tau >= maxLag) return tau.toDouble() to nsdf[tau]
        val a = nsdf[tau - 1]
        val b = nsdf[tau]
        val c = nsdf[tau + 1]
        val denominator = a - 2 * b + c
        if (denominator == 0.0) return tau.toDouble() to b
        val shift = 0.5 * (a - c) / denominator
        return (tau + shift) to (b - 0.25 * (a - c) * shift)
    }

    companion object {
        /** The ringing note keeps decaying, so removing it once is enough; a margin covers leakage. */
        private const val BACKGROUND_WEIGHT = 1.5

        /** ~46 ms: at least three periods down to about C2 (65 Hz). */
        fun defaultWindowSize(sampleRate: Int): Int = nextPowerOfTwo((sampleRate * 0.046).roundToInt())
    }
}

/** MIDI note number (fractional) of [frequencyHz], relative to concert pitch [referenceAHz]. */
fun frequencyToMidi(frequencyHz: Double, referenceAHz: Double): Double =
    69 + 12 * ln(frequencyHz / referenceAHz) / ln(2.0)
