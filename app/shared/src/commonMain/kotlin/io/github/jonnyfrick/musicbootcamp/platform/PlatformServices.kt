package io.github.jonnyfrick.musicbootcamp.platform

import io.github.jonnyfrick.musicbootcamp.core.midi.MidiMessage
import io.github.jonnyfrick.musicbootcamp.core.midi.MidiOutput
import io.github.jonnyfrick.musicbootcamp.core.persistence.DocumentStore
import io.github.jonnyfrick.musicbootcamp.core.persistence.InMemoryDocumentStore
import kotlinx.coroutines.flow.Flow

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
