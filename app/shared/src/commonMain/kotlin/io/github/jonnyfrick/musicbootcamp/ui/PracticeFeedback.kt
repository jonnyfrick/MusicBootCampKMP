package io.github.jonnyfrick.musicbootcamp.ui

import io.github.jonnyfrick.musicbootcamp.core.practice.PracticeStatus
import io.github.jonnyfrick.musicbootcamp.core.practice.StepVerdict

/** A judged step as keys: what the app gave and what the player played (empty: nothing heard). */
internal data class Answer(val verdict: StepVerdict, val given: List<Int>, val played: List<Int>)

/**
 * What the practice screen shows beyond the runner's counters, folded from its successive
 * statuses: the run of right answers, the latest results in order and the newest judged answer.
 * The streak follows the counters, so a verdict the status skipped over still counts.
 */
internal data class PracticeFeedback(
    val streak: Int = 0,
    /** The longest streak since the app started. */
    val best: Int = 0,
    /** The latest results, oldest first, at most [RECENT]: true = right. */
    val recent: List<Boolean> = emptyList(),
    val answer: Answer? = null,
    private val correct: Int = 0,
    private val wrong: Int = 0,
    private val steps: Int = 0,
    /** The notes given in the latest steps (0-based, like [StepVerdict.step]). */
    private val givenByStep: Map<Int, List<Int>> = emptyMap(),
) {
    /** For a new run: everything back to zero but the best streak. */
    fun restarted() = PracticeFeedback(best = best)

    /** After [status]; [played]: the newest notes the player struck, oldest first. */
    fun next(status: PracticeStatus, played: List<Int>): PracticeFeedback {
        val given = if (status.steps > steps && status.steps > 0) {
            (givenByStep + (status.steps - 1 to status.given)).filterKeys { it >= status.steps - GIVEN_STEPS }
        } else {
            givenByStep
        }
        val newWrong = (status.wrong - wrong).coerceAtLeast(0)
        val newCorrect = (status.correct - correct).coerceAtLeast(0)
        // Both in one update: the order is unknown; the right answer is taken as the newer one.
        val streak = if (newWrong > 0) newCorrect else streak + newCorrect
        val recent = (recent + List(newWrong) { false } + List(newCorrect) { true }).takeLast(RECENT)
        val verdict = status.lastResult
        val answer = if (verdict != null && verdict != answer?.verdict) {
            Answer(verdict, given[verdict.step] ?: status.given, if (verdict.missed) emptyList() else played)
        } else {
            answer
        }
        return copy(
            streak = streak, best = maxOf(best, streak), recent = recent, answer = answer,
            correct = status.correct, wrong = status.wrong, steps = status.steps, givenByStep = given,
        )
    }

    companion object {
        const val RECENT = 14
        private const val GIVEN_STEPS = 8
    }
}
