package io.github.jonnyfrick.musicbootcamp.core.midi

/** Note names in German/Helmholtz octave notation, as shown by the Java range dialog. */
object NoteNames {

    private val PITCH_CLASSES = listOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "G#", "A", "Bb", "B")

    /**
     * Exactly the Java `translateLimit` text, e.g. `"great C"`, `" A'"` (note the
     * leading space when there is no octave word).
     */
    fun legacyName(midiNote: Int): String {
        var prefix = ""
        var suffix = ""
        when {
            midiNote < 24 -> prefix = "sub-contra"
            midiNote < 36 -> prefix = "contra"
            midiNote < 48 -> prefix = "great"
            midiNote < 60 -> prefix = "small"
            midiNote < 72 -> suffix = "'"
            midiNote < 84 -> suffix = "''"
            midiNote < 96 -> suffix = "'''"
            midiNote < 108 -> suffix = "''''"
            midiNote == 108 -> suffix = "'''''"
        }
        return "$prefix ${PITCH_CLASSES[midiNote.mod(12)]}$suffix"
    }

    /** [legacyName] without the stray leading space, for display. */
    fun displayName(midiNote: Int): String = legacyName(midiNote).trim()
}
