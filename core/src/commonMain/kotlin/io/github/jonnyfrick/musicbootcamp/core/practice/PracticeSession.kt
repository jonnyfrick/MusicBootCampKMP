package io.github.jonnyfrick.musicbootcamp.core.practice

import io.github.jonnyfrick.musicbootcamp.core.engine.TheoryEngine
import io.github.jonnyfrick.musicbootcamp.core.learning.LearnedSequences
import io.github.jonnyfrick.musicbootcamp.core.midi.MidiMessage
import io.github.jonnyfrick.musicbootcamp.core.midi.MidiOutput
import io.github.jonnyfrick.musicbootcamp.core.model.PracticeSettings
import io.github.jonnyfrick.musicbootcamp.core.model.RandomSource

/** What one exercise step did. */
data class StepResult(
    /** Whether the step that just ended was played correctly (null for the very first step). */
    val previousCorrect: Boolean?,
    /** Whether the mistake was stored in the learned-sequences memory. */
    val storedMistake: Boolean,
    /** The note(s) given now, one per voice. */
    val given: List<Int>,
    /** Notes started in this step; each needs a note-off after [PracticeSettings.noteDurationMillis]. */
    val startedNotes: List<Int>,
)

/**
 * One exercise run without any timing: [step] is what the Java `TimerTask`s did
 * on every tick, [onMidiInput] what the MIDI receiver did. [PracticeRunner] adds
 * the clock; tests drive it directly.
 *
 * Not thread-safe: call both functions from the same thread / confined dispatcher.
 */
class PracticeSession(
    private val settings: PracticeSettings,
    private val memory: LearnedSequences,
    random: RandomSource,
    private val output: MidiOutput,
) {
    init {
        require(settings.mode.isImplemented) { "${settings.mode} is not implemented" }
    }

    private val engine = TheoryEngine(settings, memory, random)
    private val corrector = Corrector.forMode(settings.mode, settings.memorySize)
    private var steps = 0

    /** Java: `AddToCorrectorReceiver` — only real key presses count. */
    fun onMidiInput(message: MidiMessage) {
        if (message.isNoteOn) corrector.addRecorded(message.data1)
    }

    fun step(): StepResult {
        val voices = settings.mode.voices
        val sounding = engine.positions()
        sounding.forEach { output.send(MidiMessage.noteOff(it)) }

        val correct = corrector.correct()
        var stored = false
        if (!correct && settings.learnNewSequences) {
            val predecessors = corrector.predecessors()
            // Monophonic mode only stores real sequences; two-voice mode stored single chords too.
            if (voices > 1 || predecessors.size > 1) {
                memory.insert(predecessors, 0)
                stored = true
            }
        }

        engine.changeNotes(voices)
        val given = engine.positions()

        val started = if (voices == 1) {
            corrector.addGiven(given[0])
            listOf(given[0])
        } else {
            corrector.resetGiven()
            given.forEach { corrector.addGiven(it) }
            given.distinct() // a unison is played once
        }
        started.forEach { output.send(MidiMessage.noteOn(it, settings.midiOutVelocity)) }

        if (voices == 1) corrector.resetRecorded()

        val result = StepResult(
            previousCorrect = if (steps == 0) null else correct,
            storedMistake = stored,
            given = given,
            startedNotes = started,
        )
        steps++
        return result
    }
}
