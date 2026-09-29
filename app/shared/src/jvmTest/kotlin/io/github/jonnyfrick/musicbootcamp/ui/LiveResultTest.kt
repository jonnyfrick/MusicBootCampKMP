package io.github.jonnyfrick.musicbootcamp.ui

import io.github.jonnyfrick.musicbootcamp.core.practice.PracticeStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/** The Practice screen shows each result, and "listening" whenever a step waits for its answer. */
class LiveResultTest {
    private fun status(steps: Int, correct: Int, wrong: Int, last: Boolean?) =
        PracticeStatus(running = true, steps = steps, correct = correct, wrong = wrong, lastCorrect = last)

    @Test
    fun aResultStaysUntilTheNextStepThenListening() {
        val answered = status(steps = 3, correct = 3, wrong = 0, last = true)
        assertEquals(true, shownResult(answered, running = true, holding = true))
        assertEquals(true, shownResult(answered, running = true, holding = false), "answered: nothing to wait for")
        val nextStep = answered.copy(steps = 4)
        assertEquals(true, shownResult(nextStep, running = true, holding = true), "shown at least for a moment")
        assertEquals(null, shownResult(nextStep, running = true, holding = false), "then listening, before the next right answer")
    }

    @Test
    fun aLateOrMissedAnswerShowsBrieflyThenListening() {
        // Evaluated together with (or after) the next step: the next one is already waiting.
        val late = status(steps = 5, correct = 2, wrong = 2, last = false)
        assertEquals(false, shownResult(late, running = true, holding = true))
        assertEquals(null, shownResult(late, running = true, holding = false))
    }

    @Test
    fun nothingBeforeTheFirstAnswerOrAfterStopping() {
        assertEquals(null, shownResult(status(steps = 1, correct = 0, wrong = 0, last = null), running = true, holding = false))
        assertEquals(null, shownResult(status(steps = 3, correct = 3, wrong = 0, last = true), running = false, holding = true))
    }
}
