package io.github.jonnyfrick.musicbootcamp.core.pitch

import io.github.jonnyfrick.musicbootcamp.core.midi.MidiMessage
import io.github.jonnyfrick.musicbootcamp.core.midi.Tuning
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlin.jvm.JvmName
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** A note recognised from audio: one per key stroke. */
data class DetectedNote(
    val midiNote: Int,
    val frequencyHz: Double,
    /** Deviation from the tempered note, relative to the reference pitch. */
    val cents: Double,
    /** Position in the stream (samples since the tracker started) where the note was confirmed. */
    val sampleTime: Long,
)

/** A block of microphone samples and, if known, what the app played meanwhile (same length). */
class AudioBlock(val microphone: FloatArray, val reference: FloatArray? = null)

/**
 * Turns an audio stream into key strokes, tuned for struck instruments like the piano.
 *
 * A stroke is an onset — the level of the newest [hop] jumps well above the level of
 * the preceding hops — followed by a pitch that stays on the same note for
 * [confirmFrames] analysis windows. Pitch analysis starts only when the window contains
 * nothing but the new stroke, so the still ringing previous note does not win.
 * Each stroke produces at most one note; a sustained or decaying note never repeats.
 *
 * Given a reference (what the app played through the loudspeaker), the app's own sound is
 * predicted ([EchoEstimator]) and left out: onsets are measured on what exceeds it, and pitch
 * analysis removes its spectrum. Until that prediction is ready, nothing is detected while
 * the app plays.
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
    private val referenceHop = FloatArray(hop)
    private var echo: EchoEstimator? = null
    private var hopFill = 0
    private var samplesSeen = 0L

    private val recentLevels = ArrayDeque<Double>()
    private val recentRawLevels = ArrayDeque<Double>()
    private var hopsSinceOnset = -1 // -1: no stroke being analysed
    private var hopsSinceStroke = Int.MAX_VALUE / 2
    private var candidate: Int? = null
    private var candidateCount = 0
    private val candidateFrequencies = mutableListOf<Double>()

    /**
     * The longest time from a key stroke to its note being confirmed: the hop with the onset,
     * the settling and the confirming windows. Audio buffering comes on top.
     */
    val detectionDelay: Duration = ((SETTLE_HOPS + confirmFrames) * hop).toDouble().div(sampleRate).seconds

    /** RMS of the latest hop, for a level meter. */
    var level: Double = 0.0
        private set

    /** Whether the app's own sound is being removed (a reference was given and the prediction is ready). */
    val cancelsOwnSound: Boolean get() = echo?.ready == true

    /**
     * Feeds samples (mono, -1..1); returns the notes confirmed in them. [reference] is what the
     * app played at the same time, sample by sample; null means silence (or unknown, if never given).
     */
    fun process(samples: FloatArray, reference: FloatArray? = null): List<DetectedNote> {
        if (reference != null) {
            require(reference.size == samples.size) { "Reference and microphone blocks differ in length" }
            if (echo == null) echo = EchoEstimator(sampleRate, windowSize, detector.paddedSize, hop)
        }
        val notes = mutableListOf<DetectedNote>()
        for ((index, sample) in samples.withIndex()) {
            referenceHop[hopFill] = reference?.get(index) ?: 0f
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

        val echo = echo?.apply { update(hopBuffer, referenceHop, playerActive = hopsSinceStroke < PLAYER_ACTIVE_HOPS) }
        hopsSinceStroke++
        // Without a ready prediction of the app's sound, it would be taken for the player's.
        val blocked = echo != null && !echo.ready && echo.referenceActive
        if (blocked) hopsSinceOnset = -1
        val ownSoundRemoved = echo != null && echo.ready
        val strokeLevel = if (ownSoundRemoved) echo!!.residualLevel(sum / hop) else level

        // Where the app's note starts, its prediction is least exact: there a stroke must also
        // stand out against the app's sound, not only against what remained before.
        val ownLevel = if (ownSoundRemoved && echo!!.referenceOnsetNear) sqrt(echo.hopEchoEnergy) * OWN_SOUND_SHARE else 0.0
        val previous = maxOf(recentLevels.minOrNull() ?: 0.0, ownLevel)
        // What remains of the app's sound fluctuates (e.g. beating with a ringing note); a real
        // stroke also raises the total level above the last hops.
        val louder = !ownSoundRemoved || level > RAW_RISE * (recentRawLevels.minOrNull() ?: 0.0)
        val onset = !blocked && louder && strokeLevel >= noiseGate && strokeLevel > onsetRatio * previous &&
            (hopsSinceOnset < 0 || hopsSinceOnset >= REFRACTORY_HOPS)
        recentLevels.addLast(strokeLevel)
        if (recentLevels.size > LEVEL_HISTORY) recentLevels.removeFirst()
        recentRawLevels.addLast(level)
        if (recentRawLevels.size > LEVEL_HISTORY) recentRawLevels.removeFirst()

        // What was sounding right before the stroke, to be removed from its analysis.
        if (onset) window.copyInto(background)

        // Slide the window by one hop.
        window.copyInto(window, destinationOffset = 0, startIndex = hop)
        hopBuffer.copyInto(window, destinationOffset = windowSize - hop)

        if (onset) {
            hopsSinceStroke = 0
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

        val ownSound = if (ownSoundRemoved) echo!!.echoPower else null
        val estimate = detector.detect(window, background, ownSound) ?: return null.also { resetCandidate() }
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
        const val OWN_SOUND_SHARE = 0.5
        const val PLAYER_ACTIVE_HOPS = 43 // ~0.5 s
        const val RAW_RISE = 1.5
    }
}

/** Microphone blocks → note-on messages, so audio input can stand in for a MIDI keyboard. */
fun Flow<FloatArray>.detectNotes(
    sampleRate: Int,
    referenceAHz: Double,
    onNote: (DetectedNote) -> Unit = {},
    onLevel: (Double) -> Unit = {},
): Flow<MidiMessage> = map { AudioBlock(it) }.detectNotes(sampleRate, referenceAHz, onNote, onLevel)

/** Like the microphone-only version; blocks with a reference have the app's own sound removed. */
@JvmName("detectNotesInBlocks")
fun Flow<AudioBlock>.detectNotes(
    sampleRate: Int,
    referenceAHz: Double,
    onNote: (DetectedNote) -> Unit = {},
    onLevel: (Double) -> Unit = {},
): Flow<MidiMessage> = flow {
    val tracker = NoteTracker(sampleRate, referenceAHz)
    collect { block ->
        val notes = tracker.process(block.microphone, block.reference)
        onLevel(tracker.level)
        for (note in notes) {
            onNote(note)
            if (note.midiNote in 0..127) emit(MidiMessage.noteOn(note.midiNote, DETECTED_VELOCITY))
        }
    }
}

private const val DETECTED_VELOCITY = 100

