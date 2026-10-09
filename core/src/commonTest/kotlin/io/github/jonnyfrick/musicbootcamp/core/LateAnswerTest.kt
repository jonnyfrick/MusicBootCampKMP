package io.github.jonnyfrick.musicbootcamp.core

import io.github.jonnyfrick.musicbootcamp.core.learning.LearnedSequences
import io.github.jonnyfrick.musicbootcamp.core.midi.MidiMessage
import io.github.jonnyfrick.musicbootcamp.core.model.KotlinRandomSource
import io.github.jonnyfrick.musicbootcamp.core.model.PracticeMode
import io.github.jonnyfrick.musicbootcamp.core.model.PracticeSettings
import io.github.jonnyfrick.musicbootcamp.core.model.SequenceElement.Note
import io.github.jonnyfrick.musicbootcamp.core.practice.Evaluation
import io.github.jonnyfrick.musicbootcamp.core.practice.OwnSoundGate
import io.github.jonnyfrick.musicbootcamp.core.practice.PracticeRunner
import io.github.jonnyfrick.musicbootcamp.core.practice.PracticeSession
import io.github.jonnyfrick.musicbootcamp.core.practice.StepResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

/** Late answers from the microphone: a key stroke shortly after the next note still answers the previous one. */
class LateAnswerTest {
    private val mono = PracticeSettings(mode = PracticeMode.MONOPHONIC, lowLimit = 48, highLimit = 72, startPosition = 60)

    private fun key(note: Int) = MidiMessage.noteOn(note, 100)

    @Test
    fun aLateKeyStrokeAnswersThePreviousStep() {
        val session = PracticeSession(mono, LearnedSequences(), KotlinRandomSource(Random(3))) { }
        assertEquals(listOf(60), session.step(deferEvaluation = true).given)

        val second = session.step(deferEvaluation = true)
        assertTrue(second.evaluationPending, "no answer yet, so the first step waits")
        assertNull(second.previousCorrect)

        assertEquals(listOf(Evaluation(correct = true, storedMistake = false)), session.onMidiInput(key(60)))
        assertEquals(emptyList(), session.onMidiInput(key(second.given.single())), "the next stroke answers the current step")
        assertEquals(emptyList(), session.expire(second.openStep!!), "nothing open any more")

        val third = session.step(deferEvaluation = true)
        assertFalse(third.evaluationPending)
        assertEquals(true, third.previousCorrect)
    }

    @Test
    fun withoutALateAnswerTheStepIsWrongWhenTheToleranceEnds() {
        val session = PracticeSession(mono, LearnedSequences(), KotlinRandomSource(Random(3))) { }
        session.step(deferEvaluation = true)
        val second = session.step(deferEvaluation = true)

        assertEquals(listOf(Evaluation(correct = false, storedMistake = false)), session.expire(second.openStep!!))
        assertEquals(emptyList(), session.expire(second.openStep!!))

        session.onMidiInput(key(second.given.single()))
        assertEquals(true, session.step(deferEvaluation = true).previousCorrect)
    }

    @Test
    fun aLateMistakeIsStored() {
        val memory = LearnedSequences()
        val session = PracticeSession(mono.copy(learnNewSequences = true), memory, KotlinRandomSource(Random(3))) { }
        session.step(deferEvaluation = true)
        session.onMidiInput(key(60))
        val second = session.step(deferEvaluation = true).given.single()
        session.step(deferEvaluation = true)

        val evaluation = session.onMidiInput(key(second + 1)).first()
        assertEquals(Evaluation(correct = false, storedMistake = true), evaluation)
        assertEquals(listOf(1, 0, 0, 0, 0), memory.countsByPriority(), "the sequence 60, $second")
    }

    @Test
    fun answersInTimeGiveTheSameExerciseAsWithoutTolerance() {
        // With every step answered before the next note, deferring never happens: same notes,
        // same stored mistakes, same random numbers consumed.
        for (seed in 1..10) {
            val settings = mono.copy(learnedProbability = 0.5, learnNewSequences = true)
            fun run(defer: Boolean): Pair<List<MidiMessage>, List<StepResult>> {
                val memory = LearnedSequences()
                memory.insert(listOf(Note(60), Note(62), Note(64)), 0)
                val sent = mutableListOf<MidiMessage>()
                val session = PracticeSession(settings, memory, KotlinRandomSource(Random(seed))) { sent += it }
                val answers = Random(seed + 100)
                val results = List(200) {
                    val result = session.step(deferEvaluation = defer)
                    val given = result.given.single()
                    session.onMidiInput(key(if (answers.nextInt(4) == 0) given + 1 else given))
                    result
                }
                return sent to results
            }
            assertEquals(run(defer = false), run(defer = true), "seed $seed")
        }
    }

    @Test
    fun anAnswerMayComeAfterSeveralLaterNotes() {
        // Fast tempo: the player answers each note while the notes after it already sound.
        val session = PracticeSession(mono, LearnedSequences(), KotlinRandomSource(Random(3))) { }
        val given = List(3) { session.step(deferEvaluation = true).given.single() }
        assertEquals(listOf(Evaluation(true, false)), session.onMidiInput(key(given[0])))
        assertEquals(listOf(Evaluation(true, false)), session.onMidiInput(key(given[1])))
        assertEquals(emptyList(), session.onMidiInput(key(given[2])), "the current step is evaluated at the next one")
        assertEquals(true, session.step(deferEvaluation = true).previousCorrect)
    }

    @Test
    fun aStrokeMatchingALaterOpenStepSkipsAMissedOne() {
        // A note the player left out must not shift all later answers by one step.
        val session = PracticeSession(mono, LearnedSequences(), KotlinRandomSource(Random(3))) { }
        val given = List(3) { session.step(deferEvaluation = true).given.single() }
        assertEquals(
            listOf(Evaluation(false, false), Evaluation(true, false)),
            session.onMidiInput(key(given[1])),
            "the first step counts as missed, the second as answered",
        )
    }

    @Test
    fun aStrokeJustTooLateDoesNotTakeTheNextAnswer() {
        // Found live: an answer 13 ms after its tolerance answered the next step, and every later
        // answer then counted for the step after its own.
        val session = PracticeSession(mono, LearnedSequences(), KotlinRandomSource(Random(3))) { }
        val given = mutableListOf<Int>()
        given += session.step(deferEvaluation = true).given.single()
        val second = session.step(deferEvaluation = true)
        given += second.given.single()
        assertEquals(listOf(Evaluation(false, false)), session.expire(second.openStep!!))

        assertEquals(emptyList(), session.onMidiInput(key(given[0])), "too late for the first step, ignored")
        session.onMidiInput(key(given[1]))
        assertEquals(true, session.step(deferEvaluation = true).previousCorrect, "the second step keeps its own answer")
    }

    @Test
    fun aChordAnswersTheOpenStepItMatches() {
        // With two voices a recognised chord arrives as two note-ons (a unison as the note twice).
        val settings = PracticeSettings(
            mode = PracticeMode.TWO_VOICES_PURE_RANDOM, lowLimit = 48, highLimit = 72, startPosition = 60,
        )
        val session = PracticeSession(settings, LearnedSequences(), KotlinRandomSource(Random(5))) { }
        val first = session.step(deferEvaluation = true).given
        val second = session.step(deferEvaluation = true)
        assertTrue(second.evaluationPending)

        if (first[0] == first[1]) {
            // A unison is answered by its note once (one key on a keyboard); the repeat the chord
            // recognition sends for the second voice is not another answer.
            assertEquals(listOf(Evaluation(true, false)), session.onMidiInput(key(first[0])), "late")
            assertEquals(emptyList(), session.onMidiInput(key(first[1])), "the repeat belongs to it")
        } else {
            assertEquals(emptyList(), session.onMidiInput(key(first[1])), "half a chord is no answer yet")
            assertEquals(listOf(Evaluation(true, false)), session.onMidiInput(key(first[0])), "late, and in any order")
        }

        session.onMidiInput(key(second.given[0]))
        session.onMidiInput(key(second.given[1] + 1))
        assertEquals(false, session.step(deferEvaluation = true).previousCorrect, "a wrong second voice")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun atFastTempoAnswersHalfAStepLateAllCount() = runTest {
        val settings = mono.copy(breathingTime = 0.7f)
        val input = MutableSharedFlow<MidiMessage>(extraBufferCapacity = 8)
        val given = mutableListOf<Int>()
        val runner = PracticeRunner(
            backgroundScope, settings, LearnedSequences(), { if (it.isNoteOn) given += it.data1 }, input,
            random = KotlinRandomSource(Random(3)),
            lateAnswerTolerance = 560.milliseconds, // 500 ms + detection
            maxSteps = 8,
            sessionDispatcher = StandardTestDispatcher(testScheduler),
        )
        runner.start()
        runCurrent()
        // Every answer 500 ms after the next note started, i.e. 1.2 s after its own note.
        for (step in 0 until 8) {
            advanceTimeBy(if (step == 0) 1_200L else 700L)
            input.emit(key(given[step]))
            runCurrent()
        }
        advanceTimeBy(2_000)
        assertTrue(runner.status.value.finished)
        assertEquals(8, runner.status.value.correct, "wrong: ${runner.status.value.wrong}")
        runner.stop()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun runnerWaitsForTheToleranceBeforeEvaluating() = runTest {
        val settings = mono.copy(breathingTime = 1.0f)
        val input = MutableSharedFlow<MidiMessage>(extraBufferCapacity = 8)
        val runner = PracticeRunner(
            backgroundScope, settings, LearnedSequences(), { }, input,
            random = KotlinRandomSource(Random(3)),
            lateAnswerTolerance = 150.milliseconds,
            sessionDispatcher = StandardTestDispatcher(testScheduler),
        )
        runner.start()
        runCurrent() // first step at 0 ms: note 60

        advanceTimeBy(1_100) // the second note started 100 ms ago
        input.emit(key(60))
        runCurrent()
        assertEquals(1, runner.status.value.correct, "a stroke 100 ms late answers the first step")

        advanceTimeBy(1_100) // no answer to the second step; the tolerance after 2 000 ms has passed
        assertEquals(1, runner.status.value.wrong)
        assertEquals(3, runner.status.value.steps)
        runner.stop()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aRunOfFixedLengthEvaluatesTheLastNoteAndFinishes() = runTest {
        val settings = mono.copy(breathingTime = 1.0f)
        val input = MutableSharedFlow<MidiMessage>(extraBufferCapacity = 8)
        val sent = mutableListOf<MidiMessage>()
        val runner = PracticeRunner(
            backgroundScope, settings, LearnedSequences(), { sent += it }, input,
            random = KotlinRandomSource(Random(3)),
            lateAnswerTolerance = 150.milliseconds,
            maxSteps = 3,
            sessionDispatcher = StandardTestDispatcher(testScheduler),
        )
        runner.start()
        runCurrent()
        advanceTimeBy(2_500) // three notes given (0, 1000, 2000 ms)
        input.emit(key(runner.status.value.given.single()))
        runCurrent()
        assertFalse(runner.status.value.finished)

        advanceTimeBy(1_000) // at 3000 ms no fourth note; the last one is evaluated after the tolerance
        assertEquals(3, runner.status.value.steps)
        assertEquals(1, runner.status.value.correct)
        assertEquals(2, runner.status.value.wrong)
        assertTrue(runner.status.value.finished)
        assertEquals(3, sent.count { it.isNoteOn })
        runner.stop()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun theLastNoteMayBeAnsweredUpToAStepLater() = runTest {
        val settings = mono.copy(breathingTime = 1.0f)
        val input = MutableSharedFlow<MidiMessage>(extraBufferCapacity = 8)
        val runner = PracticeRunner(
            backgroundScope, settings, LearnedSequences(), { }, input,
            random = KotlinRandomSource(Random(3)),
            lateAnswerTolerance = 150.milliseconds,
            maxSteps = 2,
            sessionDispatcher = StandardTestDispatcher(testScheduler),
        )
        runner.start()
        runCurrent()
        advanceTimeBy(1_500)
        val last = runner.status.value.given.single()
        advanceTimeBy(1_000) // 2500 ms: after the tolerance, but there is no next note to confuse it with
        assertFalse(runner.status.value.finished)
        input.emit(key(last))
        advanceTimeBy(100)
        assertTrue(runner.status.value.finished)
        assertEquals(1, runner.status.value.correct)
        runner.stop()
    }

    @Test
    fun ownSoundGateLetsOtherNotesThroughWhileTheAppPlays() {
        val time = TestTimeSource()
        val gate = OwnSoundGate({ }, timeSource = time)
        gate.send(MidiMessage.noteOn(60, 80))
        time += 200.milliseconds
        assertFalse(gate.accepts(key(60)), "the app's own attack")
        assertFalse(gate.accepts(key(72)), "an octave of it, a typical detection error")
        assertTrue(gate.accepts(key(62)), "a late answer to the previous note")
    }

    @Test
    fun ownSoundGateTakesANoteForTheAppsOnlyRightAfterItsAttack() {
        // Found live on a phone at 1 s per step: the player's answer to the first step came 0.55 s
        // after the app's note, which still sounded, and was dropped as the app's own.
        val time = TestTimeSource()
        val gate = OwnSoundGate({ }, timeSource = time)
        gate.send(MidiMessage.noteOn(60, 80))
        time += 180.milliseconds
        assertFalse(gate.accepts(key(60)), "the app's attack reaching the microphone")
        time += 350.milliseconds
        assertTrue(gate.accepts(key(60)), "a new stroke of the same note is the player's")
        gate.send(MidiMessage.noteOff(60))
        gate.send(MidiMessage.noteOn(64, 80))
        time += 100.milliseconds
        assertTrue(gate.accepts(key(60)), "the released note has no new attack")
        assertFalse(gate.accepts(key(64)))
    }

    @Test
    fun ownSoundGateTakesAChordForTheAppsOnlyRightAfterItsAttack() {
        // Found live on a phone: the answer to the first step (the same notes the app still sounds,
        // before its sound can be removed) was ignored for the whole length of the app's note.
        val time = TestTimeSource()
        val gate = OwnSoundGate({ }, timeSource = time)
        gate.send(MidiMessage.noteOn(60, 80))
        gate.send(MidiMessage.noteOn(64, 80))
        time += 500.milliseconds
        assertFalse(gate.acceptsChord(listOf(60, 64)), "the app's attack, as the microphone hears it")
        assertFalse(gate.acceptsChord(listOf(60, 76)), "also with an octave error")
        assertTrue(gate.acceptsChord(listOf(60, 65)), "another chord is the player's")
        time += 1000.milliseconds
        assertTrue(gate.acceptsChord(listOf(60, 64)), "a later stroke is the player's, though the app's notes still sound")
    }

    /** With a tolerance on a MIDI keyboard, chords arrive key by key, not as recognised chords. */
    private fun duoSession() = PracticeSession(
        PracticeSettings(mode = PracticeMode.TWO_VOICES_PURE_RANDOM, lowLimit = 48, highLimit = 72, startPosition = 60),
        LearnedSequences(), KotlinRandomSource(Random(3)),
    ) { }

    @Test
    fun onAKeyboardOneKeyAnswersAUnison() {
        val session = duoSession()
        // Step until a unison is given, answering every step in between with its two keys.
        var given = session.step(deferEvaluation = true).given
        var guard = 0
        while (given[0] != given[1]) {
            given.forEach { session.onMidiInput(key(it)) }
            given = session.step(deferEvaluation = true).given
            check(++guard < 500) { "no unison in 500 steps" }
        }
        session.onMidiInput(key(given[0]))
        val next = session.step(deferEvaluation = true)
        assertEquals(true, next.previousCorrect, "one key for both voices")
        assertFalse(next.evaluationPending)
        // The next answer is not disturbed by anything left over.
        next.given.distinct().forEach { session.onMidiInput(key(it)) }
        assertEquals(true, session.step(deferEvaluation = true).previousCorrect)
    }

    @Test
    fun oneKeyOfTwoIsAWrongAnswerAndDoesNotShiftTheNextOne() {
        val session = duoSession()
        var given = session.step(deferEvaluation = true).given
        while (given[0] == given[1]) {
            session.onMidiInput(key(given[0]))
            given = session.step(deferEvaluation = true).given
        }
        session.onMidiInput(key(given[0])) // only one of the two notes
        val second = session.step(deferEvaluation = true)
        assertTrue(second.evaluationPending)
        assertEquals(listOf(Evaluation(false, false)), session.expire(second.openStep!!), "incomplete: wrong")
        second.given.distinct().forEach { session.onMidiInput(key(it)) }
        assertEquals(true, session.step(deferEvaluation = true).previousCorrect, "the next answer stands on its own")
    }
}
