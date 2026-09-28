package io.github.jonnyfrick.musicbootcamp.core

import io.github.jonnyfrick.musicbootcamp.core.pitch.DetectedNote
import io.github.jonnyfrick.musicbootcamp.core.pitch.NoteTracker
import kotlin.math.exp
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The app plays through a loudspeaker that the microphone hears. With the played audio as
 * reference, the player's notes are recognised while the app plays, and the app's own notes
 * never count.
 */
class EchoCancellationTest {
    private val sampleRate = 44_100

    /** What the app plays: [notes] at one per [periodSeconds], each sounding for half the period. */
    private fun appAudio(notes: List<Int>, seconds: Double, periodSeconds: Double = 1.0): FloatArray {
        val buffer = FloatArray((seconds * sampleRate).toInt())
        notes.forEachIndexed { step, note ->
            addPianoStroke(buffer, note, startSeconds = step * periodSeconds, durationSeconds = periodSeconds / 2, seed = 1000 + step)
        }
        return buffer
    }

    /**
     * The reference as the microphone hears it: [delaySamples] later (output and input latency),
     * [gain] quieter, with early reflections, a reverberation tail, and some noise.
     */
    private fun roomEcho(reference: FloatArray, delaySamples: Int, gain: Double, seed: Int = 1): FloatArray {
        val random = Random(seed)
        val tailLength = (0.12 * sampleRate).toInt()
        val response = DoubleArray(tailLength + sampleRate / 1000)
        response[0] = 1.0
        response[(0.003 * sampleRate).toInt()] += 0.5
        response[(0.007 * sampleRate).toInt()] -= 0.3
        // Diffuse reverberation: one random reflection per millisecond, decaying.
        for (i in (0.01 * sampleRate).toInt() until tailLength step sampleRate / 1000) {
            response[i + random.nextInt(sampleRate / 1000)] += (random.nextDouble() - 0.5) * 0.4 * exp(-i / (0.04 * sampleRate))
        }
        val taps = response.indices.filter { response[it] != 0.0 }
        val echo = FloatArray(reference.size)
        for (n in echo.indices) {
            var value = 0.0
            for (k in taps) {
                val index = n - delaySamples - k
                if (index >= 0) value += response[k] * reference[index]
            }
            echo[n] = (gain * value + (random.nextDouble() - 0.5) * 0.002).toFloat()
        }
        return echo
    }

    private fun detect(microphone: FloatArray, reference: FloatArray?, blockSize: Int = 480): List<DetectedNote> {
        val tracker = NoteTracker(sampleRate)
        return microphone.indices.chunked(blockSize).flatMap { indices ->
            val range = indices.first()..indices.last()
            tracker.process(microphone.sliceArray(range), reference?.sliceArray(range))
        }
    }

    private fun DetectedNote.seconds() = sampleTime.toDouble() / sampleRate

    private val appNotes = listOf(60, 64, 62, 67, 65, 69, 64, 71, 67, 72, 65, 60, 62, 67, 64, 69)

    @Test
    fun theAppsOwnNotesAreNeverTakenForThePlayers() {
        for (gain in listOf(0.3, 1.0, 2.0)) {
            val reference = appAudio(appNotes, seconds = 17.0)
            val microphone = roomEcho(reference, delaySamples = 1_700, gain = gain)
            assertEquals(emptyList(), detect(microphone, reference).map { it.midiNote to it.seconds() }, "gain $gain")
        }
    }

    @Test
    fun withoutReferenceTheAppsNotesAreHeardAsPlayed() {
        // Why the reference is needed at all.
        val reference = appAudio(appNotes, seconds = 17.0)
        val microphone = roomEcho(reference, delaySamples = 1_700, gain = 1.0)
        assertTrue(detect(microphone, reference = null).size > 10)
    }

    @Test
    fun aLoudspeakerTooQuietToMeasureDoesNotBlockThePlayer() {
        // Found in real recordings: the app 20+ dB below the piano, its delay never measurable.
        // Input must not stay blocked just because the app's sound cannot be learned.
        val reference = appAudio(appNotes, seconds = 17.0)
        val microphone = roomEcho(reference, delaySamples = 1_700, gain = 0.0)
        val answers = (0 until appNotes.size - 1).map { step -> appNotes[step] to step + 0.6 }
        answers.forEach { (note, time) -> addPianoStroke(microphone, note, startSeconds = time, durationSeconds = 0.3, seed = 7 * note) }

        val detected = detect(microphone, reference)
        assertEquals(answers.map { it.first }, detected.map { it.midiNote }, detected.map { it.midiNote to it.seconds() }.toString())
    }

    @Test
    fun lateAnswersOverTheNextNoteAreRecognised() {
        val reference = appAudio(appNotes, seconds = 17.0)
        val microphone = roomEcho(reference, delaySamples = 1_700, gain = 0.5)
        // From the sixth step on, each note is answered 100 ms after the next one has started.
        val answers = (5 until appNotes.size - 1).map { step -> appNotes[step] to step + 1.1 }
        answers.forEach { (note, time) -> addPianoStroke(microphone, note, startSeconds = time, durationSeconds = 0.4, seed = 7 * note) }

        val detected = detect(microphone, reference)
        assertEquals(answers.map { it.first }, detected.map { it.midiNote }, detected.map { it.midiNote to it.seconds() }.toString())
        detected.zip(answers).forEach { (note, answer) -> assertTrue(note.seconds() - answer.second < 0.1) }
    }

    @Test
    fun theNoteTheAppIsPlayingCanBePlayedAlong() {
        val reference = appAudio(appNotes, seconds = 17.0)
        val microphone = roomEcho(reference, delaySamples = 1_700, gain = 0.5)
        // From the sixth step on, the player joins the app's note 250 ms after it started.
        val answers = (5 until appNotes.size).map { step -> appNotes[step] to step + 0.25 }
        answers.forEach { (note, time) -> addPianoStroke(microphone, note, startSeconds = time, durationSeconds = 0.4, seed = 7 * note) }

        val detected = detect(microphone, reference)
        val found = detected.map { it.midiNote to it.seconds() }.toString()
        // Joining the app's own note is the hardest case; fine tuning happens with real recordings.
        assertTrue(detected.all { note -> answers.any { it.first == note.midiNote && note.seconds() - it.second in 0.0..0.5 } }, found)
        assertTrue(detected.size >= answers.size - 2, found)
    }
}
