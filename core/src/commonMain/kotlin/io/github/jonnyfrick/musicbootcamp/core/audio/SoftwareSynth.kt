package io.github.jonnyfrick.musicbootcamp.core.audio

import io.github.jonnyfrick.musicbootcamp.core.midi.MidiMessage
import io.github.jonnyfrick.musicbootcamp.core.midi.MidiOutput

/**
 * An instrument the app renders itself, for platforms without a real-time synthesizer, and so
 * that the app knows exactly what the loudspeaker plays and can remove it from the microphone
 * signal. Not thread-safe: the caller serialises [send] and [render].
 */
interface SoftwareSynth : MidiOutput {
    /** Writes the next [frames] samples (mono, -1..1) to [out] from [offset] on. */
    fun render(out: FloatArray, offset: Int = 0, frames: Int = out.size - offset)

    /** Whether nothing sounds any more. */
    val isSilent: Boolean
}

/**
 * What every [SoftwareSynth] of the app shares: the MIDI side (note on/off, sustain pedal, all
 * notes/sound off, pitch bend with its range set by RPN 0, which is how
 * [io.github.jonnyfrick.musicbootcamp.core.midi.Tuning] tunes) and the mixing of its voices.
 * A subclass only says how one note sounds ([startVoice]).
 */
abstract class VoiceSynth(protected val sampleRate: Int, private val maxVoices: Int) : SoftwareSynth {
    abstract class Voice(val note: Int, val channel: Int) {
        /** The key is up (or the note was struck again): the voice dies away. */
        var releasing = false

        /** The key is up, but the sustain pedal holds the note. */
        var held = false

        /** Follows a pitch bend of [semitones]. */
        abstract fun tune(semitones: Double)

        /** How loud it is now, to choose the voice to drop when there are too many. */
        abstract fun loudness(): Double

        /** Adds the next [length] samples to [out] from [start] on; false once the voice has ended. */
        abstract fun render(out: FloatArray, start: Int, length: Int): Boolean
    }

    /** A new voice for [note] struck with [velocity], already tuned to the pitch bend of [semitones]. */
    protected abstract fun startVoice(note: Int, channel: Int, velocity: Int, semitones: Double): Voice

    private val voices = mutableListOf<Voice>()
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
        voices += startVoice(note, channel, velocity, bend(channel))
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

    override val isSilent: Boolean get() = voices.isEmpty()

    override fun render(out: FloatArray, offset: Int, frames: Int) {
        for (i in offset until offset + frames) out[i] = 0f
        var start = offset
        while (start < offset + frames) {
            val length = minOf(BLOCK, offset + frames - start)
            val iterator = voices.iterator()
            while (iterator.hasNext()) if (!iterator.next().render(out, start, length)) iterator.remove()
            start += length
        }
        for (i in offset until offset + frames) out[i] = out[i].coerceIn(-1f, 1f)
    }

    protected companion object {
        /** Voices render at most this many samples at a time (the size of their work buffers). */
        const val BLOCK = 256

        /** Below this level a voice has ended. */
        const val SILENCE = 1e-5
    }
}
