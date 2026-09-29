package io.github.jonnyfrick.musicbootcamp.core.audio

import io.github.jonnyfrick.musicbootcamp.core.midi.MidiMessage
import io.github.jonnyfrick.musicbootcamp.core.midi.MidiOutput
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A small software piano for platforms without a real-time synthesizer (Android): every note is
 * a set of decaying partials, slightly stretched like a piano string's, with a softer fundamental
 * in the bass. Understands note on/off, the sustain pedal, all notes/sound off and pitch bend with
 * its range set by RPN 0 (which is how [io.github.jonnyfrick.musicbootcamp.core.midi.Tuning] tunes).
 *
 * Because the app renders it itself, it knows exactly what the loudspeaker plays, so its sound can
 * be removed from the microphone signal as on the desktop with Gervill.
 *
 * Not thread-safe: the caller serialises [send] and [render].
 */
class PianoSynth(private val sampleRate: Int, private val maxVoices: Int = 24) : MidiOutput {
    private inner class Voice(val note: Int, val channel: Int, velocity: Int) {
        val count: Int
        val baseFrequency = DoubleArray(MAX_PARTIALS)
        val re = DoubleArray(MAX_PARTIALS)
        val im = DoubleArray(MAX_PARTIALS)
        val cosStep = DoubleArray(MAX_PARTIALS)
        val sinStep = DoubleArray(MAX_PARTIALS)
        val decay = DoubleArray(MAX_PARTIALS)
        var age = 0
        var releasing = false
        var held = false // key up, but the sustain pedal is down
        var release = 1.0
        val releaseStep: Double

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
            releaseStep = exp(ln(0.001) / ((0.25 + 0.5 * (108 - note).coerceAtLeast(0) / 87.0) * sampleRate))
            tune(bend(channel))
        }

        /** Sets the oscillators' frequencies for a pitch bend of [semitones]. */
        fun tune(semitones: Double) {
            val factor = 2.0.pow(semitones / 12.0)
            for (k in 0 until count) {
                val w = 2 * PI * baseFrequency[k] * factor / sampleRate
                cosStep[k] = cos(w)
                sinStep[k] = sin(w)
            }
        }

        fun loudness(): Double = release * (0 until count).sumOf { k -> sqrt(re[k] * re[k] + im[k] * im[k]) }
    }

    private val voices = mutableListOf<Voice>()
    private val envelope = DoubleArray(BLOCK)
    private val pitchBend = IntArray(16) { 8192 }
    private val bendRange = DoubleArray(16) { 2.0 }
    private val rpn = IntArray(16) { 0x3FFF }
    private val sustain = BooleanArray(16)

    private fun bend(channel: Int) = (pitchBend[channel] - 8192) / 8192.0 * bendRange[channel]

    override fun send(message: MidiMessage) {
        val channel = message.channel
        when (message.command) {
            MidiMessage.NOTE_ON -> if (message.data2 > 0) noteOn(message.data1, message.data2, channel) else noteOff(message.data1, channel)
            MidiMessage.NOTE_OFF -> noteOff(message.data1, channel)
            MidiMessage.PITCH_BEND -> {
                pitchBend[channel] = (message.data2 shl 7) or message.data1
                voices.filter { it.channel == channel }.forEach { it.tune(bend(channel)) }
            }
            MidiMessage.CONTROL_CHANGE -> controlChange(message.data1, message.data2, channel)
        }
    }

    private fun noteOn(note: Int, velocity: Int, channel: Int) {
        // A repeated key damps the string it strikes again.
        voices.filter { it.note == note && it.channel == channel && !it.releasing }.forEach { it.releasing = true }
        if (voices.size >= maxVoices) voices.remove(voices.minByOrNull { it.loudness() })
        voices += Voice(note, channel, velocity)
    }

    private fun noteOff(note: Int, channel: Int) {
        voices.filter { it.note == note && it.channel == channel && !it.releasing }.forEach {
            if (sustain[channel]) it.held = true else it.releasing = true
        }
    }

    private fun controlChange(controller: Int, value: Int, channel: Int) {
        when (controller) {
            64 -> {
                sustain[channel] = value >= 64
                if (!sustain[channel]) voices.filter { it.channel == channel && it.held }.forEach { it.releasing = true }
            }
            101 -> rpn[channel] = (value shl 7) or (rpn[channel] and 0x7F)
            100 -> rpn[channel] = (rpn[channel] and 0x3F80) or value
            6 -> if (rpn[channel] == 0) bendRange[channel] = value + bendRange[channel] % 1.0
            38 -> if (rpn[channel] == 0) bendRange[channel] = bendRange[channel].toInt() + value / 100.0
            120 -> voices.removeAll { it.channel == channel } // all sound off
            123 -> voices.filter { it.channel == channel }.forEach { it.releasing = true } // all notes off
        }
    }

    /** Whether anything still sounds. */
    val isSilent: Boolean get() = voices.isEmpty()

    /** Writes the next [frames] samples (mono, -1..1) to [out] from [offset] on. */
    fun render(out: FloatArray, offset: Int = 0, frames: Int = out.size - offset) {
        for (i in offset until offset + frames) out[i] = 0f
        var start = offset
        while (start < offset + frames) {
            val length = minOf(BLOCK, offset + frames - start)
            renderBlock(out, start, length)
            start += length
        }
        for (i in offset until offset + frames) out[i] = out[i].coerceIn(-1f, 1f)
    }

    private fun renderBlock(out: FloatArray, start: Int, length: Int) {
        val attack = (ATTACK_SECONDS * sampleRate).toInt()
        val iterator = voices.iterator()
        while (iterator.hasNext()) {
            val voice = iterator.next()
            for (i in 0 until length) {
                val rise = if (voice.age + i < attack) (voice.age + i).toDouble() / attack else 1.0
                if (voice.releasing) voice.release *= voice.releaseStep
                envelope[i] = rise * voice.release
            }
            voice.age += length
            for (k in 0 until voice.count) {
                var re = voice.re[k]
                var im = voice.im[k]
                val c = voice.cosStep[k]
                val s = voice.sinStep[k]
                val d = voice.decay[k]
                for (i in 0 until length) {
                    out[start + i] += (envelope[i] * im).toFloat()
                    val nextRe = (re * c - im * s) * d
                    im = (re * s + im * c) * d
                    re = nextRe
                }
                voice.re[k] = re
                voice.im[k] = im
            }
            if (voice.release < SILENCE || voice.loudness() < SILENCE) iterator.remove()
        }
    }

    private companion object {
        const val MAX_PARTIALS = 16
        const val BLOCK = 256
        const val ATTACK_SECONDS = 0.003
        const val SILENCE = 1e-5
    }
}
