package io.github.jonnyfrick.musicbootcamp.core.persistence

import io.github.jonnyfrick.musicbootcamp.core.learning.LearnedSequences
import io.github.jonnyfrick.musicbootcamp.core.midi.Tuning
import io.github.jonnyfrick.musicbootcamp.core.model.LearnedSequence
import io.github.jonnyfrick.musicbootcamp.core.model.PracticeMode
import io.github.jonnyfrick.musicbootcamp.core.model.PracticeSettings
import io.github.jonnyfrick.musicbootcamp.core.pitch.ChordDetectionParameters
import io.github.jonnyfrick.musicbootcamp.core.pitch.DetectionParameters
import kotlinx.serialization.Serializable

/**
 * A named exercise configuration with its learned sequences (Java: one settings
 * XML file plus its `learned_sequences_*.xml`).
 *
 * Unlike the Java version, every mode keeps its own memory, so switching the mode
 * no longer throws away what was learned in the other one.
 */
class Setup(
    val name: String,
    var settings: PracticeSettings = PracticeSettings(),
    private val memories: MutableMap<PracticeMode, LearnedSequences> = mutableMapOf(),
) {
    /** The memory of [mode], created empty on first use. */
    fun memory(mode: PracticeMode = settings.mode): LearnedSequences = memories.getOrPut(mode) { LearnedSequences() }

    fun copy(newName: String): Setup = Setup(
        name = newName,
        settings = settings,
        memories = memories.mapValuesTo(mutableMapOf()) { (_, memory) ->
            LearnedSequences.fromPriorityLevels(memory.byPriority())
        },
    )

    internal fun toDocument() = SetupDocument(
        name = name,
        settings = settings,
        learnedSequences = memories.filterValues { !it.isEmpty() }.mapValues { (_, memory) -> memory.byPriority() },
    )

    internal companion object {
        fun fromDocument(document: SetupDocument) = Setup(
            name = document.name,
            settings = document.settings,
            memories = document.learnedSequences.mapValuesTo(mutableMapOf()) { (_, levels) ->
                LearnedSequences.fromPriorityLevels(levels)
            },
        )
    }
}

/** On-disk form of a [Setup]. */
@Serializable
internal data class SetupDocument(
    val version: Int = SETUP_FORMAT_VERSION,
    val name: String,
    val settings: PracticeSettings,
    /** Per mode: priority levels (0 = most urgent), each a list of sequences. */
    val learnedSequences: Map<PracticeMode, List<List<LearnedSequence>>> = emptyMap(),
)

/**
 * 2: the late-answer tolerance is part of each setup's settings (1: one value in the preferences).
 */
internal const val SETUP_FORMAT_VERSION = 2

/** Machine-wide preferences (Java: the MIDI part of every settings file). */
@Serializable
data class AppPreferences(
    val version: Int = SETUP_FORMAT_VERSION,
    val midiInputDevice: String? = null,
    val midiOutputDevice: String? = null,
    val referenceAHz: Double = Tuning.STANDARD_A_HZ,
    val lastSetup: String? = null,
    /** Show the given notes while practising; off by default because the point is to hear them. */
    val showGivenNotes: Boolean = false,
    /** Where the played notes come from. */
    /** The microphone by default: most players sit at an acoustic piano, and it needs no device. */
    val inputSource: InputSource = InputSource.MICROPHONE,
    /** Microphone for [InputSource.MICROPHONE]; null = system default. */
    val audioInputDevice: String? = null,
    /** With headphones the microphone cannot hear the app, so input is accepted at any time. */
    val usesHeadphones: Boolean = false,
    /**
     * Format 1 only, where the late-answer tolerance was one value for all setups (and the
     * microphone only). Now [PracticeSettings.lateAnswerToleranceMillis]; this is what setups
     * still in format 1 get when they are loaded. Nothing changes it any more.
     */
    val lateAnswerToleranceMillis: Int = PracticeSettings.DEFAULT_LATE_ANSWER_TOLERANCE_MILLIS,
    /** Microphone input: record each exercise (audio and event log) to tune the pitch detection. */
    val recordMicrophone: Boolean = false,
    /**
     * Microphone input: runs of [optimizationSteps] notes, always recorded, with the detection
     * parameters editable, to tune the recognition on real playing.
     */
    val optimizationMode: Boolean = false,
    val optimizationSteps: Int = DEFAULT_OPTIMIZATION_STEPS,
    val detectionParameters: DetectionParameters = DetectionParameters(),
    /**
     * Which defaults [detectionParameters] were tuned against; older ones are reset on loading, so
     * a value a later version changed its mind about does not linger (see [SetupRepository.loadPreferences]).
     */
    val detectionParametersRevision: Int = 0,
    /**
     * Microphone input: a note or chord with the right pitch classes counts as the given one, in
     * whatever octave (octave errors of the recognition are far more common than of the player).
     */
    val octavesCountAsCorrect: Boolean = true,
    /** Chord recognition (microphone, several voices), per number of voices. */
    val chordDetectionParameters: Map<Int, ChordDetectionParameters> = emptyMap(),
) {
    fun chordParameters(voices: Int): ChordDetectionParameters = chordDetectionParameters[voices] ?: ChordDetectionParameters()
}

/** Raise when the defaults of [DetectionParameters] change in a way stored values should follow. */
const val DETECTION_PARAMETERS_REVISION = 1

const val DEFAULT_OPTIMIZATION_STEPS = 12
val OPTIMIZATION_STEP_RANGE = 5..50

@Serializable
enum class InputSource {
    /** A MIDI keyboard. */
    MIDI,

    /** An acoustic instrument (piano) via microphone: pitch detection for single notes, chord recognition for several voices. */
    MICROPHONE,
}
