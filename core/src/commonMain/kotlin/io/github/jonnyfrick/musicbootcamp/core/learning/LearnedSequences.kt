package io.github.jonnyfrick.musicbootcamp.core.learning

import io.github.jonnyfrick.musicbootcamp.core.model.LearnedSequence
import io.github.jonnyfrick.musicbootcamp.core.model.RandomSource
import io.github.jonnyfrick.musicbootcamp.core.model.legacyString

/**
 * Memory of sequences the player got wrong (Java: `LearnedSequencesDataStructure`,
 * `PrioritiesArrayList`, `OnePriorityArrayList`).
 *
 * Sequences are filed under the pitch of their first note and one of
 * [PRIORITY_LEVELS] levels, 0 being the most urgent. New mistakes enter at level 0;
 * every time a sequence is practised it moves one level down and drops out after
 * the last level.
 */
class LearnedSequences {

    /** [MIDI_KEYS] × [PRIORITY_LEVELS] buckets, each keeping insertion order. */
    private val buckets: List<List<MutableList<LearnedSequence>>> =
        List(MIDI_KEYS) { List(PRIORITY_LEVELS) { mutableListOf() } }

    var size: Int = 0
        private set

    fun isEmpty(): Boolean = size == 0

    /**
     * Stores [sequence] at [priority]. Sequences that are empty or start outside
     * the MIDI range are ignored (the Java version crashed on them).
     */
    fun insert(sequence: LearnedSequence, priority: Int) {
        require(priority in 0 until PRIORITY_LEVELS) { "Priority $priority out of range" }
        val key = sequence.firstOrNull()?.firstPitch ?: return
        if (key !in 0 until MIDI_KEYS) return
        buckets[key][priority].add(sequence)
        size++
    }

    /**
     * Picks a stored sequence starting at [firstPitch] (Java: `getAndDowngradeOneSequence`).
     *
     * First a priority level is drawn with [PriorityLevelPicker]; if that level holds
     * sequences for [firstPitch], one of them is taken at random and re-filed one level
     * lower (or forgotten after the lowest level). Returns null when the drawn level is
     * empty — the caller then falls back to a random step, as in the Java version.
     *
     * A drawn sequence that is not [playable] stays where it is and null is returned; the
     * random numbers consumed are the same either way.
     */
    fun takeAndDowngrade(
        firstPitch: Int,
        random: RandomSource,
        playable: (LearnedSequence) -> Boolean = { true },
    ): LearnedSequence? {
        val level = PriorityLevelPicker.pick(random)
        if (firstPitch !in 0 until MIDI_KEYS) return null
        val bucket = buckets[firstPitch][level]
        if (bucket.isEmpty()) return null
        val index = random.nextInt(bucket.size)
        if (!playable(bucket[index])) return null
        val sequence = bucket.removeAt(index)
        size--
        if (level + 1 < PRIORITY_LEVELS) insert(sequence, level + 1)
        return sequence
    }

    fun clear() {
        buckets.forEach { levels -> levels.forEach { it.clear() } }
        size = 0
    }

    /** Stored sequences per priority level (level 0 first), keys ascending within a level. */
    fun byPriority(): List<List<LearnedSequence>> =
        List(PRIORITY_LEVELS) { level -> buckets.flatMap { it[level] } }

    /** How many stored sequences match [predicate]. */
    fun count(predicate: (LearnedSequence) -> Boolean): Int = buckets.sumOf { levels -> levels.sumOf { it.count(predicate) } }

    /** How many sequences each priority level holds. */
    fun countsByPriority(): List<Int> = List(PRIORITY_LEVELS) { level -> buckets.sumOf { it[level].size } }

    /**
     * One line per sequence, `key|priority|[elements]`, in storage order. Identical to
     * what the Java golden master harness writes, which makes the two comparable.
     */
    fun canonicalText(): String = buildString {
        for (key in 0 until MIDI_KEYS) {
            for (level in 0 until PRIORITY_LEVELS) {
                for (sequence in buckets[key][level]) {
                    append(key).append('|').append(level).append('|').append(sequence.legacyString()).append('\n')
                }
            }
        }
    }

    companion object {
        const val MIDI_KEYS = 128
        const val PRIORITY_LEVELS = 5

        /** Rebuilds a memory from [byPriority] output. */
        fun fromPriorityLevels(levels: List<List<LearnedSequence>>): LearnedSequences = LearnedSequences().apply {
            levels.forEachIndexed { level, sequences ->
                if (level < PRIORITY_LEVELS) sequences.forEach { insert(it, level) }
            }
        }
    }
}

/**
 * Draws a priority level with exponentially growing weights: level 0 is the most
 * likely (Java: `LearnedSequencesPrioritizer`).
 */
internal object PriorityLevelPicker {

    /** round(1.5^i) for i = 0..4 → [1, 2, 2, 3, 5]. */
    private val weights: IntArray = run {
        var weight = 1.0
        IntArray(LearnedSequences.PRIORITY_LEVELS) {
            val rounded = kotlin.math.floor(weight + 0.5).toInt()
            weight *= 1.5
            rounded
        }
    }

    /** Each weight index i+1 repeated weights[i] times. */
    private val weightingTable: IntArray = weights.withIndex().flatMap { (i, w) -> List(w) { i + 1 } }.toIntArray()

    fun pick(random: RandomSource): Int {
        val index = random.nextInt(weightingTable.size)
        // The heaviest weight belongs to the last index, which maps to level 0.
        return weights.size - weightingTable[index]
    }
}
