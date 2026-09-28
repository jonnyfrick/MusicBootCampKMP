package io.github.jonnyfrick.musicbootcamp.core.pitch

import kotlin.math.abs
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
 * - Delay: after each onset of the reference, the lag of the steepest rise in the microphone
 *   is a vote; the lag most recent votes agree on wins (see [voteForDelay]). Tolerates ±1 hop.
 * - Gain per frequency band (loudspeaker, room, microphone): the median of the ratio
 *   microphone / reference, learned only while the player is not playing. A stroke shows as a
 *   level jump in the microphone without one in the reference; learning pauses after it, so a
 *   missed stroke cannot raise the gain and hide the next one. Far outliers are ignored.
 * - Room reverberation: the prediction decays by [reverbDecay] per hop at the slowest.
 *
 * The prediction is scaled by [overSubtraction] as a safety margin. Until the delay is known
 * and the gains have settled ([ready]) — in a room where the app is hardly audible that may
 * never happen — callers should only accept strokes well above [levelWhileReference], the
 * microphone's usual level while the app plays.
 */
internal class EchoEstimator(
    sampleRate: Int,
    private val windowSize: Int,
    private val paddedSize: Int,
    private val hop: Int,
    private val overSubtraction: Double = 2.0,
    private val reverbDecay: Double = 0.6,
    /** The quantile of microphone/reference the gains follow; lower is safer against the player's notes. */
    private val gainQuantile: Double = 0.5,
) {
    private val bandCount = bandOf(sampleRate / 2.0) + 1
    private val paddedBands = IntArray(paddedSize) { bandOf(min(it, paddedSize - it) * sampleRate.toDouble() / paddedSize) }
    private val hopBands = IntArray(hop) { bandOf(min(it, hop - it) * sampleRate.toDouble() / hop) }

    private val reference = FloatArray(windowSize + (MAX_DELAY_HOPS + 2) * hop)
    private val microphone = FloatArray(windowSize)

    private val historySize = HISTORY_SECONDS * sampleRate / hop
    private val micEnvelope = DoubleArray(historySize) { ln(ENVELOPE_FLOOR) } // silence before the start
    private val refEnvelope = DoubleArray(historySize) { ln(ENVELOPE_FLOOR) }
    private var hops = 0L
    private var hopsSinceReference = Int.MAX_VALUE / 2
    private var hopsSincePlayerStroke = Int.MAX_VALUE / 2

    /** Delay of the microphone behind the reference, in hops; null until it was found. */
    var delayHops: Int? = null
        private set
    private val delayVotes = ArrayDeque<Int>()

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

    private var logLevelWhileReference = ln(ENVELOPE_FLOOR)
    private var levelUpdates = 0
    private var hopsSinceMicJump = Int.MAX_VALUE / 2

    /**
     * The microphone's RMS while the app plays audibly and the player (as far as visible) does not,
     * at its loud end ([LEVEL_QUANTILE]): the app's sound in the room. Needs no delay or gain estimate.
     */
    val levelWhileReference: Double get() = sqrt(exp(logLevelWhileReference))

    /** Whether [levelWhileReference] has seen enough of the app's sound, beyond the longest delay before it is heard. */
    val levelKnown: Boolean get() = levelUpdates >= MAX_DELAY_HOPS + MIN_LEVEL_UPDATES

    /**
     * Whether the app's sound started a note within the longest delay: its attack may be reaching
     * the microphone now (without knowing the delay, see [referenceOnsetNear]).
     */
    var referenceOnsetRecent = false
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
        voteForDelay()
        // A jump in the microphone that no recent onset of the reference explains is the player.
        val explained = (0..MAX_DELAY_HOPS).any { jump(refEnvelope, it) > ln(REFERENCE_JUMP) }
        referenceOnsetRecent = explained
        if (jump(micEnvelope, 0) > ln(STROKE_JUMP) && !explained) hopsSinceMicJump = 0 else hopsSinceMicJump++
        val audible = (0..MAX_DELAY_HOPS).any { it < hops && refEnvelope[slot(hops - 1 - it)] > ln(LOUD_REFERENCE) }
        if (audible && hopsSinceMicJump >= PLAYER_PAUSE_HOPS) {
            val x = ln(micEnergy + ENVELOPE_FLOOR)
            // While learning, the loudest so far (safe side); then the loud end, slowly.
            logLevelWhileReference = when {
                levelUpdates == 0 -> x
                !levelKnown -> max(logLevelWhileReference, x)
                else -> logLevelWhileReference + quantileStep(x, logLevelWhileReference, FAST_UPDATES, LEVEL_QUANTILE, rejectOutliers = false)
            }
            levelUpdates++
        }

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
        if (age >= hops || age >= historySize - 4) return 0.0
        val now = envelope[slot(hops - 1 - age)]
        val before = (1..3).minOf { envelope[slot(hops - 1 - age - it)] }
        return now - before
    }

    /** Ring buffer index of hop [t]; before the start it holds silence. */
    private fun slot(t: Long): Int = (((t % historySize) + historySize) % historySize).toInt()

    /** A level jump in the microphone that no jump in the (delayed) reference explains. */
    private fun playerStroke(delay: Int): Boolean {
        if (jump(micEnvelope, 0) < ln(STROKE_JUMP)) return false
        return (delay - 2..delay + 2).none { it >= 0 && jump(refEnvelope, it) > ln(REFERENCE_JUMP) }
    }

    /** Predicted power of the app's sound per unit of reference power at [frequencyHz] (with the safety factor). */
    fun ownSoundGain(frequencyHz: Double): Double = overSubtraction * gain(bandOf(frequencyHz))

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
            val step = quantileStep(ln((micBands[band] + TINY) / ref), logGain[band], gainUpdates[band], gainQuantile)
            logGain[band] += step
            gainUpdates[band]++
        }
        if (refSum > 0.0) {
            logGainAll += quantileStep(ln((micSum + TINY) / refSum), logGainAll, gainUpdatesAll, gainQuantile)
            gainUpdatesAll++
        }
    }

    /** Stochastic quantile tracking; faster while there are few observations. */
    private fun quantileStep(
        x: Double,
        current: Double,
        updates: Int,
        quantile: Double = QUANTILE,
        rejectOutliers: Boolean = true,
    ): Double {
        if (rejectOutliers && updates >= FAST_UPDATES && x > current + ln(OUTLIER)) return 0.0
        val rate = if (updates < FAST_UPDATES) FAST_RATE else RATE
        return rate * (quantile - if (x < current) 1.0 else 0.0)
    }

    private fun predictWindow(delay: Int) {
        refPower.fill(0.0)
        for (lag in delay - 1..delay + 1) {
            if (lag < 0) continue
            spectrum(reference, windowStart(lag), windowSize, re, im)
            for (i in 0 until paddedSize) refPower[i] = max(refPower[i], re[i])
        }
        for (i in 0 until paddedSize) {
            val predicted = overSubtraction * gain(paddedBands[i]) * refPower[i]
            echoPower[i] = max(predicted, reverbDecay * echoPower[i])
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
        val predicted = overSubtraction * energy / (hop.toDouble() * hop)
        hopEchoEnergy = max(predicted, reverbDecay * hopEchoEnergy)
    }

    /**
     * Once an onset of the reference is [MAX_DELAY_HOPS] old, votes for the lag at which the
     * microphone level rose most after it. The player's strokes are much louder than the app's
     * sound in the microphone, so correlating whole envelopes lets them dominate; per onset,
     * a stroke can at most cast one stray vote.
     */
    private fun voteForDelay() {
        val age = MAX_DELAY_HOPS + 1
        if (jump(refEnvelope, age) < ln(REFERENCE_ONSET)) return
        if (refEnvelope[((hops - 1 - age) % historySize).toInt()] < ln(AUDIBLE_REFERENCE)) return
        // The loudspeaker needs at least the output latency; shorter lags are the player or noise.
        val best = (MIN_DELAY_HOPS..MAX_DELAY_HOPS).maxBy { lag -> jump(micEnvelope, age - lag) }
        if (jump(micEnvelope, age - best) < ln(MICROPHONE_ONSET)) return // not heard (e.g. under a loud stroke)

        delayVotes.addLast(best)
        if (delayVotes.size > DELAY_VOTES) delayVotes.removeFirst()
        // The lag most votes agree with (±1 hop); it must hold at least two and half of them.
        val (winner, support) = (0..MAX_DELAY_HOPS).map { lag -> lag to delayVotes.count { abs(it - lag) <= 1 } }
            .maxWith(compareBy<Pair<Int, Int>> { it.second }.thenBy { lag -> delayVotes.count { it == lag.first } })
        if (support < MIN_DELAY_SUPPORT || 2 * support < delayVotes.size) return
        val current = delayHops
        if (current == null || abs(winner - current) > 1 && support >= MIN_DELAY_SUPPORT + 1) delayHops = winner
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
        const val MIN_LEVEL_UPDATES = 10
        const val LEVEL_QUANTILE = 0.9
        const val LOUD_REFERENCE = 1e-5 // -50 dB
        const val DELAY_VOTES = 9
        const val MIN_DELAY_SUPPORT = 3
        const val MIN_DELAY_HOPS = 2
        const val REFERENCE_ONSET = 4.0 // energy ratio
        const val MICROPHONE_ONSET = 2.0
        const val AUDIBLE_REFERENCE = 1e-6 // -60 dB
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
        const val ONSET_SPREAD_HOPS = 3

        const val SILENCE = 1e-7
        const val ENVELOPE_FLOOR = 1e-8
        const val TINY = 1e-12
    }
}
