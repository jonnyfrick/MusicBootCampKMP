package io.github.jonnyfrick.musicbootcamp.core.practice

import io.github.jonnyfrick.musicbootcamp.core.learning.LearnedSequences
import io.github.jonnyfrick.musicbootcamp.core.midi.MidiMessage
import io.github.jonnyfrick.musicbootcamp.core.midi.MidiOutput
import io.github.jonnyfrick.musicbootcamp.core.model.KotlinRandomSource
import io.github.jonnyfrick.musicbootcamp.core.model.PracticeSettings
import io.github.jonnyfrick.musicbootcamp.core.model.RandomSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Live figures of a running exercise, for the UI. */
data class PracticeStatus(
    val running: Boolean = false,
    val steps: Int = 0,
    val correct: Int = 0,
    val wrong: Int = 0,
    val storedMistakes: Int = 0,
    val given: List<Int> = emptyList(),
    val lastCorrect: Boolean? = null,
)

/**
 * Runs a [PracticeSession] on a clock (Java: `java.util.Timer` with the practice
 * `TimerTask`s and `TurnNoteOffTimerTask`).
 *
 * All session access — steps and incoming MIDI — happens on one confined
 * dispatcher, so the session needs no locking.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PracticeRunner(
    private val scope: CoroutineScope,
    private val settings: PracticeSettings,
    memory: LearnedSequences,
    private val output: MidiOutput,
    private val input: Flow<MidiMessage>,
    random: RandomSource = KotlinRandomSource(),
    private val sessionDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) {
    private val session = PracticeSession(settings, memory, random, output)
    private val _status = MutableStateFlow(PracticeStatus())
    val status: StateFlow<PracticeStatus> = _status.asStateFlow()

    private var jobs: List<Job> = emptyList()

    fun start() {
        if (jobs.isNotEmpty()) return
        _status.value = PracticeStatus(running = true)
        val inputJob = scope.launch(sessionDispatcher) {
            input.collect { session.onMidiInput(it) }
        }
        val clockJob = scope.launch(sessionDispatcher) {
            // Like Timer.schedule(task, 0, period): first step immediately, then fixed delay.
            while (isActive) {
                val result = session.step()
                result.startedNotes.forEach { note ->
                    launch {
                        delay(settings.noteDurationMillis)
                        output.send(MidiMessage.noteOff(note))
                    }
                }
                _status.update { it.after(result) }
                delay(settings.stepPeriodMillis)
            }
        }
        jobs = listOf(inputJob, clockJob)
    }

    /** Stops the clock, cancels pending note-offs and silences the output. */
    suspend fun stop() {
        jobs.forEach { it.cancelAndJoin() }
        jobs = emptyList()
        withContext(sessionDispatcher) { output.send(MidiMessage.allNotesOff()) }
        _status.update { it.copy(running = false) }
    }

    private fun PracticeStatus.after(result: StepResult) = copy(
        steps = steps + 1,
        correct = correct + if (result.previousCorrect == true) 1 else 0,
        wrong = wrong + if (result.previousCorrect == false) 1 else 0,
        storedMistakes = storedMistakes + if (result.storedMistake) 1 else 0,
        given = result.given,
        lastCorrect = result.previousCorrect ?: lastCorrect,
    )
}
