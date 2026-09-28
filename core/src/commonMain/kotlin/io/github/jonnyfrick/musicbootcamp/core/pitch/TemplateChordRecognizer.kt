package io.github.jonnyfrick.musicbootcamp.core.pitch

/** What [TemplateChordRecognizer] decided for one stroke. */
data class RecognizedChord(
    /** The distinct notes, lowest first (fewer than the voices for a unison or a missing note). */
    val notes: List<Int>,
    /** Relative misfit plus penalties; lower is better. */
    val score: Double,
    /** The runner-up and its score, to see how clear the decision was. */
    val alternative: List<Int>?,
    val alternativeScore: Double?,
    /** Every note's share of a fit with all templates at once, strongest first (for analysis). */
    val activations: List<Pair<Int, Double>>,
)

/**
 * Tells which notes a piano chord consists of, from its magnitude spectrum on the log axis.
 *
 * Every hypothesis (a set of notes) is fitted as a non-negative mix of the notes' templates;
 * its score is the part of the spectrum it leaves unexplained, plus [ChordDetectionParameters.notePenalty]
 * per note. The hypotheses are not all combinations of notes (that grows too fast with more
 * voices and a wider range) but:
 * - the chords the player is expected to play (the given ones), and their near misses: each note
 *   one or two semitones or an octave off, or left out;
 * - combinations of the strongest notes of a fit with all templates of the range at once.
 * So the work grows about linearly with the voices and the range.
 */
class TemplateChordRecognizer(
    private val templates: PianoTemplates,
    private val range: IntRange,
    private val parameters: ChordDetectionParameters = ChordDetectionParameters(),
) {
    private val dots = mutableMapOf<Long, Double>()

    private fun dot(a: Int, b: Int): Double {
        val key = minOf(a, b).toLong() * 1000 + maxOf(a, b)
        return dots.getOrPut(key) {
            val x = templates.template(a)
            val y = templates.template(b)
            var sum = 0.0
            for (i in x.indices) sum += x[i] * y[i]
            sum
        }
    }

    /**
     * The chord of up to [voices] notes that best explains [spectrum], preferring nothing but
     * the simpler explanation; [expected] are the chords the player may be answering right now.
     */
    fun recognize(spectrum: DoubleArray, voices: Int, expected: List<List<Int>>): RecognizedChord? {
        val energy = spectrum.sumOf { it * it }
        if (energy <= 0.0) return null
        val projections = mutableMapOf<Int, Double>()
        fun projection(note: Int) = projections.getOrPut(note) {
            val t = templates.template(note)
            var sum = 0.0
            for (i in t.indices) sum += t[i] * spectrum[i]
            sum
        }

        // All templates at once: which notes the spectrum seems to hold.
        val all = range.toList()
        val allFit = fit(all, ::projection)
        val shares = all.zip(allFit.toList()).filter { it.second > 0 }.sortedByDescending { it.second }
        val total = shares.sumOf { it.second * it.second }
        val activations = shares.map { it.first to it.second * it.second / total }

        val hypotheses = mutableSetOf<List<Int>>()
        val strongest = activations.take(STRONGEST).map { it.first }
        for (size in 1..voices) subsets(strongest, size).forEach { hypotheses += it.sorted() }
        val expectedSets = expected.map { it.distinct().sorted() }.filter { it.isNotEmpty() }.toSet()
        for (chord in expectedSets) {
            hypotheses += chord
            for (i in chord.indices) {
                if (chord.size > 1) hypotheses += (chord - chord[i]).sorted()
                for (step in NEAR_MISSES) {
                    val moved = chord[i] + step
                    if (moved in LogSpectrum.LOWEST_NOTE..LogSpectrum.HIGHEST_NOTE - 12 && moved !in chord) {
                        hypotheses += (chord - chord[i] + moved).sorted()
                    }
                }
            }
        }

        val scored = hypotheses.mapNotNull { notes ->
            val x = fit(notes, ::projection)
            val strength = x.sumOf { it * it }
            if (strength <= 0 || x.any { it * it < parameters.minNoteShare * strength }) return@mapNotNull null
            val misfit = Nnls.residual(gram(notes), DoubleArray(notes.size) { projection(notes[it]) }, energy, x) / energy
            val bias = if (notes in expectedSets) parameters.givenBias else 0.0
            notes to misfit + parameters.notePenalty * notes.size - bias
        }.sortedBy { it.second }
        val best = scored.firstOrNull() ?: return null
        val runnerUp = scored.getOrNull(1)
        return RecognizedChord(best.first, best.second, runnerUp?.first, runnerUp?.second, activations)
    }

    private fun gram(notes: List<Int>) = Array(notes.size) { i -> DoubleArray(notes.size) { j -> dot(notes[i], notes[j]) } }

    private fun fit(notes: List<Int>, projection: (Int) -> Double): DoubleArray =
        Nnls.solve(gram(notes), DoubleArray(notes.size) { projection(notes[it]) })

    private fun subsets(items: List<Int>, size: Int): List<List<Int>> = when {
        size == 0 -> listOf(emptyList())
        items.size < size -> emptyList()
        else -> subsets(items.drop(1), size - 1).map { listOf(items[0]) + it } + subsets(items.drop(1), size)
    }

    private companion object {
        const val STRONGEST = 6
        val NEAR_MISSES = listOf(-12, -2, -1, 1, 2, 12)
    }
}
