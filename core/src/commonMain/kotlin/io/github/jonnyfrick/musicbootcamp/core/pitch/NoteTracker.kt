package io.github.jonnyfrick.musicbootcamp.core.pitch

import io.github.jonnyfrick.musicbootcamp.core.midi.MidiMessage
import io.github.jonnyfrick.musicbootcamp.core.midi.Tuning
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A note recognised from audio: one per key stroke. */
data class DetectedNote(
    val midiNote: Int,
    val frequencyHz: Double,
    /** Deviation from the tempered note, relative to the reference pitch. */
    val cents: Double,
    /** Position in the stream (samples since the tracker started) where the note was confirmed. */
    val sampleTime: Long,
)

/**
 * Turns an audio stream into key strokes, tuned for struck instruments like the piano.
 *
 * A stroke is an onset — the level of the newest [hop] jumps well above the level of
 * the preceding hops — followed by a pitch that stays on the same note for
 * [confirmFrames] analysis windows. Pitch analysis starts only when the window contains
 * nothing but the new stroke, so the still ringing previous note does not win.
 * Each stroke produces at most one note; a sustained or decaying note never repeats.
 */
class NoteTracker(
    val sampleRate: Int,
    private val referenceAHz: Double = Tuning.STANDARD_A_HZ,
    /** Minimum RMS level (full scale = 1) of a stroke; below that everything is ignored. */
    private val noiseGate: Double = 0.01,
    private val onsetRatio: Double = 2.0,
    private val minClarity: Double = 0.75,
    private val confirmFrames: Int = 2,
) {
    private val detector = PitchDetector(sampleRate)
    private val windowSize = detector.windowSize
    private val hop = windowSize / 4

    private val window = FloatArray(windowSize)
    private val background = FloatArray(windowSize)
    private val hopBuffer = FloatArray(hop)
    private var hopFill = 0
    private var samplesSeen = 0L

    private val recentLevels = ArrayDeque<Double>()
    private var hopsSinceOnset = -1 // -1: no stroke being analysed
    private var candidate: Int? = null
    private var candidateCount = 0
    private val candidateFrequencies = mutableListOf<Double>()

    /** RMS of the latest hop, for a level meter. */
    var level: Double = 0.0
        private set

    /** Feeds samples (mono, -1..1); returns the notes confirmed in them. */
    fun process(samples: FloatArray): List<DetectedNote> {
        val notes = mutableListOf<DetectedNote>()
        for (sample in samples) {
            hopBuffer[hopFill++] = sample
            samplesSeen++
            if (hopFill == hop) {
                hopFill = 0
                analyseHop()?.let { notes += it }
            }
        }
        return notes
    }

    private fun analyseHop(): DetectedNote? {
        var sum = 0.0
        for (sample in hopBuffer) sum += sample * sample
        level = sqrt(sum / hop)

        val reference = recentLevels.minOrNull() ?: 0.0
        val onset = level >= noiseGate && level > onsetRatio * reference &&
            (hopsSinceOnset < 0 || hopsSinceOnset >= REFRACTORY_HOPS)
        recentLevels.addLast(level)
        if (recentLevels.size > LEVEL_HISTORY) recentLevels.removeFirst()

        // What was sounding right before the stroke, to be removed from its analysis.
        if (onset) window.copyInto(background)

        // Slide the window by one hop.
        window.copyInto(window, destinationOffset = 0, startIndex = hop)
        hopBuffer.copyInto(window, destinationOffset = windowSize - hop)

        if (onset) {
            hopsSinceOnset = 0
            resetCandidate()
            return null
        }
        if (hopsSinceOnset < 0) return null
        hopsSinceOnset++

        if (hopsSinceOnset > GIVE_UP_HOPS || level < noiseGate / 2) {
            hopsSinceOnset = -1
            return null
        }
        // Wait until the whole window belongs to the new stroke.
        if (hopsSinceOnset < SETTLE_HOPS) return null

        val estimate = detector.detect(window, background) ?: return null.also { resetCandidate() }
        if (estimate.clarity < minClarity) return null.also { resetCandidate() }

        val note = frequencyToMidi(estimate.frequencyHz, referenceAHz).roundToInt()
        if (note == candidate) {
            candidateCount++
        } else {
            resetCandidate()
            candidate = note
            candidateCount = 1
        }
        candidateFrequencies += estimate.frequencyHz
        if (candidateCount < confirmFrames) return null

        hopsSinceOnset = -1 // one note per stroke
        val frequency = candidateFrequencies.takeLast(confirmFrames).average()
        val exact = frequencyToMidi(frequency, referenceAHz)
        return DetectedNote(note, frequency, (exact - note) * 100, samplesSeen)
    }

    private fun resetCandidate() {
        candidate = null
        candidateCount = 0
        candidateFrequencies.clear()
    }

    private companion object {
        const val LEVEL_HISTORY = 3
        const val REFRACTORY_HOPS = 6
        const val SETTLE_HOPS = 3
        const val GIVE_UP_HOPS = 16
    }
}

/** Microphone blocks → note-on messages, so audio input can stand in for a MIDI keyboard. */
fun Flow<FloatArray>.detectNotes(
    sampleRate: Int,
    referenceAHz: Double,
    onNote: (DetectedNote) -> Unit = {},
    onLevel: (Double) -> Unit = {},
): Flow<MidiMessage> = flow {
    val tracker = NoteTracker(sampleRate, referenceAHz)
    collect { block ->
        val notes = tracker.process(block)
        onLevel(tracker.level)
        for (note in notes) {
            onNote(note)
            if (note.midiNote in 0..127) emit(MidiMessage.noteOn(note.midiNote, DETECTED_VELOCITY))
        }
    }
}

private const val DETECTED_VELOCITY = 100

