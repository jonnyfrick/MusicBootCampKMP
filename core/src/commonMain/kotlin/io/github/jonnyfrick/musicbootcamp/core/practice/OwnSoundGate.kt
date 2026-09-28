package io.github.jonnyfrick.musicbootcamp.core.practice

import io.github.jonnyfrick.musicbootcamp.core.midi.MidiMessage
import io.github.jonnyfrick.musicbootcamp.core.midi.MidiOutput
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * For microphone input without headphones: the microphone also hears the notes the app
 * plays. This output wrapper tracks what is sounding; [isQuiet] is false while an app note
 * sounds and for [releaseTime] after it. [accepts] lets a detected note through unless it
 * could be the app's own sound.
 *
 * Like [PracticeSession], use it from one thread: [PracticeRunner] sends the notes and
 * collects the input on the same confined dispatcher.
 */
class OwnSoundGate(
    private val output: MidiOutput,
    private val releaseTime: Duration = 200.milliseconds,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : MidiOutput {
    private val sounding = mutableSetOf<Int>()
    private val released = mutableMapOf<Int, TimeMark>()
    private var lastSilence = timeSource.markNow() - releaseTime

    override fun send(message: MidiMessage) {
        when {
            message.isNoteOn -> sounding += message.data1
            message.command == MidiMessage.NOTE_OFF || message.command == MidiMessage.NOTE_ON -> {
                if (sounding.remove(message.data1)) {
                    released[message.data1] = timeSource.markNow()
                    if (sounding.isEmpty()) lastSilence = timeSource.markNow()
                }
            }
            message == MidiMessage.allNotesOff() -> {
                if (sounding.isNotEmpty()) lastSilence = timeSource.markNow()
                sounding.forEach { released[it] = timeSource.markNow() }
                sounding.clear()
            }
        }
        output.send(message)
    }

    fun isQuiet(): Boolean = sounding.isEmpty() && lastSilence.elapsedNow() >= releaseTime

    /**
     * Whether a detected note-on can be the player's: always when [isQuiet], otherwise only if
     * it is neither a note the app is sounding (or released within [releaseTime]) nor an octave
     * of one — pitch detection sometimes reads a note an octave off.
     */
    fun accepts(message: MidiMessage): Boolean {
        if (isQuiet()) return true
        released.entries.removeAll { it.value.elapsedNow() >= releaseTime }
        val own = sounding + released.keys
        return own.none { (message.data1 - it) % 12 == 0 }
    }
}
