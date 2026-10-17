package io.github.jonnyfrick.musicbootcamp.ui

import io.github.jonnyfrick.musicbootcamp.core.practice.PracticeStatus
import io.github.jonnyfrick.musicbootcamp.core.practice.StepVerdict
import kotlin.test.Test
import kotlin.test.assertEquals

/** Streak, recent results and the latest answer's keys follow the runner's statuses. */
class PracticeFeedbackTest {
    private fun PracticeFeedback.after(vararg statuses: PracticeStatus, played: List<Int> = emptyList()) =
        statuses.fold(this) { feedback, status -> feedback.next(status, played) }

    @Test
    fun theStreakCountsRightAnswersAndAWrongOneEndsIt() {
        val feedback = PracticeFeedback().after(
            PracticeStatus(steps = 1, given = listOf(60)),
            PracticeStatus(steps = 2, correct = 1, given = listOf(62)),
            PracticeStatus(steps = 3, correct = 2, given = listOf(64)),
            PracticeStatus(steps = 4, correct = 3, given = listOf(65)),
        )
        assertEquals(3, feedback.streak)
        val broken = feedback.after(PracticeStatus(steps = 5, correct = 3, wrong = 1))
        assertEquals(0, broken.streak)
        assertEquals(3, broken.best)
        assertEquals(listOf(true, true, true, false), broken.recent)
    }

    @Test
    fun aRightAnswerAfterAWrongOneInTheSameUpdateStartsANewStreak() {
        val feedback = PracticeFeedback().after(
            PracticeStatus(steps = 3, correct = 2),
            PracticeStatus(steps = 4, correct = 3, wrong = 1),
        )
        assertEquals(1, feedback.streak)
        assertEquals(listOf(true, true, false, true), feedback.recent)
    }

    @Test
    fun onlyTheLatestResultsAreKept() {
        val statuses = (1..20).map { PracticeStatus(steps = it, correct = it) }.toTypedArray()
        assertEquals(PracticeFeedback.RECENT, PracticeFeedback().after(*statuses).recent.size)
    }

    @Test
    fun aWrongAnswerKeepsTheNotesGivenInItsStepAndThePlayedOnes() {
        val feedback = PracticeFeedback()
            .after(PracticeStatus(steps = 1, given = listOf(60)), PracticeStatus(steps = 2, given = listOf(64)))
            // A late answer: step 0 is judged while step 1 already plays e'.
            .after(PracticeStatus(steps = 2, wrong = 1, given = listOf(64), lastResult = StepVerdict(0, false)), played = listOf(61))
        assertEquals(Answer(StepVerdict(0, false), given = listOf(60), played = listOf(61)), feedback.answer)
    }

    @Test
    fun nothingHeardHasNoPlayedKeys() {
        val feedback = PracticeFeedback().after(
            PracticeStatus(steps = 1, given = listOf(60)),
            PracticeStatus(steps = 2, wrong = 1, given = listOf(62), lastResult = StepVerdict(0, false, missed = true)),
            played = listOf(55),
        )
        assertEquals(Answer(StepVerdict(0, false, missed = true), given = listOf(60), played = emptyList()), feedback.answer)
    }

    @Test
    fun aNewRunStartsFromZeroButKeepsTheBestStreak() {
        val feedback = PracticeFeedback().after(PracticeStatus(steps = 3, correct = 3)).restarted()
        assertEquals(0, feedback.streak)
        assertEquals(3, feedback.best)
        assertEquals(1, feedback.after(PracticeStatus(steps = 1, correct = 1)).streak, "counters start again at zero")
    }
}
