package io.github.jonnyfrick.musicbootcamp.core.practice

import io.github.jonnyfrick.musicbootcamp.core.engine.TheoryEngine
import io.github.jonnyfrick.musicbootcamp.core.learning.LearnedSequences
import io.github.jonnyfrick.musicbootcamp.core.midi.MidiMessage
import io.github.jonnyfrick.musicbootcamp.core.midi.MidiOutput
import io.github.jonnyfrick.musicbootcamp.core.model.PracticeSettings
import io.github.jonnyfrick.musicbootcamp.core.model.RandomSource

/** What one exercise step did. */
data class StepResult(
    /**
     * Whether the step that just ended was played correctly; null for the very first step and
     * while its evaluation is pending (see [evaluationPending]).
     */
    val previousCorrect: Boolean?,
    /** Whether the mistake was stored in the learned-sequences memory. */
    val storedMistake: Boolean,
    /** The note(s) given now, one per voice. */
    val given: List<Int>,
    /** Notes started in this step; each needs a note-off after [PracticeSettings.noteDurationMillis]. */
    val startedNotes: List<Int>,
    /**
     * The step that just ended had no answer yet and waits for a late one; its [Evaluation] comes
     * from [PracticeSession.onMidiInput] or [PracticeSession.finishEvaluation].
     */
    val evaluationPending: Boolean = false,
)

/** The outcome of a step that was evaluated after the next step had begun. */
data class Evaluation(
    val correct: Boolean,
    /** Whether the mistake was stored in the learned-sequences memory. */
    val storedMistake: Boolean,
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

    /** The notes of the current step while the previous step still waits for a late answer. */
    private var pendingGiven: List<Int>? = null

    /**
     * Java: `AddToCorrectorReceiver` — only real key presses count.
     *
     * While the previous step waits for a late answer, key presses count for it; returns its
     * [Evaluation] once it has its answer.
     */
    fun onMidiInput(message: MidiMessage): Evaluation? {
        if (!message.isNoteOn) return null
        corrector.addRecorded(message.data1)
        return if (pendingGiven != null && corrector.hasAnswer()) finishEvaluation() else null
    }

    /**
     * Evaluates the previous step if it still waits for a late answer (the tolerance is over);
     * null if nothing was pending.
     */
    fun finishEvaluation(): Evaluation? {
        val given = pendingGiven ?: return null
        pendingGiven = null
        val evaluation = evaluate()
        startGiven(given)
        return evaluation
    }

    /**
     * Ends the current step and gives the next note(s).
     *
     * With [deferEvaluation], a step without an answer yet is not evaluated now: the next note
     * starts on time, and key presses keep counting for the ended step until it has its answer or
     * [finishEvaluation] is called. Evaluating late means a stored mistake can only influence
     * the steps after the next one. Without it (the Java behaviour) the step is evaluated at once.
     * A still pending evaluation is finished first; call [finishEvaluation] before to get it.
     */
    fun step(deferEvaluation: Boolean = false): StepResult {
        finishEvaluation()
        val voices = settings.mode.voices
        val sounding = engine.positions()
        sounding.forEach { output.send(MidiMessage.noteOff(it)) }

        val defer = deferEvaluation && steps > 0 && !corrector.hasAnswer()
        val evaluation = if (defer) null else evaluate()

        engine.changeNotes(voices)
        val given = engine.positions()
        if (defer) pendingGiven = given else startGiven(given)

        val started = if (voices == 1) listOf(given[0]) else given.distinct() // a unison is played once
        started.forEach { output.send(MidiMessage.noteOn(it, settings.midiOutVelocity)) }

        val result = StepResult(
            previousCorrect = if (steps == 0) null else evaluation?.correct,
            storedMistake = evaluation?.storedMistake ?: false,
            given = given,
            startedNotes = started,
            evaluationPending = defer,
        )
        steps++
        return result
    }

    private fun evaluate(): Evaluation {
        val correct = corrector.correct()
        var stored = false
        if (!correct && settings.learnNewSequences) {
            val predecessors = corrector.predecessors()
            // Monophonic mode only stores real sequences; two-voice mode stored single chords too.
            if (settings.mode.voices > 1 || predecessors.size > 1) {
                memory.insert(predecessors, 0)
                stored = true
            }
        }
        return Evaluation(correct, stored)
    }

    /** Hands the given note(s) of a new step to the corrector. */
    private fun startGiven(given: List<Int>) {
        if (settings.mode.voices == 1) {
            corrector.addGiven(given[0])
            corrector.resetRecorded()
        } else {
            corrector.resetGiven()
            given.forEach { corrector.addGiven(it) }
        }
    }
}
