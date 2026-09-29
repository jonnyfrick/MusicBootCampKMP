package io.github.jonnyfrick.musicbootcamp.platform

import io.github.jonnyfrick.musicbootcamp.core.audio.RecordingFile
import io.github.jonnyfrick.musicbootcamp.core.midi.MidiMessage
import io.github.jonnyfrick.musicbootcamp.core.midi.MidiOutput
import io.github.jonnyfrick.musicbootcamp.core.persistence.DocumentStore
import io.github.jonnyfrick.musicbootcamp.core.persistence.InMemoryDocumentStore
import io.github.jonnyfrick.musicbootcamp.core.pitch.AudioBlock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map

/** An opened MIDI output (synth or instrument). */
interface MidiOutputPort : MidiOutput {
    fun close()
}

/** An opened MIDI input (the keyboard the player plays on). */
interface MidiInputPort {
    val messages: Flow<MidiMessage>
    fun close()
}

/** Access to the platform's MIDI system. Implemented per platform; desktop uses javax.sound.midi. */
interface MidiBackend {
    /** Null when MIDI works on this platform, otherwise why it does not. */
    val unavailableReason: String?

    fun inputDevices(): List<String>
    fun outputDevices(): List<String>

    /** Emits whenever devices are plugged in or removed (where the platform can tell). */
    val devicesChanged: Flow<Unit> get() = emptyFlow()

    /** Opens the named device; throws with a readable message if that fails. */
    fun openInput(name: String): MidiInputPort
    fun openOutput(name: String): MidiOutputPort
}

/** Placeholder for platforms whose MIDI implementation does not exist yet. */
class UnsupportedMidiBackend(override val unavailableReason: String) : MidiBackend {
    override fun inputDevices(): List<String> = emptyList()
    override fun outputDevices(): List<String> = emptyList()
    override fun openInput(name: String): MidiInputPort = throw UnsupportedOperationException(unavailableReason)
    override fun openOutput(name: String): MidiOutputPort = throw UnsupportedOperationException(unavailableReason)
}

/** An opened microphone / line input delivering mono samples in -1..1. */
interface AudioInputPort {
    val sampleRate: Int
    /** Cold flow: recording runs while it is collected. */
    val blocks: Flow<FloatArray>

    /**
     * Like [blocks], each with [reference] called for the same number of samples as soon as
     * the block was read (implementations call it on the reading thread, to keep both in step).
     */
    fun blocksWith(reference: (frames: Int) -> FloatArray): Flow<AudioBlock> =
        blocks.map { AudioBlock(it, reference(it.size)) }

    fun close()
}

/** Access to the platform's audio inputs, for pitch detection. Desktop uses javax.sound.sampled. */
interface AudioInputBackend {
    /** Null when audio input works on this platform, otherwise why it does not. */
    val unavailableReason: String?

    fun devices(): List<String>

    /** Whether the app may record now (on Android the user grants it at runtime). */
    val hasAccess: Boolean get() = true

    /** Asks the user for access to the microphone; true if granted. */
    suspend fun requestAccess(): Boolean = true

    /** Opens the named input, or the system default for null. */
    fun open(name: String?): AudioInputPort
}

class UnsupportedAudioInput(override val unavailableReason: String) : AudioInputBackend {
    override fun devices(): List<String> = emptyList()
    override fun open(name: String?): AudioInputPort = throw UnsupportedOperationException(unavailableReason)
}

/** An output the app renders itself, so it knows exactly what the loudspeaker plays. */
interface RenderedOutputPort : MidiOutputPort {
    /**
     * The next [frames] samples of what the loudspeaker plays (mono, at the sample rate it was
     * opened with), continuing where the previous call ended. Call it right after reading the
     * same number of microphone samples, so both stay roughly in step.
     */
    fun playedAudio(frames: Int): FloatArray
}

/** A software synthesizer the app can render itself (desktop: Java's Gervill). */
interface RenderedSynth {
    /** The name of the MIDI output device it stands in for, e.g. "Gervill". */
    val deviceName: String

    /** Opens a new instance playing through the default audio output; throws if that fails. */
    fun open(sampleRate: Int): RenderedOutputPort
}

/** Where practice sessions with the microphone are recorded (audio and event log). */
interface RecordingStore {
    /** The folder, to show the user. */
    val location: String

    /** Starts a new recording, named after the current date and time. */
    fun create(): RecordingFile
}

/** A Java-version settings file chosen by the user, with access to the files next to it. */
class LegacySelection(
    val suggestedName: String,
    val settingsXml: String,
    val readSibling: (String) -> String?,
)

fun interface LegacyFilePicker {
    /** Lets the user choose a Java settings XML file; null if cancelled. */
    suspend fun pickSettingsFile(): LegacySelection?
}

/** Everything the shared app needs from the platform it runs on. */
class PlatformServices(
    val midi: MidiBackend,
    val documents: DocumentStore,
    /** Null where importing old files makes no sense (no file system access). */
    val legacyFiles: LegacyFilePicker?,
    val audio: AudioInputBackend = UnsupportedAudioInput("Microphone input is only implemented in the desktop app so far."),
    /** Null where there is no file system to record into. */
    val recordings: RecordingStore? = null,
    /** Null where the app cannot render its sound itself (then its own sound cannot be removed). */
    val renderedSynth: RenderedSynth? = null,
) {
    companion object {
        /** For Android, iOS and web until their MIDI and storage implementations exist. */
        fun withoutMidi(platform: String) = PlatformServices(
            midi = UnsupportedMidiBackend("MIDI is not implemented on $platform yet — use the desktop app."),
            documents = InMemoryDocumentStore(),
            legacyFiles = null,
        )
    }
}
