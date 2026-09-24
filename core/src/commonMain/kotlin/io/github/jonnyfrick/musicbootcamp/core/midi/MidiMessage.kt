package io.github.jonnyfrick.musicbootcamp.core.midi

/** A MIDI channel message (status byte plus two data bytes). */
data class MidiMessage(val status: Int, val data1: Int, val data2: Int) {

    val command: Int get() = status and 0xF0
    val channel: Int get() = status and 0x0F

    /** A key press; note-on with velocity 0 is a release by convention. */
    val isNoteOn: Boolean get() = command == NOTE_ON && data2 != 0

    companion object {
        const val NOTE_OFF = 0x80
        const val NOTE_ON = 0x90
        const val CONTROL_CHANGE = 0xB0
        const val PITCH_BEND = 0xE0

        fun noteOn(note: Int, velocity: Int, channel: Int = 0) = MidiMessage(NOTE_ON or channel, note, velocity)
        fun noteOff(note: Int, channel: Int = 0) = MidiMessage(NOTE_OFF or channel, note, 0)
        fun controlChange(controller: Int, value: Int, channel: Int = 0) =
            MidiMessage(CONTROL_CHANGE or channel, controller, value)

        fun pitchBend(lsb: Int, msb: Int, channel: Int = 0) = MidiMessage(PITCH_BEND or channel, lsb, msb)

        /** Controller 0x7B on channel 0, as the Java version sent when stopping. */
        fun allNotesOff() = controlChange(0x7B, 0)
    }
}

/** Where the exercise sends its notes. */
fun interface MidiOutput {
    fun send(message: MidiMessage)
}
