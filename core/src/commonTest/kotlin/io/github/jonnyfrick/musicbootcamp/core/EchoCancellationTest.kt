package io.github.jonnyfrick.musicbootcamp.core

import io.github.jonnyfrick.musicbootcamp.core.pitch.DetectedNote
import io.github.jonnyfrick.musicbootcamp.core.pitch.DetectionParameters
import io.github.jonnyfrick.musicbootcamp.core.pitch.NoteTracker
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
     * Like the app: the tracker, then — for notes recognised before the app's sound could be
     * removed — the own-sound gate, which ignores the app's current note (and its octaves) while it
     * sounds (half a step, [appAudio]) and 200 ms after.
     */
    private fun detect(microphone: FloatArray, reference: FloatArray?, blockSize: Int = 480): List<DetectedNote> {
        val tracker = NoteTracker(sampleRate)
        val notes = microphone.indices.chunked(blockSize).flatMap { indices ->
            val range = indices.first()..indices.last()
            tracker.process(microphone.sliceArray(range), reference?.sliceArray(range))
        }
        if (reference == null) return notes
        return notes.filter { note ->
            val seconds = note.seconds()
            val step = seconds.toInt()
            val own = appNotes.getOrNull(step)?.takeIf { seconds - step < 0.5 + 0.2 }
            note.ownSoundRemoved || own == null || (note.midiNote - own) % 12 != 0
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
        // Answered after the app's note has ended (at 0.5) and the own-sound gate's 200 ms.
        val answers = (0 until appNotes.size - 1).map { step -> appNotes[step] to step + 0.8 }
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

    @Test
    fun aPlayerInTempoDoesNotMisleadTheDelay() {
        // Found in a phone's browser: the loudspeaker 180 ms ahead of the microphone and as loud as
        // the piano, the player answering every note in tempo, just after the app's next one
        // started. The onset votes took the player's steady lag for the delay (85 ms), so the
        // app's sound was never removed and answers colliding with its attacks were lost.
        val lead = 0.5 // the exercise starts once the microphone delivers sound
        val reference = FloatArray((17.5 * sampleRate).toInt())
        appNotes.forEachIndexed { step, note ->
            addPianoStroke(reference, note, startSeconds = lead + step, durationSeconds = 0.5, seed = 1000 + step)
        }
        val delay = 7_940 // 180 ms
        val microphone = roomEcho(reference, delaySamples = delay, gain = 1.0)
        val answers = (1 until appNotes.size).map { step -> appNotes[step - 1] to lead + step + 0.08 }
        answers.forEach { (note, time) -> addPianoStroke(microphone, note, startSeconds = time, durationSeconds = 0.4, seed = 7 * note) }

        val tracker = NoteTracker(sampleRate)
        microphone.indices.chunked(480).forEach { indices ->
            val range = indices.first()..indices.last()
            tracker.process(microphone.sliceArray(range), reference.sliceArray(range))
        }
        val found = tracker.ownSound!!.delayHops!! * tracker.hopSize
        assertTrue(kotlin.math.abs(found - delay) <= 2 * tracker.hopSize, "delay ${found * 1000 / sampleRate} ms, not 180 ms")
    }

    @Test
    fun aLateAnswerOnTheAppsNextAttackCountsIfItIsAnotherPitch() {
        // Found in a phone's browser: a very late answer started with the echo of the app's next
        // note and had to stand out against half of that note's level, although the app's sound
        // was already removed. A stroke at another pitch than the app's now needs only a quarter.
        val lead = 0.5
        val reference = FloatArray((17.5 * sampleRate).toInt())
        appNotes.forEachIndexed { step, note ->
            addPianoStroke(reference, note, startSeconds = lead + step, durationSeconds = 0.5, seed = 1000 + step)
        }
        val microphone = roomEcho(reference, delaySamples = 7_940, gain = 1.0)
        // Silent while the delay is learned, then each note answered as the next one's echo arrives.
        val answers = (5 until appNotes.size).map { step -> appNotes[step - 1] to lead + step + 0.18 }
        answers.forEach { (note, time) -> addPianoStroke(microphone, note, startSeconds = time, durationSeconds = 0.4, seed = 7 * note) }

        fun found(parameters: DetectionParameters): Int {
            val tracker = NoteTracker(sampleRate, parameters = parameters)
            val notes = microphone.indices.chunked(480).flatMap { indices ->
                val range = indices.first()..indices.last()
                tracker.process(microphone.sliceArray(range), reference.sliceArray(range))
            }
            return answers.count { (note, time) -> notes.any { it.midiNote == note && it.seconds() - time in 0.0..0.15 } }
        }
        val asBefore = found(DetectionParameters(ownSoundShare = 0.5))
        val now = found(DetectionParameters())
        assertTrue(now > asBefore, "$now of ${answers.size} found, before $asBefore")
    }
}
