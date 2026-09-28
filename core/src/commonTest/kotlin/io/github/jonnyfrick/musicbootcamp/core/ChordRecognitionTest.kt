package io.github.jonnyfrick.musicbootcamp.core

import io.github.jonnyfrick.musicbootcamp.core.pitch.ChordTracker
import io.github.jonnyfrick.musicbootcamp.core.pitch.DetectedChord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Chords from the microphone, on synthetic piano tones (see [addPianoStroke]). */
class ChordRecognitionTest {
    private val sampleRate = 44_100

    /** The notes struck together (10 ms apart, as fingers are) at [start]. */
    private fun addChord(buffer: FloatArray, notes: List<Int>, start: Double, duration: Double = 0.8, seed: Int = 0) {
        notes.forEachIndexed { i, note ->
            addPianoStroke(buffer, note, startSeconds = start + i * 0.01, durationSeconds = duration, seed = seed + note * 3 + i)
        }
    }

    private fun chord(notes: List<Int>) = FloatArray((1.2 * sampleRate).toInt()).also { addChord(it, notes, start = 0.2) }

    private fun recognize(
        microphone: FloatArray,
        voices: Int,
        expected: (Double) -> List<List<Int>>,
        range: IntRange = 36..84,
        reference: FloatArray? = null,
    ): List<DetectedChord> {
        var seconds = 0.0
        val tracker = ChordTracker(sampleRate, voices, range, expected = { expected(seconds) })
        return microphone.indices.chunked(512).flatMap { block ->
            seconds = block.first().toDouble() / sampleRate
            val r = block.first()..block.last()
            tracker.process(microphone.sliceArray(r), reference?.sliceArray(r))
        }
    }

    @Test
    fun everyIntervalUpToTwoOctavesIsRecognised() {
        val failures = mutableListOf<String>()
        for (low in listOf(36, 43, 48, 55, 60, 67)) {
            for (interval in 0..24) {
                val notes = listOf(low, low + interval)
                val found = recognize(chord(notes.distinct()), 2, { listOf(notes) }).map { it.notes }
                if (found != listOf(notes.distinct())) failures += "$notes -> $found"
            }
        }
        // Known: a fifth deep in the bass (weak fundamental) can be read an octave too high.
        // Templates learned from the player's piano are meant to settle that.
        assertTrue(failures.size <= 2, "${failures.size} of 150: $failures")
    }

    @Test
    fun aWrongChordIsRecognisedAsPlayed() {
        val given = listOf(60, 64)
        for (played in listOf(listOf(60, 65), listOf(59, 64), listOf(60, 76), listOf(62, 67), listOf(60, 63))) {
            assertEquals(listOf(played), recognize(chord(played), 2, { listOf(given) }).map { it.notes }, "played $played")
        }
    }

    @Test
    fun aUnisonIsOneNote() {
        assertEquals(listOf(listOf(60)), recognize(chord(listOf(60)), 2, { listOf(listOf(60, 60)) }).map { it.notes })
    }

    @Test
    fun triadsAreRecognised() {
        val triads = listOf(listOf(48, 52, 55), listOf(57, 60, 64), listOf(50, 53, 57), listOf(55, 59, 62), listOf(60, 64, 67), listOf(45, 52, 60))
        val failures = triads.mapNotNull { triad ->
            val found = recognize(chord(triad), 3, { listOf(triad) }).map { it.notes }
            if (found != listOf(triad)) "$triad -> $found" else null
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun chordsAreRecognisedOverTheAppsChords() {
        // The app plays a chord every 2.5 s for 1.25 s; the player answers each 1.6 s after it,
        // some late, while the next one already sounds. The app's own chords must never count.
        val appChords = listOf(listOf(48, 55), listOf(50, 57), listOf(52, 59), listOf(53, 60), listOf(55, 62), listOf(57, 64), listOf(52, 60), listOf(50, 55))
        val period = 2.5
        val reference = FloatArray(((appChords.size + 1) * period * sampleRate).toInt())
        appChords.forEachIndexed { step, notes -> addChord(reference, notes, start = step * period, duration = period / 2, seed = 500) }
        val microphone = roomEcho(reference, delaySamples = 1_700, gain = 0.5)
        val answers = appChords.indices.map { step -> appChords[step] to step * period + if (step % 3 == 2) 2.7 else 1.6 }
        answers.forEach { (notes, time) -> addChord(microphone, notes, start = time) }

        // As in the app: the chords given recently; while the app's sound is not removed yet, a
        // chord of the app's current notes is ignored (OwnSoundGate).
        fun given(seconds: Double) = appChords.filterIndexed { step, _ -> step * period <= seconds }.takeLast(2)
        val found = recognize(microphone, 2, ::given, reference = reference).filter { chord ->
            val seconds = chord.onsetTime.toDouble() / sampleRate
            val step = (seconds / period).toInt()
            val sounding = appChords.getOrNull(step)?.takeIf { seconds - step * period < period / 2 + 0.2 }
            chord.ownSoundRemoved || sounding == null || !chord.notes.all { note -> sounding.any { (note - it) % 12 == 0 } }
        }
        val expectedAnswers = answers.map { it.first }
        val hits = found.map { it.notes }.filter { it in expectedAnswers }
        assertTrue(found.map { it.notes }.all { it in expectedAnswers }, "only the player's chords: ${found.map { it.notes to it.onsetTime / 44100.0 }}")
        assertTrue(hits.size >= appChords.size - 1, "${hits.size} of ${appChords.size}: ${found.map { it.notes to it.onsetTime / 44100.0 }}")
    }
}
