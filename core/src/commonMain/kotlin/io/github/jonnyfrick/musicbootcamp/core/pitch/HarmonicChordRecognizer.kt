package io.github.jonnyfrick.musicbootcamp.core.pitch

import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Tells chords apart by **where** partials are, not how strong: the room and the microphone's
 * position change the strength of single partials by many dB, but not their frequencies.
 *
 * 1. The spectrum is divided by its envelope (a moving average over
 *    [ChordDetectionParameters.whitenSemitones]), which levels out the room's colouring; a
 *    partial then counts as present if it stands [ChordDetectionParameters.presence] times above
 *    its surroundings.
 * 2. A chord explains the peaks that fall on its notes' partials (stretched by inharmonicity);
 *    a peak shared by two notes (octave, fifth) counts once. Its score is the share of all peaks'
 *    strength it explains beyond chance (the share of the spectrum its partials cover: a deep
 *    note's dense comb would otherwise catch many peaks) …
 * 3. … minus [ChordDetectionParameters.missingPenalty] for every partial it needs that is not
 *    there. That settles octaves without strengths: C3 needs its odd partials (C3, G4, E5), which
 *    C4 does not have. Which partials a note needs: the ones a calibration found present, else
 *    partials 2–6 (and the fundamental from D3 up; deep in the bass it is often too weak).
 */
class HarmonicChordRecognizer(
    private val range: IntRange,
    private val parameters: ChordDetectionParameters = ChordDetectionParameters(),
    learned: LearnedTemplates = LearnedTemplates(),
    private val instrument: InstrumentProfile = InstrumentProfile.PIANO,
) : ChordRecognizer {
    /**
     * Per calibrated note, which of its low partials (1–6, as by default) were clearly present —
     * e.g. no fundamental deep in the bass. Only which, not how strong (that depends on the room).
     */
    private val reliable: Map<Int, Set<Int>> = learned.notes.mapValues { (note, spectrum) ->
        val levelled = whiten(spectrum.toDoubleArray())
        (1..minOf(RELIABLE_PARTIALS, parameters.partials))
            .filter { k -> partialBin(note, k)?.let { strengthAt(levelled, it, k) >= parameters.presence } == true }.toSet()
    }

    override fun recognize(spectrum: DoubleArray, voices: Int, expected: List<List<Int>>): RecognizedChord? {
        val levelled = whiten(spectrum)
        val low = LogSpectrum.binOfNote(range.first) - LogSpectrum.BINS_PER_SEMITONE
        val high = minOf(LogSpectrum.SIZE - 2, LogSpectrum.binOfNote(TOP_NOTE))
        // Clear peaks and how far each stands out.
        val peaks = (maxOf(1, low)..high).filter { i ->
            levelled[i] >= parameters.presence && levelled[i] >= levelled[i - 1] && levelled[i] >= levelled[i + 1]
        }
        val strength = peaks.associateWith { levelled[it] - 1 }
        val total = strength.values.sum()
        if (total <= 0) return null

        // How much each note's partials stand out: the notes worth combining.
        val salience = range.associateWith { note ->
            (1..parameters.partials).sumOf { k -> partialBin(note, k)?.let { (strengthAt(levelled, it, k) - 1).coerceAtLeast(0.0) / k.toDouble().pow(0.5) } ?: 0.0 }
        }
        val ranked = salience.entries.sortedByDescending { it.value }
        val activationTotal = ranked.sumOf { it.value }.takeIf { it > 0 } ?: 1.0
        val activations = ranked.filter { it.value > 0 }.map { it.key to it.value / activationTotal }

        val expectedSets = expected.map { it.distinct().sorted() }.toSet()
        val missingOf = mutableMapOf<List<Int>, Int>()
        val scored = chordHypotheses(activations.take(STRONGEST).map { it.first }, voices, expected, range).map { notes ->
            val explained = peaks.filter { peak -> notes.any { note -> explains(note, peak) } }.sumOf { strength.getValue(it) } / total
            // A deep note's dense comb of partials would catch many peaks by chance: only what it
            // explains beyond the share of the spectrum it covers counts.
            val covered = (low..high).count { bin -> notes.any { note -> explains(note, bin) } }.toDouble() / (high - low + 1)
            val missing = notes.sumOf { note ->
                needed(note).count { k -> partialBin(note, k)?.let { strengthAt(levelled, it, k) < parameters.presence } ?: false }
            }
            missingOf[notes] = missing
            val bias = if (notes in expectedSets) parameters.givenBias else 0.0
            val score = -(1 + parameters.unexplainedPenalty) * (explained - parameters.chanceWeight * covered) + parameters.missingPenalty * missing +
                parameters.notePenalty * notes.size - bias
            notes to score
        }.sortedBy { it.second }
        var best = scored.firstOrNull() ?: return null
        // A note lying entirely on the partials of another (a twelfth, a double octave) leaves no
        // trace of its own without strengths. If an expected chord is the best chord plus such
        // notes — all their partials there, nothing but the note penalty lost — take the expected one.
        val scores = scored.toMap()
        expectedSets.filter { it.size > best.first.size && it.containsAll(best.first) && it in scores }.forEach { chord ->
            val extraCost = parameters.notePenalty * (chord.size - best.first.size)
            if (missingOf[chord] == missingOf[best.first] && scores.getValue(chord) - best.second <= extraCost + 1e-9) best = chord to scores.getValue(chord)
        }
        val runnerUp = scored.firstOrNull { it.first != best.first }
        return RecognizedChord(best.first, best.second, runnerUp?.first, runnerUp?.second, activations)
    }

    /**
     * The partials a note must show: those a calibration found, else the instrument's, else the
     * piano's 2–6 (and 1 from D3 up).
     */
    private fun needed(note: Int): Collection<Int> =
        reliable[note] ?: instrument.neededPartials
            ?: ((if (note >= FUNDAMENTAL_FROM) 1 else 2)..minOf(RELIABLE_PARTIALS, parameters.partials)).toList()

    /** Whether the peak at [bin] lies on one of [note]'s partials. */
    private fun explains(note: Int, bin: Int): Boolean =
        (1..parameters.partials).any { k -> partialBin(note, k)?.let { kotlin.math.abs(bin - it) <= tolerance(k) } == true }

    /** The strongest levelled value around partial [k] of a note (at [bin]). */
    private fun strengthAt(levelled: DoubleArray, bin: Int, k: Int): Double {
        var best = 0.0
        for (i in bin - tolerance(k)..bin + tolerance(k)) if (i in levelled.indices) best = max(best, levelled[i])
        return best
    }

    /** ±1 bin (a third of a semitone), a little more for high partials (inharmonicity varies). */
    private fun tolerance(k: Int) = 1 + k / 6

    /** The log bin of partial [k] of [note], stretched by a typical inharmonicity; null above the analysed range. */
    private fun partialBin(note: Int, k: Int): Int? {
        // Piano strings are stiff, which stretches their partials; a blown column of air is exactly harmonic.
        val b = if (instrument.stretchedPartials) 0.0001 * 2.0.pow((note - 21) / 20.0) else 0.0
        val bin = LogSpectrum.binOfNote(note) + LogSpectrum.binsFor(k * sqrt(1 + b * k * k))
        return bin.roundToInt().takeIf { it < LogSpectrum.binOfNote(TOP_NOTE) }
    }

    /** The spectrum divided by its moving-average envelope (with a floor, so silence is not blown up). */
    private fun whiten(spectrum: DoubleArray): DoubleArray {
        val half = (parameters.whitenSemitones * LogSpectrum.BINS_PER_SEMITONE / 2).roundToInt().coerceAtLeast(1)
        val prefix = DoubleArray(spectrum.size + 1)
        for (i in spectrum.indices) prefix[i + 1] = prefix[i] + spectrum[i]
        val envelope = DoubleArray(spectrum.size) { i ->
            val from = maxOf(0, i - half)
            val to = minOf(spectrum.size, i + half + 1)
            (prefix[to] - prefix[from]) / (to - from)
        }
        val floor = (envelope.maxOrNull() ?: 0.0) * FLOOR
        return DoubleArray(spectrum.size) { i -> spectrum[i] / (envelope[i] + floor + 1e-12) }
    }

    private companion object {
        const val STRONGEST = 6
        const val TOP_NOTE = 111 // about 5 kHz: above, partials are weak and dense
        const val RELIABLE_PARTIALS = 6
        const val FUNDAMENTAL_FROM = 50
        const val FLOOR = 0.03
    }
}
