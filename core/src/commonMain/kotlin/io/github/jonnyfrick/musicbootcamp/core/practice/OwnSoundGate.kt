package io.github.jonnyfrick.musicbootcamp.core.practice

import io.github.jonnyfrick.musicbootcamp.core.midi.MidiMessage
import io.github.jonnyfrick.musicbootcamp.core.midi.MidiOutput
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * For microphone input without headphones: the microphone also hears the notes the app
 * plays. This output wrapper tracks what is sounding; [isQuiet] is false while an app note
 * sounds and for [releaseTime] after it, and input detected then is ignored.
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
    private var lastSilence = timeSource.markNow() - releaseTime

    override fun send(message: MidiMessage) {
        when {
            message.isNoteOn -> sounding += message.data1
            message.command == MidiMessage.NOTE_OFF || message.command == MidiMessage.NOTE_ON -> {
                if (sounding.remove(message.data1) && sounding.isEmpty()) lastSilence = timeSource.markNow()
            }
            message == MidiMessage.allNotesOff() -> {
                if (sounding.isNotEmpty()) lastSilence = timeSource.markNow()
                sounding.clear()
            }
        }
        output.send(message)
    }

    fun isQuiet(): Boolean = sounding.isEmpty() && lastSilence.elapsedNow() >= releaseTime
}
