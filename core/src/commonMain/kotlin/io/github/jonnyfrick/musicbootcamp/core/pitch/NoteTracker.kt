package io.github.jonnyfrick.musicbootcamp.core.pitch

import io.github.jonnyfrick.musicbootcamp.core.midi.MidiMessage
import io.github.jonnyfrick.musicbootcamp.core.midi.Tuning
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
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
    /**
     * Whether the app's own sound was being removed; if not, and the app was playing, the note
     * may be the app's own (callers then ignore the app's current note, see OwnSoundGate).
     */
    val ownSoundRemoved: Boolean = false,
)

/** What [NoteTracker] saw and decided in one hop (for analysing recordings). */
class HopTrace(
    /** Samples since the tracker started, at the end of the hop. */
    val sampleTime: Long,
    val level: Double,
    /** The level a stroke is judged by: [level], or what exceeds the app's predicted sound. */
    val strokeLevel: Double,
    /** Predicted RMS of the app's own sound (with the safety factor); 0 without a reference. */
    val ownSoundLevel: Double,
    /** The microphone's usual RMS while the app plays (for the fallback before [ownSoundRemoved]). */
    val levelWhileReference: Double,
    val delayHops: Int?,
    val ownSoundRemoved: Boolean,
    /** Input ignored because the app plays and its sound cannot be removed yet. */
    val blocked: Boolean,
    val referenceOnsetNear: Boolean,
    val onset: Boolean,
    /** The pitch analysed in this hop, if any. */
    val frequencyHz: Double?,
    val clarity: Double?,
    /** The predicted power spectrum of the app's sound; only valid during the callback. */
    val ownSoundPower: DoubleArray?,
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
 * analysis removes its spectrum. Until that prediction is ready (in a room where the app is
 * hardly audible it may never be), a stroke must be [DetectionParameters.fallbackMargin]
 * times louder than the microphone usually is while the app plays.
 */
class NoteTracker(
    val sampleRate: Int,
    private val referenceAHz: Double = Tuning.STANDARD_A_HZ,
    private val parameters: DetectionParameters = DetectionParameters(),
    /** The instrument the player answers on. */
    private val instrument: InstrumentProfile = InstrumentProfile.PIANO,
    /**
     * Whether a change of pitch without a new attack counts as a note (held instruments only).
     * Only safe where the app's own sound cannot be taken for the player's: with headphones, or
     * once it is removed from the signal.
     */
    private val legato: Boolean = false,
) {
    private val noiseGate = parameters.noiseGate
    private val onsetRatio = parameters.onsetRatio
    private val minClarity = parameters.minClarity
    private val confirmFrames = instrument.confirmFrames ?: parameters.confirmFrames
    private val detector = PitchDetector(sampleRate, peakThreshold = parameters.peakThreshold)
    private val windowSize = detector.windowSize
    private val hop = windowSize / 4
    private val settleHops = maxOf(SETTLE_HOPS, instrument.settleMillis * sampleRate / 1000 / hop)
    private val giveUpHops = GIVE_UP_HOPS - SETTLE_HOPS + settleHops + confirmFrames
    private val sameAttackHops = SAME_ATTACK_MILLIS * sampleRate / 1000 / hop

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
    private var hopsSinceNote = Int.MAX_VALUE / 2
    private var candidate: Int? = null
    private var candidateCount = 0
    private val candidateFrequencies = mutableListOf<Double>()

    // Held instruments: the note sounding, to tell a new note from more of the same.
    private var lastNote: Int? = null
    private var lowestLevelSinceNote = 0.0
    private var levelAtNote = 0.0
    private var legatoCandidate: Int? = null
    private var legatoCount = 0

    /**
     * The longest time from a key stroke to its note being confirmed: the hop with the onset,
     * the settling and the confirming windows. Audio buffering comes on top.
     */
    val detectionDelay: Duration = ((settleHops + confirmFrames) * hop).toDouble().div(sampleRate).seconds

    /** The estimate of the app's own sound, once a reference was given (for the chord analysis). */
    internal val ownSound: EchoEstimator? get() = echo

    /** Samples per analysis hop. */
    internal val hopSize: Int get() = hop

    /** Receives what happened in every hop, for analysing recordings. */
    var trace: ((HopTrace) -> Unit)? = null

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
        if (reference != null && parameters.echoCancellation) {
            require(reference.size == samples.size) { "Reference and microphone blocks differ in length" }
            if (echo == null) {
                echo = EchoEstimator(
                    sampleRate, windowSize, detector.paddedSize, hop,
                    parameters.overSubtraction, parameters.reverbDecay, parameters.gainQuantile,
                )
            }
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

    private var traced: PitchEstimate? = null
    private var tracedStroke = 0.0
    private var tracedBlocked = false
    private var tracedOnset = false

    private fun analyseHop(): DetectedNote? {
        traced = null
        val note = analyse()
        trace?.let { report ->
            val echo = echo
            report(
                HopTrace(
                    samplesSeen, level, tracedStroke, echo?.let { sqrt(it.hopEchoEnergy) } ?: 0.0,
                    echo?.levelWhileReference ?: 0.0, echo?.delayHops,
                    echo?.ready == true, tracedBlocked, echo?.referenceOnsetNear == true, tracedOnset,
                    traced?.frequencyHz, traced?.clarity, echo?.echoPower,
                ),
            )
        }
        return note
    }

    private fun analyse(): DetectedNote? {
        var sum = 0.0
        for (sample in hopBuffer) sum += sample * sample
        level = sqrt(sum / hop)

        // Before the app's sound is removed, onsets include the app's own; only after that do they
        // mean the player is active (the estimator detects the player's strokes itself too).
        val echo = echo?.apply { update(hopBuffer, referenceHop, playerActive = ready && hopsSinceStroke < PLAYER_ACTIVE_HOPS) }
        hopsSinceStroke++
        hopsSinceNote++
        // Without a ready prediction of the app's sound, only strokes well above it count.
        // Blocking only prevents new strokes; one already being analysed goes on.
        // Right after the app starts a note its attack may be louder still, so the margin doubles there.
        val blocked = echo != null && !echo.ready && echo.referenceActive && parameters.fallbackMargin > 0 && (
            !echo.levelKnown ||
                level < parameters.fallbackMargin * echo.levelWhileReference * (if (echo.referenceOnsetRecent) 2 else 1)
            )
        val ownSoundRemoved = echo != null && echo.ready
        val strokeLevel = if (ownSoundRemoved) echo!!.residualLevel(sum / hop) else level

        // Where the app's note starts, its prediction is least exact: there a stroke must also
        // stand out against the app's sound, not only against what remained before.
        val ownLevel = if (ownSoundRemoved && echo!!.referenceOnsetNear) sqrt(echo.hopEchoEnergy) * parameters.ownSoundShare else 0.0
        val previous = maxOf(recentLevels.minOrNull() ?: 0.0, ownLevel)
        // What remains of the app's sound fluctuates (e.g. beating with a ringing note); a real
        // stroke also raises the total level above the last hops.
        // Shortly after a note, what remains of it and the app's sound can beat; a new stroke then
        // has to raise the total level clearly.
        val rise = if (echo != null && hopsSinceNote < FOLLOW_UP_HOPS) parameters.followUpRise else parameters.rawRise
        val louder = (!ownSoundRemoved && rise == parameters.rawRise) || level > rise * (recentRawLevels.minOrNull() ?: 0.0)
        val onset = !blocked && louder && strokeLevel >= noiseGate && strokeLevel > onsetRatio * previous &&
            hopsSinceStroke >= REFRACTORY_HOPS
        tracedStroke = strokeLevel
        tracedBlocked = blocked
        tracedOnset = onset
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
        if (level < lowestLevelSinceNote) lowestLevelSinceNote = level
        if (level < noiseGate / 2) lastNote = null // silence: whatever comes next is a new note
        if (hopsSinceOnset < 0) return if (legato && instrument.sustained) followPitch(ownSoundRemoved) else null
        hopsSinceOnset++

        if (hopsSinceOnset > giveUpHops || level < noiseGate / 2) {
            hopsSinceOnset = -1
            return null
        }
        // Wait until the whole window belongs to the new stroke (and a blown note has found its pitch).
        if (hopsSinceOnset < settleHops) return null

        val ownSound = if (ownSoundRemoved) echo!!.echoPower else null
        val estimate = detector.detect(window, background, ownSound) ?: return null.also { resetCandidate() }
        traced = estimate
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
        // A held tone swells slowly: its attack can look like several. The same note again soon
        // after, without the level having dropped in between, is still that one attack.
        val sameAttack = instrument.sustained && note == lastNote && hopsSinceNote < sameAttackHops &&
            lowestLevelSinceNote > REATTACK_DIP * levelAtNote
        return if (sameAttack) null else confirmed(note, candidateFrequencies.takeLast(confirmFrames).average(), ownSoundRemoved)
    }

    private fun confirmed(note: Int, frequency: Double, ownSoundRemoved: Boolean): DetectedNote {
        hopsSinceNote = 0
        lastNote = note
        levelAtNote = level
        lowestLevelSinceNote = level
        legatoCandidate = null
        legatoCount = 0
        val exact = frequencyToMidi(frequency, referenceAHz)
        return DetectedNote(note, frequency, (exact - note) * 100, samplesSeen, ownSoundRemoved)
    }

    /**
     * Between attacks, on a held instrument: a clear pitch other than the note sounding, held
     * for [LEGATO_FRAMES] windows, is a new note played without a new attack.
     */
    private fun followPitch(ownSoundRemoved: Boolean): DetectedNote? {
        val sounding = lastNote ?: return null // legato continues a note; the first one has an attack
        if (level < noiseGate) return null
        val estimate = detector.detect(window, null, if (ownSoundRemoved) echo!!.echoPower else null)
        val note = estimate?.takeIf { it.clarity >= minClarity }?.let { frequencyToMidi(it.frequencyHz, referenceAHz).roundToInt() }
        if (note == null || note == sounding) {
            legatoCandidate = null
            legatoCount = 0
            return null
        }
        if (note == legatoCandidate) legatoCount++ else {
            legatoCandidate = note
            legatoCount = 1
        }
        return if (legatoCount >= LEGATO_FRAMES) confirmed(note, estimate.frequencyHz, ownSoundRemoved) else null
    }

    private fun resetCandidate() {
        candidate = null
        candidateCount = 0
        candidateFrequencies.clear()
    }

    private companion object {
        const val LEVEL_HISTORY = 3
        const val REFRACTORY_HOPS = 6
        const val FOLLOW_UP_HOPS = 34 // ~400 ms
        const val SETTLE_HOPS = 3
        const val GIVE_UP_HOPS = 16
        const val PLAYER_ACTIVE_HOPS = 43 // ~0.5 s
        const val SAME_ATTACK_MILLIS = 500
        const val REATTACK_DIP = 0.5
        const val LEGATO_FRAMES = 6 // ~70 ms: longer than the window takes to slide from one note to the next
    }
}

/** Microphone blocks → note-on messages, so audio input can stand in for a MIDI keyboard. */
fun Flow<FloatArray>.detectNotes(
    sampleRate: Int,
    referenceAHz: Double,
    onNote: (DetectedNote) -> Unit = {},
    onLevel: (Double) -> Unit = {},
    parameters: DetectionParameters = DetectionParameters(),
    instrument: InstrumentProfile = InstrumentProfile.PIANO,
    legato: Boolean = false,
): Flow<MidiMessage> = map { AudioBlock(it) }.detectNotes(sampleRate, referenceAHz, onNote, onLevel, parameters, instrument, legato)

/** Like the microphone-only version; blocks with a reference have the app's own sound removed. */
@JvmName("detectNotesInBlocks")
fun Flow<AudioBlock>.detectNotes(
    sampleRate: Int,
    referenceAHz: Double,
    onNote: (DetectedNote) -> Unit = {},
    onLevel: (Double) -> Unit = {},
    parameters: DetectionParameters = DetectionParameters(),
    instrument: InstrumentProfile = InstrumentProfile.PIANO,
    legato: Boolean = false,
): Flow<MidiMessage> = detectedNotes(sampleRate, referenceAHz, onLevel, parameters, instrument, legato)
    .onEach(onNote)
    .filter { it.midiNote in 0..127 }
    .map { it.toNoteOn() }

/** The recognised notes themselves (with what the tracker knew), for callers that filter them. */
fun Flow<AudioBlock>.detectedNotes(
    sampleRate: Int,
    referenceAHz: Double,
    onLevel: (Double) -> Unit = {},
    parameters: DetectionParameters = DetectionParameters(),
    instrument: InstrumentProfile = InstrumentProfile.PIANO,
    legato: Boolean = false,
): Flow<DetectedNote> = flow {
    val tracker = NoteTracker(sampleRate, referenceAHz, parameters, instrument, legato)
    collect { block ->
        val notes = tracker.process(block.microphone, block.reference)
        onLevel(tracker.level)
        notes.forEach { emit(it) }
    }
}

/** A recognised note as the note-on a MIDI keyboard would send. */
fun DetectedNote.toNoteOn(): MidiMessage = MidiMessage.noteOn(midiNote, DETECTED_VELOCITY)

private const val DETECTED_VELOCITY = 100

