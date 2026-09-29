package io.github.jonnyfrick.musicbootcamp.ui

import io.github.jonnyfrick.musicbootcamp.core.practice.PracticeStatus
import io.github.jonnyfrick.musicbootcamp.core.practice.StepVerdict
import kotlin.test.Test
import kotlin.test.assertEquals

/** The Practice screen shows each result right away, and "listening" whenever a step waits for its answer. */
class LiveResultTest {
    private fun status(steps: Int, result: StepVerdict?) = PracticeStatus(running = true, steps = steps, lastResult = result)

    @Test
    fun anAnswerShowsAtOnceAndStaysUntilTheNextStep() {
        // Step 2 (0-based) is the current one and was just answered.
        val answered = status(steps = 3, result = StepVerdict(2, true))
        assertEquals(StepVerdict(2, true), shownResult(answered, running = true, holding = true))
        assertEquals(StepVerdict(2, true), shownResult(answered, running = true, holding = false), "nothing waits")
        val nextStep = answered.copy(steps = 4)
        assertEquals(StepVerdict(2, true), shownResult(nextStep, running = true, holding = true), "shown at least for a moment")
        assertEquals(null, shownResult(nextStep, running = true, holding = false), "then listening for step 3")
    }

    @Test
    fun aLateOrMissedAnswerShowsBrieflyThenListening() {
        val late = status(steps = 5, result = StepVerdict(3, false))
        assertEquals(StepVerdict(3, false), shownResult(late, running = true, holding = true))
        assertEquals(null, shownResult(late, running = true, holding = false))
    }

    @Test
    fun nothingBeforeTheFirstAnswerOrAfterStopping() {
        assertEquals(null, shownResult(status(steps = 1, result = null), running = true, holding = false))
        assertEquals(null, shownResult(status(steps = 3, result = StepVerdict(2, true)), running = false, holding = true))
    }
}
