package io.github.jonnyfrick.musicbootcamp.core

import io.github.jonnyfrick.musicbootcamp.core.pitch.ChordDetectionParameters
import io.github.jonnyfrick.musicbootcamp.core.pitch.ChordMethod
import io.github.jonnyfrick.musicbootcamp.core.pitch.ChordTracker
import io.github.jonnyfrick.musicbootcamp.core.pitch.DetectedChord
import io.github.jonnyfrick.musicbootcamp.core.pitch.PianoCalibration
import io.github.jonnyfrick.musicbootcamp.core.pitch.matchIgnoringOctaves
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Chords from the microphone, on synthetic piano tones (see [addPianoStroke]). */
class ChordRecognitionTest {
    private val sampleRate = 44_100

    /** The notes struck together (10 ms apart, as fingers are) at [start]. */
    private fun addChord(
        buffer: FloatArray,
        notes: List<Int>,
        start: Double,
        duration: Double = 0.8,
        seed: Int = 0,
        colour: (Double) -> Double = { 1.0 },
    ) {
        notes.forEachIndexed { i, note ->
            addPianoStroke(buffer, note, startSeconds = start + i * 0.01, durationSeconds = duration, seed = seed + note * 3 + i, colour = colour)
        }
    }

    private fun chord(notes: List<Int>, colour: (Double) -> Double = { 1.0 }) =
        FloatArray((1.2 * sampleRate).toInt()).also { addChord(it, notes, start = 0.2, colour = colour) }

    private fun recognize(
        microphone: FloatArray,
        voices: Int,
        expected: (Double) -> List<List<Int>>,
        range: IntRange = 36..84,
        reference: FloatArray? = null,
        parameters: ChordDetectionParameters = ChordDetectionParameters(),
    ): List<DetectedChord> {
        var seconds = 0.0
        val tracker = ChordTracker(sampleRate, voices, range, parameters = parameters, expected = { expected(seconds) })
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

    @Test
    fun calibrationLearnsEachNoteAndIgnoresSlips() {
        val notes = listOf(48, 49, 50)
        val calibration = PianoCalibration(sampleRate, notes, 440.0)
        val buffer = FloatArray((5.0 * sampleRate).toInt())
        addPianoStroke(buffer, 48, startSeconds = 0.2, durationSeconds = 0.6)
        addPianoStroke(buffer, 55, startSeconds = 1.2, durationSeconds = 0.6) // a slip: not the asked note
        addPianoStroke(buffer, 49, startSeconds = 2.2, durationSeconds = 0.6)
        addPianoStroke(buffer, 50, startSeconds = 3.2, durationSeconds = 0.6)
        val heard = mutableListOf<List<Int>?>()
        buffer.indices.chunked(512).forEach { block ->
            calibration.process(buffer.sliceArray(block.first()..block.last()))
            if (calibration.lastHeard != heard.lastOrNull()) heard += calibration.lastHeard
        }
        assertEquals(null, calibration.target, "all done")
        assertEquals(notes.toSet(), calibration.result().notes.keys)
        assertTrue(listOf(55) in heard, "the slip was reported: $heard")

        // Learned from these synthetic strokes, the templates recognise them as well as the model.
        val tracker = ChordTracker(sampleRate, 2, 36..84, learned = calibration.result(), expected = { listOf(listOf(48, 50)) })
        val chordBuffer = chord(listOf(48, 50))
        val found = chordBuffer.indices.chunked(512).flatMap { tracker.process(chordBuffer.sliceArray(it.first()..it.last())) }
        assertEquals(listOf(listOf(48, 50)), found.map { it.notes })
    }

    @Test
    fun octavesCanCountAsCorrect() {
        val expected = listOf(listOf(52, 49), listOf(54, 54))
        assertEquals(listOf(54, 54), matchIgnoringOctaves(listOf(54, 66), expected), "a unison heard with its octave")
        assertEquals(listOf(52, 49), matchIgnoringOctaves(listOf(49, 64), expected), "one voice an octave off")
        assertEquals(null, matchIgnoringOctaves(listOf(49, 55), expected), "a wrong note stays wrong")
        assertEquals(listOf(60), matchIgnoringOctaves(listOf(48), listOf(listOf(60))), "single notes too")
    }

    @Test
    fun calibrationTakesTheAskedNoteInAnotherOctave() {
        // Found live: the model heard a real C3 as C4, so C3 could not be calibrated.
        val calibration = PianoCalibration(sampleRate, listOf(48), 440.0)
        val buffer = FloatArray((1.5 * sampleRate).toInt())
        addPianoStroke(buffer, 60, startSeconds = 0.2, durationSeconds = 0.6) // heard an octave off
        buffer.indices.chunked(512).forEach { calibration.process(buffer.sliceArray(it.first()..it.last())) }
        assertEquals(null, calibration.target)
        assertEquals(setOf(48), calibration.result().notes.keys)
    }

    @Test
    fun theHarmonicMethodCopesWithTheRoomsColouring() {
        // A room and microphone position change single partials by up to ±10 dB; which partials
        // are there does not change. Octave errors count as right, as they do in the app.
        for (seed in 1..3) {
            val colour = roomColour(seed)
            val failures = mutableListOf<String>()
            for (low in listOf(36, 43, 48, 55, 60, 67)) {
                for (interval in 0..24) {
                    val notes = listOf(low, low + interval)
                    val found = recognize(chord(notes.distinct(), colour), 2, { listOf(notes) }).map { it.notes }
                    if (found != listOf(notes.distinct()) && (found.size != 1 || matchIgnoringOctaves(found.single(), listOf(notes)) == null)) {
                        failures += "$notes -> $found"
                    }
                }
            }
            assertTrue(failures.size <= 10, "room $seed: ${failures.size} of 150: $failures")
        }
    }
}
