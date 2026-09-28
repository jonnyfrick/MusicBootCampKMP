package io.github.jonnyfrick.musicbootcamp.core.pitch

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Predicts how much of the microphone signal is the app's own sound, from the audio the app
 * played (the reference), so the player's notes can be recognised while the app plays.
 *
 * Works on power spectra, not on waveforms: phase, small timing errors and loudspeaker
 * distortion hardly matter, and pitch detection only needs the power spectrum anyway.
 * - Delay: the onsets in the reference and in the microphone are correlated over the last
 *   seconds; the best lag within [MAX_DELAY_HOPS] wins. Tolerates ±1 hop.
 * - Gain per frequency band (loudspeaker, room, microphone): the median of the ratio
 *   microphone / reference, learned only while the player is not playing. A stroke shows as a
 *   level jump in the microphone without one in the reference; learning pauses after it, so a
 *   missed stroke cannot raise the gain and hide the next one. Far outliers are ignored.
 * - Room reverberation: the prediction decays by [REVERB_DECAY] per hop at the slowest.
 *
 * The prediction is scaled by [OVER_SUBTRACTION] as a safety margin. Until the delay is known
 * and the gains have settled ([ready]), callers should ignore input while the reference is
 * active ([referenceActive]), like without echo cancellation.
 */
internal class EchoEstimator(
    sampleRate: Int,
    private val windowSize: Int,
    private val paddedSize: Int,
    private val hop: Int,
) {
    private val bandCount = bandOf(sampleRate / 2.0) + 1
    private val paddedBands = IntArray(paddedSize) { bandOf(min(it, paddedSize - it) * sampleRate.toDouble() / paddedSize) }
    private val hopBands = IntArray(hop) { bandOf(min(it, hop - it) * sampleRate.toDouble() / hop) }

    private val reference = FloatArray(windowSize + (MAX_DELAY_HOPS + 2) * hop)
    private val microphone = FloatArray(windowSize)

    private val historySize = HISTORY_SECONDS * sampleRate / hop
    private val micEnvelope = DoubleArray(historySize)
    private val refEnvelope = DoubleArray(historySize)
    private var hops = 0L
    private var hopsSinceReference = Int.MAX_VALUE / 2
    private var hopsSincePlayerStroke = Int.MAX_VALUE / 2

    /** Delay of the microphone behind the reference, in hops; null until it was found. */
    var delayHops: Int? = null
        private set
    private var delayCandidate = -1

    private val logGain = DoubleArray(bandCount)
    private val gainUpdates = IntArray(bandCount)
    private var logGainAll = 0.0
    private var gainUpdatesAll = 0

    /** The predicted power spectrum of the app's sound in the current analysis window. */
    val echoPower = DoubleArray(paddedSize)

    /** The predicted mean square of the app's sound in the latest hop. */
    var hopEchoEnergy = 0.0
        private set

    val ready: Boolean get() = delayHops != null && gainUpdatesAll >= MIN_GAIN_UPDATES

    /** Whether an onset of the app's sound reaches the microphone about now (where predictions are least exact). */
    var referenceOnsetNear = false
        private set

    /** Whether the app played something recently enough to be heard now. */
    val referenceActive: Boolean get() = hopsSinceReference <= MAX_DELAY_HOPS + RELEASE_HOPS

    private val re = DoubleArray(paddedSize)
    private val im = DoubleArray(paddedSize)
    private val micBands = DoubleArray(bandCount)
    private val refBands = DoubleArray(bandCount)
    private val refPower = DoubleArray(paddedSize)
    private val hopRe = DoubleArray(hop)
    private val hopIm = DoubleArray(hop)
    private val hopRefPower = DoubleArray(hop)

    /**
     * Feeds one hop of microphone and reference samples, recorded at the same time. While
     * [playerActive], the microphone holds more than the app's sound, so the gains are not learned.
     */
    fun update(micHop: FloatArray, refHop: FloatArray, playerActive: Boolean = false) {
        slide(microphone, micHop)
        slide(reference, refHop)

        val micEnergy = meanSquare(micHop)
        val refEnergy = meanSquare(refHop)
        if (refEnergy > SILENCE) hopsSinceReference = 0 else hopsSinceReference++
        val slot = (hops % historySize).toInt()
        micEnvelope[slot] = ln(micEnergy + ENVELOPE_FLOOR)
        refEnvelope[slot] = ln(refEnergy + ENVELOPE_FLOOR)
        hops++
        if (hops >= historySize / 4 && hops % DELAY_UPDATE_HOPS == 0L) estimateDelay()

        val delay = delayHops
        if (delay == null) {
            echoPower.fill(0.0)
            hopEchoEnergy = 0.0
            return
        }
        referenceOnsetNear = (delay - 2..delay + ONSET_SPREAD_HOPS).any { it >= 0 && jump(refEnvelope, it) > ln(REFERENCE_JUMP) }
        if (playerStroke(delay)) hopsSincePlayerStroke = 0 else hopsSincePlayerStroke++
        if (!playerActive && hopsSincePlayerStroke >= PLAYER_PAUSE_HOPS) updateGains(delay)
        predictWindow(delay)
        predictHop(delay)
    }

    /** How much the (log) energy [age] hops ago rose above the lowest of the three hops before. */
    private fun jump(envelope: DoubleArray, age: Int): Double {
        if (hops - age < 4 || age >= historySize - 4) return 0.0
        val now = envelope[((hops - 1 - age) % historySize).toInt()]
        val before = (1..3).minOf { envelope[((hops - 1 - age - it) % historySize).toInt()] }
        return now - before
    }

    /** A level jump in the microphone that no jump in the (delayed) reference explains. */
    private fun playerStroke(delay: Int): Boolean {
        if (jump(micEnvelope, 0) < ln(STROKE_JUMP)) return false
        return (delay - 2..delay + 2).none { it >= 0 && jump(refEnvelope, it) > ln(REFERENCE_JUMP) }
    }

    /** RMS of what the microphone got beyond the predicted own sound. */
    fun residualLevel(micEnergy: Double): Double = sqrt((micEnergy - hopEchoEnergy).coerceAtLeast(0.0))

    private fun gain(band: Int): Double =
        exp(if (gainUpdates[band] >= MIN_BAND_UPDATES) logGain[band] else logGainAll)

    private fun updateGains(delay: Int) {
        spectrum(microphone, 0, windowSize, re, im)
        bandSums(re, micBands)
        spectrum(reference, windowStart(delay), windowSize, re, im)
        bandSums(re, refBands)

        val loudest = refBands.max()
        if (loudest <= 0.0) return
        // Band sums of a zero-padded FFT: Σ|X(k)|² = N Σ x², so per sample divide by N × L.
        val quiet = BAND_SILENCE * paddedSize * windowSize
        var micSum = 0.0
        var refSum = 0.0
        for (band in 0 until bandCount) {
            val ref = refBands[band]
            // Faint bands (decay tails) would mostly measure the microphone's noise.
            if (ref < loudest * ACTIVE_BAND || ref < quiet) continue
            micSum += micBands[band]
            refSum += ref
            val step = quantileStep(ln((micBands[band] + TINY) / ref), logGain[band], gainUpdates[band])
            logGain[band] += step
            gainUpdates[band]++
        }
        if (refSum > 0.0) {
            logGainAll += quantileStep(ln((micSum + TINY) / refSum), logGainAll, gainUpdatesAll)
            gainUpdatesAll++
        }
    }

    /** Stochastic quantile tracking; faster while there are few observations. */
    private fun quantileStep(x: Double, current: Double, updates: Int): Double {
        if (updates >= FAST_UPDATES && x > current + ln(OUTLIER)) return 0.0
        val rate = if (updates < FAST_UPDATES) FAST_RATE else RATE
        return rate * (QUANTILE - if (x < current) 1.0 else 0.0)
    }

    private fun predictWindow(delay: Int) {
        refPower.fill(0.0)
        for (lag in delay - 1..delay + 1) {
            if (lag < 0) continue
            spectrum(reference, windowStart(lag), windowSize, re, im)
            for (i in 0 until paddedSize) refPower[i] = max(refPower[i], re[i])
        }
        for (i in 0 until paddedSize) {
            val predicted = OVER_SUBTRACTION * gain(paddedBands[i]) * refPower[i]
            echoPower[i] = max(predicted, REVERB_DECAY * echoPower[i])
        }
    }

    private fun predictHop(delay: Int) {
        hopRefPower.fill(0.0)
        for (lag in delay - 1..delay + 1) {
            if (lag < 0) continue
            spectrum(reference, reference.size - hop - lag * hop, hop, hopRe, hopIm)
            for (i in 0 until hop) hopRefPower[i] = max(hopRefPower[i], hopRe[i])
        }
        var energy = 0.0
        for (i in 0 until hop) energy += gain(hopBands[i]) * hopRefPower[i]
        // Parseval: Σ|X(k)|² = N Σ x²; per sample that is Σ|X(k)|² / N².
        val predicted = OVER_SUBTRACTION * energy / (hop.toDouble() * hop)
        hopEchoEnergy = max(predicted, REVERB_DECAY * hopEchoEnergy)
    }

    private fun estimateDelay() {
        val length = min(hops, historySize.toLong()).toInt()
        val first = hops - length
        fun onset(envelope: DoubleArray, t: Long): Double =
            if (t <= first) 0.0 else (envelope[(t % historySize).toInt()] - envelope[((t - 1) % historySize).toInt()]).coerceAtLeast(0.0)

        val micOnsets = DoubleArray(length) { onset(micEnvelope, first + it) }
        val refOnsets = DoubleArray(length) { onset(refEnvelope, first + it) }
        val refStrength = refOnsets.sum()
        if (refStrength < MIN_ONSET_STRENGTH) return
        val norm = sqrt(micOnsets.sumOf { it * it } * refOnsets.sumOf { it * it })
        if (norm <= 0.0) return

        val scores = DoubleArray(MAX_DELAY_HOPS + 1) { lag ->
            var sum = 0.0
            for (t in lag until length) sum += micOnsets[t] * refOnsets[t - lag]
            sum / norm
        }
        val best = scores.indices.maxBy { scores[it] }
        val rival = scores.indices.filter { it !in best - 1..best + 1 }.maxOfOrNull { scores[it] } ?: 0.0
        if (scores[best] < MIN_CORRELATION || scores[best] < DISTINCT * rival) return

        val current = delayHops
        when {
            current == null || best == current -> delayHops = best
            best == delayCandidate -> delayHops = best // confirmed twice
            else -> delayCandidate = best
        }
    }

    private fun windowStart(lagHops: Int) = reference.size - windowSize - lagHops * hop

    /** Leaves |X(k)|² of `source[offset, offset + length)`, zero-padded to the array size, in [outRe]. */
    private fun spectrum(source: FloatArray, offset: Int, length: Int, outRe: DoubleArray, outIm: DoubleArray) {
        outRe.fill(0.0)
        outIm.fill(0.0)
        for (i in 0 until length) outRe[i] = source[offset + i].toDouble()
        fft(outRe, outIm)
        for (i in outRe.indices) {
            outRe[i] = outRe[i] * outRe[i] + outIm[i] * outIm[i]
            outIm[i] = 0.0
        }
    }

    private fun bandSums(power: DoubleArray, bands: DoubleArray) {
        bands.fill(0.0)
        for (i in 1 until paddedSize / 2) bands[paddedBands[i]] += power[i]
    }

    private fun slide(buffer: FloatArray, newest: FloatArray) {
        buffer.copyInto(buffer, destinationOffset = 0, startIndex = newest.size)
        newest.copyInto(buffer, destinationOffset = buffer.size - newest.size)
    }

    private fun meanSquare(samples: FloatArray): Double {
        var sum = 0.0
        for (sample in samples) sum += sample * sample
        return sum / samples.size
    }

    private fun bandOf(frequencyHz: Double): Int =
        (BANDS_PER_OCTAVE * log2(max(frequencyHz, LOWEST_BAND_HZ) / LOWEST_BAND_HZ)).toInt()

    private companion object {
        const val MAX_DELAY_HOPS = 30 // ~350 ms at 44.1 kHz
        const val HISTORY_SECONDS = 8
        const val DELAY_UPDATE_HOPS = 43
        const val MIN_ONSET_STRENGTH = 5.0
        const val MIN_CORRELATION = 0.2
        const val DISTINCT = 1.3
        const val RELEASE_HOPS = 20

        const val BANDS_PER_OCTAVE = 6
        const val LOWEST_BAND_HZ = 50.0
        const val ACTIVE_BAND = 1e-3
        const val BAND_SILENCE = 1e-8
        const val QUANTILE = 0.5
        const val RATE = 0.05
        const val FAST_RATE = 0.3
        const val FAST_UPDATES = 40
        const val MIN_BAND_UPDATES = 20
        const val MIN_GAIN_UPDATES = 100

        const val OUTLIER = 8.0
        const val STROKE_JUMP = 3.0 // energy ratio
        const val REFERENCE_JUMP = 2.0
        const val PLAYER_PAUSE_HOPS = 43 // ~0.5 s
        const val ONSET_SPREAD_HOPS = 4

        const val OVER_SUBTRACTION = 2.0
        const val REVERB_DECAY = 0.6
        const val SILENCE = 1e-7
        const val ENVELOPE_FLOOR = 1e-8
        const val TINY = 1e-12
    }
}
