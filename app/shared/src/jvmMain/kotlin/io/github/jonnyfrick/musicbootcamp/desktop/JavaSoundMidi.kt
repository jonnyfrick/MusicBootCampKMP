package io.github.jonnyfrick.musicbootcamp.desktop

import io.github.jonnyfrick.musicbootcamp.core.midi.MidiMessage
import io.github.jonnyfrick.musicbootcamp.platform.MidiBackend
import io.github.jonnyfrick.musicbootcamp.platform.MidiInputPort
import io.github.jonnyfrick.musicbootcamp.platform.MidiOutputPort
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.sound.midi.MidiDevice
import javax.sound.midi.MidiSystem
import javax.sound.midi.MidiUnavailableException
import javax.sound.midi.Receiver
import javax.sound.midi.ShortMessage
import javax.sound.midi.Transmitter
import javax.sound.midi.MidiMessage as JavaMidiMessage

/** MIDI via `javax.sound.midi`, like the Java version (`BootCampMidiInterface`). */
class JavaSoundMidiBackend : MidiBackend {
    override val unavailableReason: String? = null

    override fun inputDevices(): List<String> = devices { it.maxTransmitters != 0 }.map { it.deviceInfo.name }.distinct()
    override fun outputDevices(): List<String> = devices { it.maxReceivers != 0 }.map { it.deviceInfo.name }.distinct()

    override fun openInput(name: String): MidiInputPort {
        val device = devices { it.maxTransmitters != 0 }.firstOrNull { it.deviceInfo.name == name }
            ?: throw MidiUnavailableException("MIDI input '$name' is not connected")
        return JavaSoundInput(device)
    }

    override fun openOutput(name: String): MidiOutputPort {
        val device = devices { it.maxReceivers != 0 }.firstOrNull { it.deviceInfo.name == name }
            ?: throw MidiUnavailableException("MIDI output '$name' is not connected")
        return JavaSoundOutput(device)
    }

    private fun devices(filter: (MidiDevice) -> Boolean): List<MidiDevice> =
        MidiSystem.getMidiDeviceInfo().mapNotNull { info ->
            runCatching { MidiSystem.getMidiDevice(info) }.getOrNull()?.takeIf(filter)
        }
}

private class JavaSoundOutput(private val device: MidiDevice) : MidiOutputPort {
    private val openedHere = !device.isOpen
    private val receiver: Receiver

    init {
        if (openedHere) device.open()
        receiver = device.receiver
    }

    override fun send(message: MidiMessage) {
        receiver.send(ShortMessage(message.status, message.data1, message.data2), -1)
    }

    override fun close() {
        receiver.close()
        if (openedHere) device.close()
    }
}

private class JavaSoundInput(private val device: MidiDevice) : MidiInputPort {
    private val openedHere = !device.isOpen
    private val transmitter: Transmitter
    private val flow = MutableSharedFlow<MidiMessage>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override val messages: Flow<MidiMessage> = flow.asSharedFlow()

    init {
        if (openedHere) device.open()
        transmitter = device.transmitter
        transmitter.receiver = object : Receiver {
            override fun send(message: JavaMidiMessage, timeStamp: Long) {
                if (message is ShortMessage) flow.tryEmit(MidiMessage(message.status, message.data1, message.data2))
            }

            override fun close() = Unit
        }
    }

    override fun close() {
        transmitter.close()
        if (openedHere) device.close()
    }
}
