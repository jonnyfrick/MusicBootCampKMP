package io.github.jonnyfrick.musicbootcamp.core.practice

import io.github.jonnyfrick.musicbootcamp.core.midi.MidiMessage
import io.github.jonnyfrick.musicbootcamp.core.midi.MidiOutput
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * For microphone input without headphones: the microphone also hears the notes the app plays.
 * This output wrapper tracks what the app struck when, and [accepts] / [acceptsChord] let a
 * recognised note or chord through unless it can be the app's own attack.
 *
 * Only the attack: the recognition looks at what a stroke adds to the sound before it, and a
 * note of the app has a single attack. So a note recognised later, while the app's note still
 * sounds, is the player's, also if it is the very same note. (At first every such note was
 * dropped for as long as the app's note sounded; recordings from a phone showed players
 * answering the first step, before the app's sound can be removed, in exactly that time.)
 *
 * Like [PracticeSession], use it from one thread: [PracticeRunner] sends the notes and
 * collects the input on the same confined dispatcher.
 */
class OwnSoundGate(
    private val output: MidiOutput,
    private val releaseTime: Duration = 200.milliseconds,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    /**
     * How long after the app struck a note a recognised single note can still be that attack:
     * the way to the microphone (up to 350 ms) plus the recognition (about 60 ms).
     */
    private val attackTime: Duration = 450.milliseconds,
    /** The same for a chord, whose analysis takes about 400 ms. */
    private val chordAttackTime: Duration = 900.milliseconds,
) : MidiOutput {
    private val sounding = mutableSetOf<Int>()
    private val struck = mutableMapOf<Int, TimeMark>()
    private var lastSilence = timeSource.markNow() - releaseTime

    override fun send(message: MidiMessage) {
        when {
            message.isNoteOn -> {
                sounding += message.data1
                struck[message.data1] = timeSource.markNow()
            }
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

    /** Whether the app sounds nothing, and has not for [releaseTime]. */
    fun isQuiet(): Boolean = sounding.isEmpty() && lastSilence.elapsedNow() >= releaseTime

    /**
     * Whether a recognised note can be the player's: unless the app struck that note, or an
     * octave of it (pitch detection sometimes reads a note an octave off), within [attackTime].
     */
    fun accepts(message: MidiMessage): Boolean = !struckWithin(message.data1, attackTime)

    /** For a recognised chord: ignored only if every one of its notes is one the app struck within [chordAttackTime]. */
    fun acceptsChord(notes: List<Int>): Boolean = notes.any { !struckWithin(it, chordAttackTime) }

    private fun struckWithin(note: Int, time: Duration): Boolean {
        struck.entries.removeAll { it.value.elapsedNow() >= maxOf(attackTime, chordAttackTime) }
        return struck.any { (appNote, mark) -> (note - appNote) % 12 == 0 && mark.elapsedNow() < time }
    }
}
