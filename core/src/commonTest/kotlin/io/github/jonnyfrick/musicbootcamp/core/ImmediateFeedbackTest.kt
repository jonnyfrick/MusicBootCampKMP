package io.github.jonnyfrick.musicbootcamp.core

import io.github.jonnyfrick.musicbootcamp.core.learning.LearnedSequences
import io.github.jonnyfrick.musicbootcamp.core.midi.MidiMessage
import io.github.jonnyfrick.musicbootcamp.core.model.KotlinRandomSource
import io.github.jonnyfrick.musicbootcamp.core.model.PracticeMode
import io.github.jonnyfrick.musicbootcamp.core.model.PracticeSettings
import io.github.jonnyfrick.musicbootcamp.core.practice.PracticeSession
import io.github.jonnyfrick.musicbootcamp.core.practice.StepVerdict
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Right or wrong is known as soon as the answer is, for feedback, while the evaluation itself
 * still happens when the next step starts (Java behaviour: the memory and random numbers stay the same).
 */
class ImmediateFeedbackTest {
    private val mono = PracticeSettings(mode = PracticeMode.MONOPHONIC, lowLimit = 48, highLimit = 72, startPosition = 60)
    private val duo = PracticeSettings(mode = PracticeMode.TWO_VOICES_PURE_RANDOM, lowLimit = 48, highLimit = 72, startPosition = 60)

    private fun key(note: Int) = MidiMessage.noteOn(note, 100)
    private fun session(settings: PracticeSettings) = PracticeSession(settings, LearnedSequences(), KotlinRandomSource(Random(3))) { }

    @Test
    fun aKeyIsJudgedAtOnceAndEvaluatedWithTheNextStep() {
        val session = session(mono)
        val first = session.step()
        session.takeVerdicts()
        session.onMidiInput(key(first.given.single()))
        assertEquals(listOf(StepVerdict(0, true)), session.takeVerdicts(), "right when the key is pressed")
        session.onMidiInput(key(first.given.single() + 1))
        assertEquals(emptyList(), session.takeVerdicts(), "only the first key counts")

        val second = session.step()
        assertEquals(true, second.previousCorrect)
        assertEquals(listOf(StepVerdict(0, true)), session.takeVerdicts(), "the evaluation agrees")
        session.onMidiInput(key(second.given.single() + 2))
        assertEquals(listOf(StepVerdict(1, false)), session.takeVerdicts())
    }

    @Test
    fun aStepWithoutAnyKeyIsMissedNotJustWrong() {
        val session = session(mono)
        session.step()
        session.step()
        assertEquals(listOf(StepVerdict(0, correct = false, missed = true)), session.takeVerdicts())

        val late = session(mono)
        late.step(deferEvaluation = true)
        val second = late.step(deferEvaluation = true)
        late.takeVerdicts()
        late.expire(second.openStep!!)
        assertEquals(listOf(StepVerdict(0, correct = false, missed = true)), late.takeVerdicts(), "no late answer either")
    }

    @Test
    fun aLateAnswerIsJudgedWhenItComes() {
        val session = session(mono)
        val first = session.step(deferEvaluation = true)
        session.step(deferEvaluation = true)
        session.takeVerdicts()
        session.onMidiInput(key(first.given.single()))
        assertEquals(StepVerdict(0, true), session.takeVerdicts().last())
    }

    @Test
    fun twoVoicesAreJudgedWhenCertain() {
        val session = session(duo)
        val given = session.step().given
        session.takeVerdicts()
        session.onMidiInput(key(given[0]))
        if (given[0] != given[1]) {
            assertEquals(emptyList(), session.takeVerdicts(), "one of two notes: not certain yet")
            session.onMidiInput(key(given[1]))
        }
        assertEquals(listOf(StepVerdict(0, true)), session.takeVerdicts())

        val next = session.step().given
        session.takeVerdicts()
        session.onMidiInput(key(listOf(47, 73).first { it !in next }))
        assertEquals(listOf(StepVerdict(1, false)), session.takeVerdicts(), "a wrong note is certain at once")
    }
}
