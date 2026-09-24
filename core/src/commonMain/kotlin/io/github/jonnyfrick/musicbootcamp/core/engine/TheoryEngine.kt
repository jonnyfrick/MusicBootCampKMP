package io.github.jonnyfrick.musicbootcamp.core.engine

import io.github.jonnyfrick.musicbootcamp.core.learning.LearnedSequences
import io.github.jonnyfrick.musicbootcamp.core.model.PracticeSettings
import io.github.jonnyfrick.musicbootcamp.core.model.RandomSource
import io.github.jonnyfrick.musicbootcamp.core.model.SequenceElement
import kotlin.math.abs

/**
 * Decides which note(s) come next (Java: `TheoryEngine`).
 *
 * Each step either continues a memorised sequence, embeds a new one from
 * [memory] (with probability [PracticeSettings.learnedProbability]) or moves
 * every voice by a random interval weighted by [PracticeSettings.intervalPriorities].
 *
 * The order in which [random] is consumed is identical to the Java version, which
 * is what the golden master tests rely on.
 */
class TheoryEngine(
    private val settings: PracticeSettings,
    private val memory: LearnedSequences,
    private val random: RandomSource,
) {
    private var positions: IntArray = IntArray(settings.mode.voices) { settings.startPosition }
    private var changes = 0
    private var currentSequence: MutableList<SequenceElement> = mutableListOf()
    private var totallyFresh = true

    /** Semitone steps, each repeated as often as its priority says. */
    private val weightingTable: IntArray =
        settings.intervalPriorities.withIndex().flatMap { (i, weight) -> List(weight) { i + 1 } }.toIntArray()

    /** Never set in the Java version either, so the upper-limit check uses the note itself. */
    private val maxInterval = 0

    /** The note(s) currently given, one per voice. */
    fun positions(): List<Int> = positions.toList()

    fun changeNotes(voices: Int) {
        val normedRandom = random.nextInt(Int.MAX_VALUE).toDouble() / Int.MAX_VALUE.toDouble()
        val threshold = settings.learnedProbability

        if (currentSequence.isNotEmpty()) {
            changeNotesCurrentSequence()
            return
        }

        var changeRandom = false
        if (normedRandom > threshold || totallyFresh) {
            changeRandom = true
            totallyFresh = false
        } else if (embedLearnedSequence()) {
            changeNotesCurrentSequence()
        } else {
            changeRandom = true
        }
        if (changeRandom) changeNotesRandom(voices)
    }

    private fun changeNotesCurrentSequence() {
        when (val element = currentSequence.removeAt(0)) {
            is SequenceElement.Note -> positions[0] = element.pitch
            is SequenceElement.Chord -> element.pitches.forEachIndexed { voice, pitch ->
                // A chord wider than the voice count crashed the Java version; extra notes are dropped.
                if (voice < positions.size) positions[voice] = pitch
            }
        }
    }

    private fun changeNotesRandom(voices: Int) {
        if (changes == 0) {
            // The very first step plays the reference note.
            positions[0] = settings.startPosition
            changes++
            return
        }

        val next = IntArray(voices)
        if (positions.size == 1) {
            next.fill(positions[0])
        } else {
            positions.copyInto(next, endIndex = minOf(positions.size, voices))
        }

        for (voice in 0 until voices) {
            val randomNumber = random.nextInt()
            var offset = if (weightingTable.isEmpty()) {
                0 // Java divided by zero here; an all-zero interval list now simply repeats the note.
            } else {
                weightingTable[abs(randomNumber % weightingTable.size)]
            }
            if (randomNumber < 0) offset = -offset

            val current = next[voice]
            next[voice] = when {
                // Outside the range (e.g. after the range was narrowed) the Java reflection below
                // turns into an unbounded random walk that can drift far away; step back instead.
                current > settings.highLimit -> current - abs(offset)
                current < settings.lowLimit -> current + abs(offset)
                else -> {
                    var note = current + offset
                    if (note < settings.lowLimit) note -= offset * 2
                    if (note + maxInterval > settings.highLimit) note -= offset * 2
                    note
                }
            }
        }
        positions = next
    }

    private fun embedLearnedSequence(): Boolean {
        // The Java version built a list of the current pitches, but only its first entry was used.
        // Unlike Java, sequences reaching outside the current range are not played (they were
        // learned with another range); they stay in memory for when the range fits again.
        val range = settings.lowLimit..settings.highLimit
        val sequence = memory.takeAndDowngrade(positions[0], random) { sequence ->
            sequence.all { element -> element.pitches.all { it in range } }
        }
        if (sequence == null) {
            currentSequence = mutableListOf()
            return false
        }
        currentSequence = sequence.toMutableList()
        // The first element is the step that is sounding right now.
        if (currentSequence.isNotEmpty()) currentSequence.removeAt(0)
        return currentSequence.isNotEmpty()
    }
}
