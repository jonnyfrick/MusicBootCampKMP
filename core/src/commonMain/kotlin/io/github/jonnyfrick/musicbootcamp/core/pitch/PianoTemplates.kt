package io.github.jonnyfrick.musicbootcamp.core.pitch

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Spectra of single notes of the player's piano, measured by calibration (log axis, see [LogSpectrum]). */
@Serializable
data class LearnedTemplates(
    val version: Int = 1,
    /** MIDI note → its magnitude spectrum on the log axis, as the chord analysis measures it. */
    val notes: Map<Int, List<Double>> = emptyMap(),
)

/**
 * The expected spectrum of each piano note on the log axis, for [TemplateChordRecognizer].
 *
 * Learned notes (calibration) are used as they are; a note without one is the nearest learned
 * note within [SHIFT_LIMIT] semitones moved along the log axis, else a piano model: partials
 * stretched by inharmonicity, amplitudes falling with the partial number and a weak fundamental
 * in the bass, each decaying faster the higher it is, averaged over the analysis window. The
 * model spectrum goes through the same FFT and log mapping as the measurement ([LogSpectrum]),
 * so both are smeared alike. Templates are normalised to unit length.
 */
class PianoTemplates(
    private val sampleRate: Int,
    private val fftSize: Int,
    private val referenceAHz: Double,
    /** The analysis window after the stroke, in seconds, for the decay of the partials. */
    private val windowStart: Double,
    private val windowEnd: Double,
    learned: LearnedTemplates = LearnedTemplates(),
) {
    private val learned = learned.notes.mapValues { (_, spectrum) -> normalise(spectrum.toDoubleArray()) }
    private val cache = mutableMapOf<Int, DoubleArray>()

    fun template(note: Int): DoubleArray = cache.getOrPut(note) {
        learned[note] ?: shiftedLearned(note) ?: normalise(model(note))
    }

    private fun shiftedLearned(note: Int): DoubleArray? {
        val nearest = learned.keys.minByOrNull { abs(it - note) } ?: return null
        val semitones = note - nearest
        if (abs(semitones) > SHIFT_LIMIT) return null
        val shift = semitones * LogSpectrum.BINS_PER_SEMITONE
        val source = learned.getValue(nearest)
        return normalise(DoubleArray(LogSpectrum.SIZE) { i -> source.getOrElse(i - shift) { 0.0 } })
    }

    private fun model(note: Int): DoubleArray {
        val f0 = LogSpectrum.frequencyOf(note.toDouble(), referenceAHz)
        val b = 0.0001 * 2.0.pow((note - 21) / 20.0)
        val fundamentalWeight = if (note < 48) 0.25 else 1.0
        val power = DoubleArray(fftSize / 2 + 1)
        for (n in 1..MAX_PARTIALS) {
            val frequency = n * f0 * sqrt(1 + b * n * n)
            if (frequency >= sampleRate / 2.0 * 0.95) break
            val decay = 1.2 + 0.6 * n
            val averageEnvelope = (exp(-decay * windowStart) - exp(-decay * windowEnd)) / (decay * (windowEnd - windowStart))
            val amplitude = (if (n == 1) fundamentalWeight else 1.0) / n * averageEnvelope
            addSinusoid(power, frequency * fftSize / sampleRate, amplitude * amplitude)
        }
        return LogSpectrum.fromPower(power, fftSize, sampleRate, referenceAHz)
    }

    /** The power a Hann-windowed sinusoid at fractional FFT bin [bin] leaves in the bins around it. */
    private fun addSinusoid(power: DoubleArray, bin: Double, strength: Double) {
        val centre = bin.roundToInt()
        for (k in centre - LOBE..centre + LOBE) {
            if (k !in power.indices) continue
            val w = hann(k - bin)
            power[k] += strength * w * w
        }
    }

    private companion object {
        const val MAX_PARTIALS = 30
        const val LOBE = 4
        const val SHIFT_LIMIT = 6

        fun sinc(x: Double) = if (abs(x) < 1e-9) 1.0 else sin(PI * x) / (PI * x)

        /** The Hann window's transform at a distance of [d] bins, normalised to 1 at 0. */
        fun hann(d: Double) = 0.5 * sinc(d) + 0.25 * (sinc(d - 1) + sinc(d + 1))

        fun normalise(values: DoubleArray): DoubleArray {
            val length = sqrt(values.sumOf { it * it })
            return if (length > 0) DoubleArray(values.size) { values[it] / length } else values
        }
    }
}
