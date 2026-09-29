package io.github.jonnyfrick.musicbootcamp.core.practice

import io.github.jonnyfrick.musicbootcamp.core.model.LearnedSequence
import io.github.jonnyfrick.musicbootcamp.core.model.PracticeMode
import io.github.jonnyfrick.musicbootcamp.core.model.SequenceElement

/**
 * Compares what was given with what the player played and remembers the
 * preceding steps so a mistake can be stored as a sequence (Java: `Corrector`).
 */
sealed interface Corrector {
    fun addGiven(note: Int)
    fun resetGiven()
    fun addRecorded(note: Int)
    fun resetRecorded()

    /** Whether enough key presses were recorded that further ones would not count for this step. */
    fun hasAnswer(): Boolean

    /** Evaluates the step that just ended and updates the predecessor memory. */
    fun correct(): Boolean

    /**
     * What [correct] will say about the key presses so far, as soon as that is certain, without
     * changing anything; null while it still depends on further presses.
     */
    fun verdict(): Boolean?

    /** The remembered steps in the order they are stored as a learned sequence. */
    fun predecessors(): LearnedSequence

    companion object {
        fun forMode(mode: PracticeMode, memorySize: Int): Corrector = when (mode) {
            PracticeMode.MONOPHONIC -> SingleNoteCorrector(memorySize)
            PracticeMode.TWO_VOICES_PURE_RANDOM -> TwoVoicesCorrector(memorySize)
            PracticeMode.HOMOPHONIC_MODES -> error("Homophonic modes are not implemented")
        }

        /** The [verdict] on [answer] to a step that gave [given]. */
        fun judge(mode: PracticeMode, given: List<Int>, answer: List<Int>): Boolean? = forMode(mode, 1).run {
            if (mode.voices == 1) addGiven(given[0]) else given.forEach(::addGiven)
            resetRecorded()
            answer.forEach(::addRecorded)
            verdict()
        }
    }
}

/** Java: `PracticeSingleNoteCorrector`. Predecessors are kept oldest first. */
class SingleNoteCorrector(private val memorySize: Int) : Corrector {
    private var given = 0
    private var recorded = 0
    private val predecessors = mutableListOf<Int>()

    override fun addGiven(note: Int) {
        given = note
    }

    override fun resetGiven() {
        given = 0
    }

    /** Only the first key press after [resetRecorded] counts. */
    override fun addRecorded(note: Int) {
        if (recorded < 0) recorded = note
    }

    override fun resetRecorded() {
        recorded = -1
    }

    override fun hasAnswer(): Boolean = recorded >= 0

    override fun correct(): Boolean {
        if (given != 0) predecessors.add(given)
        if (predecessors.size > memorySize) predecessors.removeAt(0)
        return recorded == given
    }

    override fun verdict(): Boolean? = if (recorded >= 0) recorded == given else null

    override fun predecessors(): LearnedSequence = predecessors.map { SequenceElement.Note(it) }
}

/**
 * Java: `PracticeTwoVoicesPureRandomCorrector`. Predecessors are kept newest
 * first — that is how the Java version stored two-voice mistakes, so the
 * learned sequences keep that order.
 */
class TwoVoicesCorrector(private val memorySize: Int) : Corrector {
    private val given = mutableListOf<Int>()
    private val recorded = mutableListOf<Int>()
    private val predecessors = mutableListOf<List<Int>>()

    override fun addGiven(note: Int) {
        given.add(note)
    }

    override fun resetGiven() {
        given.clear()
    }

    /** The first two key presses count. */
    override fun addRecorded(note: Int) {
        if (recorded.size < 2) recorded.add(note)
    }

    override fun resetRecorded() {
        recorded.clear()
    }

    override fun hasAnswer(): Boolean = recorded.size >= 2

    override fun correct(): Boolean {
        if (given.isEmpty()) return true // nothing was given before the first step
        predecessors.add(0, given.toList())
        if (predecessors.size > memorySize) predecessors.removeAt(predecessors.size - 1)

        if (recorded.isEmpty()) return false
        // A single key press counts for both voices (unison).
        if (recorded.size == 1) recorded.add(recorded[0])

        for (note in given) {
            if (!recorded.remove(note)) {
                recorded.clear()
                return false
            }
        }
        recorded.clear()
        return true
    }

    /** Certain after two presses, or after one that is wrong or answers a unison. */
    override fun verdict(): Boolean? = when {
        given.isEmpty() || recorded.isEmpty() -> null
        recorded.size >= 2 -> given.toMutableList().let { open -> recorded.all { open.remove(it) } }
        recorded[0] !in given -> false
        given.all { it == recorded[0] } -> true
        else -> null
    }

    override fun predecessors(): LearnedSequence = predecessors.map { SequenceElement.Chord(it) }
}
