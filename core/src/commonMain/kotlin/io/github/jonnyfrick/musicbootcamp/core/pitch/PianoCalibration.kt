package io.github.jonnyfrick.musicbootcamp.core.pitch

/**
 * Learns the player's piano for the chord recognition: asks for [notes] one after the other and
 * keeps the spectrum of each stroke, measured exactly as a chord is ([ChordTracker] with one
 * voice). A stroke counts for the asked note only if it sounds like it (else it is asked again),
 * so a slip of the finger does not end up as a template.
 */
class PianoCalibration(
    sampleRate: Int,
    private val notes: List<Int>,
    referenceAHz: Double,
    parameters: ChordDetectionParameters = ChordDetectionParameters(),
    previous: LearnedTemplates = LearnedTemplates(),
) {
    private val learned = previous.notes.toMutableMap()
    private var index = 0
    private var lastSpectrum: DoubleArray? = null

    private val tracker = ChordTracker(
        // A wide range, so a slip is heard as the note it was and not as a neighbour of the asked one.
        sampleRate, 1, notes.min() - 12..notes.max() + 12, referenceAHz, parameters, expected = { listOfNotNull(target?.let(::listOf)) },
    ).also { tracker -> tracker.spectrumTrace = { _, spectrum -> lastSpectrum = spectrum } }

    /** The note to play now; null when all are done. */
    val target: Int? get() = notes.getOrNull(index)

    /** What the last stroke sounded like, if it was not the asked note. */
    var lastHeard: List<Int>? = null
        private set

    val done: Int get() = index
    val total: Int get() = notes.size

    val level: Double get() = tracker.level

    /** Feeds microphone samples; true if a note was learned in them. */
    fun process(samples: FloatArray): Boolean {
        val asked = target ?: return false
        var learnedOne = false
        for (chord in tracker.process(samples)) {
            val spectrum = lastSpectrum ?: continue
            // The asked note — or its pitch class: the piano model often misjudges the octave of a
            // real piano (weak bass fundamentals), which is exactly what the calibration is for.
            val strongest = chord.details.activations.firstOrNull()
            val sameClass = chord.notes.isNotEmpty() && chord.notes.all { (it - asked) % 12 == 0 }
            if (sameClass || strongest != null && (strongest.first - asked) % 12 == 0 && strongest.second > DOMINANT) {
                learned[asked] = spectrum.toList()
                lastHeard = null
                index++
                learnedOne = true
                if (target == null) break
            } else {
                lastHeard = chord.notes
            }
        }
        return learnedOne
    }

    /** The templates learned so far (with those from before that were not played again). */
    fun result(): LearnedTemplates = LearnedTemplates(notes = learned.toMap())

    private companion object {
        const val DOMINANT = 0.6
    }
}
