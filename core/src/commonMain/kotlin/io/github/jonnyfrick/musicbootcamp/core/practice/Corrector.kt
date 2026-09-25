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

    /** The remembered steps in the order they are stored as a learned sequence. */
    fun predecessors(): LearnedSequence

    companion object {
        fun forMode(mode: PracticeMode, memorySize: Int): Corrector = when (mode) {
            PracticeMode.MONOPHONIC -> SingleNoteCorrector(memorySize)
            PracticeMode.TWO_VOICES_PURE_RANDOM -> TwoVoicesCorrector(memorySize)
            PracticeMode.HOMOPHONIC_MODES -> error("Homophonic modes are not implemented")
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

    override fun predecessors(): LearnedSequence = predecessors.map { SequenceElement.Chord(it) }
}
