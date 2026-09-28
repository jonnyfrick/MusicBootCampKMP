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
     * from [PracticeSession.onMidiInput] or [PracticeSession.expire].
     */
    val evaluationPending: Boolean = false,
    /** The number of that step, for [PracticeSession.expire]. */
    val openStep: Int? = null,
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

    /**
     * With a late-answer tolerance (microphone): the steps whose answer may still come, oldest
     * first; the last one is the current step. The corrector only sees a step when it is evaluated.
     */
    private val open = ArrayDeque<OpenStep>()
    private var deferring = false

    private class OpenStep(val number: Int, val given: Int) {
        var answer: Int? = null
        /** Answered, or given up (its time is over, or a later step got the answer). */
        var resolved = false
    }

    /**
     * Java: `AddToCorrectorReceiver` — only real key presses count.
     *
     * With a tolerance, a key press answers the oldest open step — or, if it is exactly the note of
     * a later open step, that one, and the older ones count as missed (so a skipped note or a
     * false stroke does not shift all later answers). Returns the evaluations this completes.
     */
    fun onMidiInput(message: MidiMessage): List<Evaluation> {
        if (!message.isNoteOn) return emptyList()
        if (!deferring) {
            corrector.addRecorded(message.data1)
            return emptyList()
        }
        val waiting = open.filter { !it.resolved }
        if (waiting.isEmpty()) return emptyList() // only the first key press of a step counts
        val note = message.data1
        val target = waiting.firstOrNull { it.given == note } ?: waiting.first()
        for (step in waiting) {
            if (step === target) break
            step.resolved = true // missed
        }
        target.answer = note
        target.resolved = true
        return evaluateResolved()
    }

    /** The tolerance of step [number] is over: it (and older open steps) counts as missed if unanswered. */
    fun expire(number: Int): List<Evaluation> {
        val current = open.lastOrNull()
        open.filter { it.number <= number && it !== current }.forEach { it.resolved = true }
        return evaluateResolved()
    }

    /** Whether no step waits for an answer any more (so a run may end without waiting longer). */
    fun allAnswered(): Boolean = if (deferring) open.all { it.resolved } else corrector.hasAnswer()

    /**
     * Ends the current step and gives the next note(s).
     *
     * With [deferEvaluation] (monophonic only), a step without an answer yet stays open: the next
     * note starts on time, and key presses may still answer it (see [onMidiInput]) until [expire]
     * is called with its number ([StepResult.openStep]). Evaluating late means a stored mistake can
     * only influence the steps after the next one. A step answered in time is evaluated here, as
     * without it (the Java behaviour), so the exercise and the random numbers are the same then.
     */
    fun step(deferEvaluation: Boolean = false): StepResult {
        val voices = settings.mode.voices
        val sounding = engine.positions()
        sounding.forEach { output.send(MidiMessage.noteOff(it)) }

        val defer = deferEvaluation && voices == 1
        var leftOpen: Int? = null
        val evaluation = if (!defer) {
            evaluate()
        } else {
            deferring = true
            val current = open.lastOrNull()
            when {
                current == null -> null
                current.resolved -> evaluateStep(open.removeLast()) // older ones are done by then
                else -> null.also { leftOpen = current.number }
            }
        }

        engine.changeNotes(voices)
        val given = engine.positions()
        if (defer) open.addLast(OpenStep(steps, given[0])) else startGiven(given)

        val started = if (voices == 1) listOf(given[0]) else given.distinct() // a unison is played once
        started.forEach { output.send(MidiMessage.noteOn(it, settings.midiOutVelocity)) }

        val result = StepResult(
            previousCorrect = if (steps == 0) null else evaluation?.correct,
            storedMistake = evaluation?.storedMistake ?: false,
            given = given,
            startedNotes = started,
            evaluationPending = leftOpen != null,
            openStep = leftOpen,
        )
        steps++
        return result
    }

    /**
     * Evaluates the current step (and any still open) without giving a new one, to end a run of a
     * fixed length; empty before the first step.
     */
    fun end(): List<Evaluation> {
        if (steps == 0) return emptyList()
        if (!deferring) return listOf(evaluate())
        open.forEach { it.resolved = true }
        return buildList { while (open.isNotEmpty()) add(evaluateStep(open.removeFirst())) }
    }

    /** Evaluates resolved steps from the oldest on, in order; the current step waits for [step]. */
    private fun evaluateResolved(): List<Evaluation> = buildList {
        while (open.size > 1 && open.first().resolved) add(evaluateStep(open.removeFirst()))
    }

    private fun evaluateStep(step: OpenStep): Evaluation {
        corrector.addGiven(step.given)
        corrector.resetRecorded()
        step.answer?.let { corrector.addRecorded(it) }
        return evaluate()
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
