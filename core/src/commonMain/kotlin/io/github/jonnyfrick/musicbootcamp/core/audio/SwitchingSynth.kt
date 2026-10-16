package io.github.jonnyfrick.musicbootcamp.core.audio

import io.github.jonnyfrick.musicbootcamp.core.midi.MidiMessage

/**
 * Plays on [first] until [better] becomes available (e.g. samples still being loaded when an
 * exercise starts), then on that from the next note on. The settings sent so far (tuning, pedal)
 * are passed on to it; notes still sounding on [first] ring out.
 */
class SwitchingSynth(private val first: SoftwareSynth, private val better: () -> SoftwareSynth?) : SoftwareSynth {
    private var second: SoftwareSynth? = null
    private val settings = mutableListOf<MidiMessage>()
    private var mixBuffer = FloatArray(0)

    /** Whether it plays on the better synthesizer by now. */
    val switched: Boolean get() = second != null

    override fun send(message: MidiMessage) {
        val current = second
        if (current != null) {
            if (message.command == MidiMessage.NOTE_OFF || message.command == MidiMessage.NOTE_ON && !message.isNoteOn) first.send(message)
            current.send(message)
            return
        }
        if (message.isNoteOn) better()?.let { next ->
            settings.forEach(next::send)
            settings.clear()
            second = next
            next.send(message)
            return
        }
        if (message.command != MidiMessage.NOTE_ON && message.command != MidiMessage.NOTE_OFF) settings += message
        first.send(message)
    }

    override val isSilent: Boolean get() = first.isSilent && second?.isSilent != false

    override fun render(out: FloatArray, offset: Int, frames: Int) {
        val next = second ?: return first.render(out, offset, frames)
        next.render(out, offset, frames)
        if (first.isSilent) return
        if (mixBuffer.size < frames) mixBuffer = FloatArray(frames)
        first.render(mixBuffer, 0, frames)
        for (i in 0 until frames) out[offset + i] = (out[offset + i] + mixBuffer[i]).coerceIn(-1f, 1f)
    }
}
