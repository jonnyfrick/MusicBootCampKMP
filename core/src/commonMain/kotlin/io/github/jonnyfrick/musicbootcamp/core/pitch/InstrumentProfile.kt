package io.github.jonnyfrick.musicbootcamp.core.pitch

/**
 * What the recognition has to know about the instrument the player answers on. It was built for
 * the piano: a struck string is in tune at once, dies away, and its partials are stretched.
 * A blown note scoops into its pitch, is held, changes pitch without a new attack (legato), has
 * exactly harmonic partials, and some instruments lack partials a piano always has.
 */
data class InstrumentProfile(
    /** How long after an attack the pitch is not trusted yet (a blown note scoops into it). */
    val settleMillis: Int,
    /** Analysis windows that must agree on a note; more for tones that waver at the start. */
    val confirmFrames: Int?,
    /**
     * The tone is held at its level: a second detection of the same note shortly after the
     * first belongs to the same (slowly swelling) attack, and a change of pitch without a new
     * attack is a new note.
     */
    val sustained: Boolean,
    /** Partials stretched like a piano string's; else exactly harmonic. */
    val stretchedPartials: Boolean,
    /**
     * For chords: which of a note's low partials (1–6) must be there for the note to be
     * plausible. Null: the piano's rule (2–6, and the fundamental from D3 up).
     */
    val neededPartials: List<Int>?,
    /** For chords: where the analysis window starts after the attack; later for scooping notes. */
    val chordWindowStartMillis: Int?,
    /** For chords: attacks this close together are one chord; wind players never start exactly together. */
    val chordSpreadMillis: Int?,
) {
    companion object {
        val PIANO = InstrumentProfile(
            settleMillis = 35, confirmFrames = null, sustained = false, stretchedPartials = true,
            neededPartials = null, chordWindowStartMillis = null, chordSpreadMillis = null,
        )

        /** Brass, saxophones, oboe and other held tones rich in partials, all of them present. */
        val WIND = InstrumentProfile(
            settleMillis = 90, confirmFrames = 4, sustained = true, stretchedPartials = false,
            neededPartials = listOf(1, 2, 3, 4), chordWindowStartMillis = 100, chordSpreadMillis = 150,
        )

        /** The clarinet's tube sounds mostly odd partials. */
        val CLARINET = WIND.copy(neededPartials = listOf(1, 3))

        /** A flute, softly blown, is little more than its fundamental and the octave. */
        val FLUTE = WIND.copy(neededPartials = listOf(1))
    }
}
