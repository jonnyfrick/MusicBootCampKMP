package io.github.jonnyfrick.musicbootcamp.core.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A small synthetic piano, used while (or where) the sampled one ([Sampler]) is not loaded:
 * every note is a set of decaying partials, slightly stretched like a piano string's, with a
 * softer fundamental in the bass.
 */
class PianoSynth(sampleRate: Int, maxVoices: Int = 24) : VoiceSynth(sampleRate, maxVoices) {
    override fun startVoice(note: Int, channel: Int, velocity: Int, semitones: Double): Voice =
        PartialsVoice(note, channel, velocity).also { it.tune(semitones) }

    private inner class PartialsVoice(note: Int, channel: Int, velocity: Int) : Voice(note, channel) {
        private val count: Int
        private val baseFrequency = DoubleArray(MAX_PARTIALS)
        private val re = DoubleArray(MAX_PARTIALS)
        private val im = DoubleArray(MAX_PARTIALS)
        private val cosStep = DoubleArray(MAX_PARTIALS)
        private val sinStep = DoubleArray(MAX_PARTIALS)
        private val decay = DoubleArray(MAX_PARTIALS)
        private val envelope = DoubleArray(BLOCK)
        private var age = 0
        private var release = 1.0
        private val releaseStep: Double

        init {
            val f0 = 440.0 * 2.0.pow((note - 69) / 12.0)
            val inharmonicity = 0.0001 * 2.0.pow((note - 21) / 20.0)
            // Deep notes ring long, high ones briefly; higher partials die away faster.
            val t60 = (12.0 * 2.0.pow(-(note - 21) / 18.0)).coerceIn(0.6, 12.0)
            val loudness = 0.18 * (velocity / 127.0).pow(1.6)
            var k = 0
            while (k < MAX_PARTIALS) {
                val n = k + 1
                val frequency = n * f0 * sqrt(1 + inharmonicity * n * n)
                if (frequency > 0.45 * sampleRate) break
                baseFrequency[k] = frequency
                val weight = if (n == 1 && note < 48) 0.35 else 1.0
                re[k] = 0.0
                im[k] = loudness * weight / n
                decay[k] = exp(ln(0.001) / (t60 / (1 + 0.35 * (n - 1)) * sampleRate))
                k++
            }
            count = k
            releaseStep = releaseStep(note, sampleRate)
        }

        override fun tune(semitones: Double) {
            val factor = 2.0.pow(semitones / 12.0)
            for (k in 0 until count) {
                val w = 2 * PI * baseFrequency[k] * factor / sampleRate
                cosStep[k] = cos(w)
                sinStep[k] = sin(w)
            }
        }

        override fun loudness(): Double = release * (0 until count).sumOf { k -> sqrt(re[k] * re[k] + im[k] * im[k]) }

        override fun render(out: FloatArray, start: Int, length: Int): Boolean {
            val attack = (ATTACK_SECONDS * sampleRate).toInt()
            for (i in 0 until length) {
                val rise = if (age + i < attack) (age + i).toDouble() / attack else 1.0
                if (releasing) release *= releaseStep
                envelope[i] = rise * release
            }
            age += length
            for (k in 0 until count) {
                var real = re[k]
                var imaginary = im[k]
                val c = cosStep[k]
                val s = sinStep[k]
                val d = decay[k]
                for (i in 0 until length) {
                    out[start + i] += (envelope[i] * imaginary).toFloat()
                    val nextReal = (real * c - imaginary * s) * d
                    imaginary = (real * s + imaginary * c) * d
                    real = nextReal
                }
                re[k] = real
                im[k] = imaginary
            }
            return release >= SILENCE && loudness() >= SILENCE
        }
    }

    internal companion object {
        const val MAX_PARTIALS = 16
        const val ATTACK_SECONDS = 0.003

        /** The factor per sample by which a released note dies away: quicker the higher it is, like dampers. */
        fun releaseStep(note: Int, sampleRate: Int): Double =
            exp(ln(0.001) / ((0.25 + 0.5 * (108 - note).coerceAtLeast(0) / 87.0) * sampleRate))
    }
}
