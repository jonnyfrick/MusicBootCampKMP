package io.github.jonnyfrick.musicbootcamp.desktop

import io.github.jonnyfrick.musicbootcamp.core.midi.MidiMessage
import io.github.jonnyfrick.musicbootcamp.platform.MidiBackend
import io.github.jonnyfrick.musicbootcamp.platform.MidiInputPort
import io.github.jonnyfrick.musicbootcamp.platform.MidiOutputPort
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import uk.co.xfactorylibrarians.coremidi4j.CoreMidiDeviceProvider
import uk.co.xfactorylibrarians.coremidi4j.CoreMidiNotification
import javax.sound.midi.MidiDevice
import javax.sound.midi.MidiSystem
import javax.sound.midi.MidiUnavailableException
import javax.sound.midi.Receiver
import javax.sound.midi.ShortMessage
import javax.sound.midi.Transmitter
import javax.sound.midi.MidiMessage as JavaMidiMessage

/**
 * MIDI via `javax.sound.midi`, like the Java version (`BootCampMidiInterface`).
 *
 * On macOS the devices come from CoreMIDI4J: Java's own CoreMIDI support reads the device
 * list only once, so a keyboard plugged in after the start was never found. CoreMIDI4J
 * sees devices appear and disappear and reports it through [devicesChanged]. On other
 * systems it simply returns Java's own devices.
 *
 * Devices are opened once and stay open until the app ends; closing a port only detaches
 * it. On macOS, `MidiDevice.close()` on a MIDI input can block forever in native code
 * (`MidiInDevice.nStop` against its reader thread, e.g. after the keyboard was unplugged),
 * which froze the UI. The Java version never closed its devices either.
 */
class JavaSoundMidiBackend : MidiBackend {
    override val unavailableReason: String? = null

    override fun inputDevices(): List<String> = devices { it.maxTransmitters != 0 }.map { displayName(it) }.distinct()
    override fun outputDevices(): List<String> = devices { it.maxReceivers != 0 }.map { displayName(it) }.distinct()

    override val devicesChanged: Flow<Unit> = callbackFlow {
        val listener = CoreMidiNotification { trySend(Unit) }
        val registered = runCatching {
            CoreMidiDeviceProvider.isLibraryLoaded() && run {
                CoreMidiDeviceProvider.addNotificationListener(listener)
                true
            }
        }.getOrDefault(false)
        awaitClose {
            if (registered) runCatching { CoreMidiDeviceProvider.removeNotificationListener(listener) }
        }
    }

    override fun openInput(name: String): MidiInputPort {
        val device = devices { it.maxTransmitters != 0 }.firstOrNull { displayName(it) == name }
            ?: throw MidiUnavailableException("MIDI input '$name' is not connected")
        return JavaSoundInput(ensureOpen(device))
    }

    override fun openOutput(name: String): MidiOutputPort {
        val device = devices { it.maxReceivers != 0 }.firstOrNull { displayName(it) == name }
            ?: throw MidiUnavailableException("MIDI output '$name' is not connected")
        return JavaSoundOutput(ensureOpen(device))
    }

    /** CoreMIDI4J prefixes its device names; users know them without it. */
    private fun displayName(device: MidiDevice): String = device.deviceInfo.name.removePrefix(CORE_MIDI_PREFIX)

    @Synchronized
    private fun ensureOpen(device: MidiDevice): MidiDevice {
        if (!device.isOpen) device.open()
        return device
    }

    /** The current devices; on macOS as CoreMIDI4J sees them right now, instead of Java's list from the start. */
    private fun devices(filter: (MidiDevice) -> Boolean): List<MidiDevice> {
        val infos = runCatching { CoreMidiDeviceProvider.getMidiDeviceInfo() }.getOrElse { MidiSystem.getMidiDeviceInfo() }
        return infos.mapNotNull { info ->
            runCatching { MidiSystem.getMidiDevice(info) }.getOrNull()?.takeIf(filter)
        }
    }

    private companion object {
        const val CORE_MIDI_PREFIX = "CoreMIDI4J - "
    }
}

/** One receiver of an open output device; [close] releases only the receiver. */
private class JavaSoundOutput(device: MidiDevice) : MidiOutputPort {
    private val receiver: Receiver = device.receiver

    override fun send(message: MidiMessage) {
        receiver.send(ShortMessage(message.status, message.data1, message.data2), -1)
    }

    override fun close() {
        receiver.close()
    }
}

/** One transmitter of an open input device; [close] detaches it without closing the device. */
private class JavaSoundInput(device: MidiDevice) : MidiInputPort {
    private val transmitter: Transmitter = device.transmitter
    private val flow = MutableSharedFlow<MidiMessage>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    @Volatile private var closed = false

    override val messages: Flow<MidiMessage> = flow.asSharedFlow()

    init {
        transmitter.receiver = object : Receiver {
            override fun send(message: JavaMidiMessage, timeStamp: Long) {
                if (!closed && message is ShortMessage) flow.tryEmit(MidiMessage(message.status, message.data1, message.data2))
            }

            override fun close() = Unit
        }
    }

    override fun close() {
        closed = true
        transmitter.receiver = null
        transmitter.close()
    }
}
