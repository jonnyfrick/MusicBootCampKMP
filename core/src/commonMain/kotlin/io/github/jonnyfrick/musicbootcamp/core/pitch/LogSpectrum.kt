package io.github.jonnyfrick.musicbootcamp.core.pitch

import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A magnitude spectrum on a logarithmic frequency axis: [BINS_PER_SEMITONE] bins per semitone
 * from MIDI note [LOWEST_NOTE] (A0) up. On this axis a note's partial pattern keeps its shape
 * when it is moved by semitones, which is what the chord templates rely on.
 */
object LogSpectrum {
    const val BINS_PER_SEMITONE = 3
    const val LOWEST_NOTE = 21 // A0, 27.5 Hz
    const val HIGHEST_NOTE = 124 // about 10.5 kHz: upper partials of the treble still fit
    const val SIZE = (HIGHEST_NOTE - LOWEST_NOTE) * BINS_PER_SEMITONE + 1

    /** The (fractional) bin of [frequencyHz], relative to concert pitch [referenceAHz]. */
    fun binOf(frequencyHz: Double, referenceAHz: Double): Double =
        (frequencyToMidi(frequencyHz, referenceAHz) - LOWEST_NOTE) * BINS_PER_SEMITONE

    /** The bin of the fundamental of MIDI note [note]. */
    fun binOfNote(note: Int): Int = (note - LOWEST_NOTE) * BINS_PER_SEMITONE

    /**
     * Maps a power spectrum (`power[k]` for frequency `k * sampleRate / fftSize`, up to the
     * Nyquist frequency) onto the log axis: the power of each FFT bin is split linearly between
     * the two nearest log bins, then the square root gives magnitudes. Low log bins narrower than
     * an FFT bin share its power, so bass notes need long windows to be told apart.
     */
    fun fromPower(power: DoubleArray, fftSize: Int, sampleRate: Int, referenceAHz: Double): DoubleArray {
        val bins = DoubleArray(SIZE)
        for (k in 1 until minOf(power.size, fftSize / 2)) {
            val position = binOf(k.toDouble() * sampleRate / fftSize, referenceAHz)
            if (position < 0 || position >= SIZE - 1) continue
            val low = floor(position).toInt()
            val share = position - low
            bins[low] += power[k] * (1 - share)
            bins[low + 1] += power[k] * share
        }
        for (i in bins.indices) bins[i] = sqrt(bins[i])
        return bins
    }

    /** Frequency of MIDI note [note] (fractional) at concert pitch [referenceAHz]. */
    fun frequencyOf(note: Double, referenceAHz: Double): Double = referenceAHz * 2.0.pow((note - 69) / 12)

    /** Log-axis position of a frequency ratio (e.g. a partial relative to its fundamental). */
    fun binsFor(ratio: Double): Double = BINS_PER_SEMITONE * 12 * ln(ratio) / ln(2.0)
}
