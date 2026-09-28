package io.github.jonnyfrick.musicbootcamp.core.audio

import io.github.jonnyfrick.musicbootcamp.core.midi.MidiMessage
import io.github.jonnyfrick.musicbootcamp.core.midi.MidiOutput
import io.github.jonnyfrick.musicbootcamp.core.persistence.SetupRepository
import io.github.jonnyfrick.musicbootcamp.core.pitch.DetectedNote
import io.github.jonnyfrick.musicbootcamp.core.practice.Evaluation
import io.github.jonnyfrick.musicbootcamp.core.practice.StepResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable

/** Where a recording goes: the audio as it arrives, the header and the event log at the end. */
interface RecordingFile {
    /** A name to show the user, e.g. the file path. */
    val name: String

    fun append(bytes: ByteArray)

    /** Writes [wavHeader] at the start of the audio and stores [log] next to it. */
    fun finish(wavHeader: ByteArray, log: String)
}

@Serializable
enum class RecordingEventType {
    /** The app started a note. */
    APP_NOTE_ON,
    APP_NOTE_OFF,

    /** Pitch detection recognised a note (before the own-sound filter). */
    DETECTED,

    /** A detected note that reached the exercise. */
    ACCEPTED,

    /** A new step started; [RecordingEvent.notes] are the given notes. */
    STEP,

    /** A step was evaluated; [RecordingEvent.correct] says how. */
    EVALUATION,
}

@Serializable
data class RecordingEvent(
    /** Position in the recording, in samples. */
    val sample: Long,
    val type: RecordingEventType,
    val notes: List<Int> = emptyList(),
    val cents: Double? = null,
    val correct: Boolean? = null,
)

@Serializable
data class RecordingLog(
    val version: Int = 1,
    val sampleRate: Int,
    /** What the channels of the WAV file hold, e.g. `microphone`, `reference`. */
    val channels: List<String>,
    /** Settings that matter for replaying the recording (tolerance, headphones, tuning, …). */
    val info: Map<String, String> = emptyMap(),
    val events: List<RecordingEvent>,
)

/**
 * Records a practice session with microphone input: the audio the pitch detection gets, as a
 * WAV file, and a log of what the app played, what was recognised and how steps were evaluated.
 * Replaying it tunes the recognition on real sound instead of synthetic tones.
 *
 * [audio] and [detected] are called from the audio pipeline, the other events from the exercise
 * dispatcher; each side keeps its own list. [finish] merges them once both have stopped.
 */
class SessionRecorder(
    private val file: RecordingFile,
    val sampleRate: Int,
    private val channelNames: List<String>,
) {
    private val samplesWritten = MutableStateFlow(0L) // written by the audio side only
    private val audioEvents = mutableListOf<RecordingEvent>()
    private val exerciseEvents = mutableListOf<RecordingEvent>()
    private var bytesWritten = 0L

    val name: String get() = file.name

    /** One block per channel, in the order of the channel names. */
    fun audio(channels: List<FloatArray>) {
        require(channels.size == channelNames.size) { "Expected ${channelNames.size} channels" }
        val bytes = Wav.pcm16(channels)
        file.append(bytes)
        bytesWritten += bytes.size
        samplesWritten.value += channels.first().size
    }

    /** [DetectedNote.sampleTime] counts from the same first block as this recording. */
    fun detected(note: DetectedNote) {
        audioEvents += RecordingEvent(note.sampleTime, RecordingEventType.DETECTED, listOf(note.midiNote), cents = note.cents)
    }

    fun accepted(message: MidiMessage) = exerciseEvent(RecordingEventType.ACCEPTED, listOf(message.data1))

    fun step(result: StepResult) {
        result.previousCorrect?.let { exerciseEvent(RecordingEventType.EVALUATION, correct = it) }
        exerciseEvent(RecordingEventType.STEP, result.given)
    }

    fun evaluation(evaluation: Evaluation) = exerciseEvent(RecordingEventType.EVALUATION, correct = evaluation.correct)

    /** Wraps the app's output so every note it plays is logged. */
    fun recording(output: MidiOutput) = MidiOutput { message ->
        when {
            message.isNoteOn -> exerciseEvent(RecordingEventType.APP_NOTE_ON, listOf(message.data1))
            message.command == MidiMessage.NOTE_OFF || message.command == MidiMessage.NOTE_ON ->
                exerciseEvent(RecordingEventType.APP_NOTE_OFF, listOf(message.data1))
        }
        output.send(message)
    }

    /** Call once the audio has stopped and the exercise has ended. */
    fun finish(info: Map<String, String>) {
        val events = (audioEvents + exerciseEvents).sortedBy { it.sample }
        val log = RecordingLog(sampleRate = sampleRate, channels = channelNames, info = info, events = events)
        file.finish(Wav.header(sampleRate, channelNames.size, bytesWritten), SetupRepository.json.encodeToString(log))
    }

    // Exercise events happen "now": at the end of the audio received so far (one block of jitter).
    private fun exerciseEvent(type: RecordingEventType, notes: List<Int> = emptyList(), correct: Boolean? = null) {
        exerciseEvents += RecordingEvent(samplesWritten.value, type, notes, correct = correct)
    }
}
