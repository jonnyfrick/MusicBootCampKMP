package io.github.jonnyfrick.musicbootcamp.core

import io.github.jonnyfrick.musicbootcamp.core.midi.MidiMessage
import io.github.jonnyfrick.musicbootcamp.core.pitch.NoteTracker
import io.github.jonnyfrick.musicbootcamp.core.pitch.detectNotes
import io.github.jonnyfrick.musicbootcamp.core.practice.OwnSoundGate
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import io.github.jonnyfrick.musicbootcamp.core.pitch.PitchDetector
import io.github.jonnyfrick.musicbootcamp.core.pitch.frequencyToMidi
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource
import kotlin.test.assertTrue

/**
 * Pitch detection against synthetic piano tones: stretched (inharmonic) partials,
 * a weak fundamental in the bass, faster decay of upper partials and a noisy attack.
 */
class PitchDetectionTest {

    private val sampleRate = 44_100

    /**
     * Adds a piano-like stroke of [midiNote] to [buffer] starting at [startSeconds]; after
     * [durationSeconds] the key is released and the damper stops the string within ~0.1 s.
     */
    private fun addPianoStroke(
        buffer: FloatArray,
        midiNote: Int,
        startSeconds: Double,
        durationSeconds: Double = 1.5,
        referenceAHz: Double = 440.0,
        detuneCents: Double = 0.0,
        loudness: Double = 0.3,
        seed: Int = midiNote,
    ) {
        val f0 = referenceAHz * 2.0.pow((midiNote - 69 + detuneCents / 100) / 12)
        // Inharmonicity grows towards the treble (typical B from ~0.0001 to ~0.002).
        val b = 0.0001 * 2.0.pow((midiNote - 21) / 20.0)
        // Bass strings: the fundamental is much weaker than the next partials.
        val fundamentalWeight = if (midiNote < 48) 0.25 else 1.0
        val random = Random(seed)
        val phases = DoubleArray(16) { random.nextDouble(2 * PI) }
        val start = (startSeconds * sampleRate).toInt()
        val length = ((durationSeconds + 0.3) * sampleRate).toInt()
        for (i in 0 until length) {
            val index = start + i
            if (index >= buffer.size) break
            val t = i.toDouble() / sampleRate
            var value = 0.0
            for (n in 1..16) {
                val fn = n * f0 * sqrt(1 + b * n * n)
                if (fn >= sampleRate / 2) break
                val amplitude = (if (n == 1) fundamentalWeight else 1.0) / n
                value += amplitude * exp(-t * (1.2 + 0.6 * n)) * sin(2 * PI * fn * t + phases[n - 1])
            }
            // Hammer noise during the first 4 ms.
            if (t < 0.004) value += (random.nextDouble() - 0.5) * 0.6 * (1 - t / 0.004)
            val damper = if (t < durationSeconds) 1.0 else exp(-(t - durationSeconds) / 0.03)
            buffer[index] += (loudness * value * damper).toFloat()
        }
    }

    private fun silence(seconds: Double) = FloatArray((seconds * sampleRate).toInt())

    private fun detect(buffer: FloatArray, referenceAHz: Double = 440.0, blockSize: Int = 480): List<Int> {
        val tracker = NoteTracker(sampleRate, referenceAHz)
        // Feed in odd-sized blocks like a sound card would.
        return buffer.toList().chunked(blockSize).flatMap { tracker.process(it.toFloatArray()) }.map { it.midiNote }
    }

    @Test
    fun detectorFindsTheFundamentalOfEveryPianoNote() {
        val detector = PitchDetector(sampleRate)
        val failures = mutableListOf<String>()
        for (note in 36..96) {
            val buffer = silence(0.3)
            addPianoStroke(buffer, note, startSeconds = 0.0)
            val window = buffer.copyOfRange(2000, 2000 + detector.windowSize)
            val estimate = detector.detect(window)
            val midi = estimate?.let { frequencyToMidi(it.frequencyHz, 440.0) }
            if (midi == null || abs(midi - note) > 0.3) failures += "$note → $midi"
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun everyStrokeIsReportedExactlyOnce() {
        val failures = mutableListOf<String>()
        for (note in 36..96) {
            val buffer = silence(1.2)
            addPianoStroke(buffer, note, startSeconds = 0.2)
            val detected = detect(buffer)
            if (detected != listOf(note)) failures += "$note → $detected"
        }
        assertEquals(emptyList(), failures)
    }

    /** Legato without pedal: each key is released 80 ms after the next one is struck. */
    @Test
    fun legatoPhrase() {
        val phrase = listOf(60, 64, 67, 72, 71, 67, 62, 55, 48, 60, 61, 60, 84, 36)
        val buffer = silence(0.3 + phrase.size * 0.5 + 1.0)
        phrase.forEachIndexed { i, note -> addPianoStroke(buffer, note, startSeconds = 0.3 + i * 0.5, durationSeconds = 0.58) }
        assertEquals(phrase, detect(buffer))
    }

    /** With the sustain pedal down every note keeps ringing under the following ones. */
    @Test
    fun phraseWithSustainPedal() {
        val phrase = listOf(60, 64, 67, 72, 71, 67, 62, 55, 48, 60)
        val buffer = silence(0.3 + phrase.size * 0.5 + 2.0)
        phrase.forEachIndexed { i, note -> addPianoStroke(buffer, note, startSeconds = 0.3 + i * 0.5, durationSeconds = 3.0) }
        assertEquals(phrase, detect(buffer))
    }

    @Test
    fun repeatedKeyIsCountedTwice() {
        val buffer = silence(2.0)
        addPianoStroke(buffer, 65, startSeconds = 0.2)
        addPianoStroke(buffer, 65, startSeconds = 0.9, seed = 7)
        assertEquals(listOf(65, 65), detect(buffer))
    }

    @Test
    fun followsTheReferencePitch() {
        // A piano tuned to 442 Hz: its A4 is 442 Hz, which must still read as note 69.
        val buffer = silence(1.2)
        addPianoStroke(buffer, 69, startSeconds = 0.2, referenceAHz = 442.0)
        assertEquals(listOf(69), detect(buffer, referenceAHz = 442.0))

        // Slightly out of tune still counts as the same key; half a semitone off does not.
        val sharp = silence(1.2).also { addPianoStroke(it, 64, 0.2, detuneCents = 30.0) }
        assertEquals(listOf(64), detect(sharp))
        val veryFlat = silence(1.2).also { addPianoStroke(it, 64, 0.2, detuneCents = -70.0) }
        assertEquals(listOf(63), detect(veryFlat))
    }

    @Test
    fun noiseAndQuietStrokesAreIgnored() {
        val random = Random(1)
        val noise = FloatArray(sampleRate * 2) { ((random.nextDouble() - 0.5) * 0.01).toFloat() }
        assertEquals(emptyList(), detect(noise))

        val quiet = silence(1.2).also { addPianoStroke(it, 60, 0.2, loudness = 0.003) }
        assertEquals(emptyList(), detect(quiet))
    }

    @Test
    fun audioStreamBecomesNoteOnMessages() = runTest {
        val buffer = silence(2.0)
        addPianoStroke(buffer, 57, startSeconds = 0.2)
        addPianoStroke(buffer, 59, startSeconds = 1.0)
        val blocks = buffer.toList().chunked(512).map { it.toFloatArray() }.asFlow()
        val messages = blocks.detectNotes(sampleRate, referenceAHz = 440.0).toList()
        assertEquals(listOf(MidiMessage.noteOn(57, 100), MidiMessage.noteOn(59, 100)), messages)
    }

    @Test
    fun ownSoundGateBlocksInputWhileTheAppPlays() {
        val time = TestTimeSource()
        val sent = mutableListOf<MidiMessage>()
        val gate = OwnSoundGate({ sent += it }, releaseTime = 200.milliseconds, timeSource = time)
        assertTrue(gate.isQuiet())
        gate.send(MidiMessage.noteOn(60, 80))
        assertFalse(gate.isQuiet())
        time += 500.milliseconds
        gate.send(MidiMessage.noteOff(60))
        time += 100.milliseconds
        assertFalse(gate.isQuiet(), "the synth still rings shortly after note-off")
        time += 150.milliseconds
        assertTrue(gate.isQuiet())
        assertEquals(2, sent.size, "messages are passed on")
    }

    @Test
    fun detectionIsFastEnough() {
        val buffer = silence(1.2)
        addPianoStroke(buffer, 60, startSeconds = 0.2)
        val tracker = NoteTracker(sampleRate)
        val note = buffer.toList().chunked(256).flatMap { tracker.process(it.toFloatArray()) }.single()
        val latencyMs = (note.sampleTime - (0.2 * sampleRate).toLong()) * 1000 / sampleRate
        assertTrue(latencyMs < 120, "latency $latencyMs ms")
    }
}
