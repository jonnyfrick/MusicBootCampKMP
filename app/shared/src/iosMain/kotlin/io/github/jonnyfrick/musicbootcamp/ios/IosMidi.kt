@file:OptIn(ExperimentalForeignApi::class)

package io.github.jonnyfrick.musicbootcamp.ios

import io.github.jonnyfrick.musicbootcamp.core.midi.MidiMessage
import io.github.jonnyfrick.musicbootcamp.core.midi.MidiParser
import io.github.jonnyfrick.musicbootcamp.platform.MidiBackend
import io.github.jonnyfrick.musicbootcamp.platform.MidiInputPort
import io.github.jonnyfrick.musicbootcamp.platform.MidiOutputPort
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFStringRefVar
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreMIDI.MIDIClientCreateWithBlock
import platform.CoreMIDI.MIDIClientRef
import platform.CoreMIDI.MIDIClientRefVar
import platform.CoreMIDI.MIDIEndpointRef
import platform.CoreMIDI.MIDIGetDestination
import platform.CoreMIDI.MIDIGetNumberOfDestinations
import platform.CoreMIDI.MIDIGetNumberOfSources
import platform.CoreMIDI.MIDIGetSource
import platform.CoreMIDI.MIDIInputPortCreateWithBlock
import platform.CoreMIDI.MIDIObjectGetStringProperty
import platform.CoreMIDI.MIDIOutputPortCreate
import platform.CoreMIDI.MIDIPacket
import platform.CoreMIDI.MIDIPacketList
import platform.CoreMIDI.MIDIPacketListAdd
import platform.CoreMIDI.MIDIPacketListInit
import platform.CoreMIDI.MIDIPacketNext
import platform.CoreMIDI.MIDIPortConnectSource
import platform.CoreMIDI.MIDIPortDispose
import platform.CoreMIDI.MIDIPortRefVar
import platform.CoreMIDI.MIDISend
import platform.CoreMIDI.kMIDIPropertyDisplayName
import platform.Foundation.CFBridgingRelease

/**
 * MIDI through CoreMIDI (keyboards over a USB adapter, network sessions, Bluetooth devices once
 * connected), plus the app's own piano as an output that is always there.
 */
internal class IosMidiBackend(private val piano: IosPianoSynth) : MidiBackend {
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    private val client: MIDIClientRef = memScoped {
        val ref = alloc<MIDIClientRefVar>()
        // CoreMIDI calls this whenever a device comes or goes.
        MIDIClientCreateWithBlock(cfString("MusicBootCamp"), ref.ptr) { _ -> changes.tryEmit(Unit) }
        ref.value
    }

    override val unavailableReason: String? = null

    override val devicesChanged: Flow<Unit> = changes

    override fun inputDevices(): List<String> = named(sources()).map { it.first }

    override fun outputDevices(): List<String> = listOf(piano.deviceName) + named(destinations()).map { it.first }

    override fun openInput(name: String): MidiInputPort {
        val source = named(sources()).firstOrNull { it.first == name }?.second
            ?: throw IllegalStateException("MIDI device $name is not connected.")
        val messages = MutableSharedFlow<MidiMessage>(extraBufferCapacity = 256)
        val parser = MidiParser()
        val port = memScoped {
            val ref = alloc<MIDIPortRefVar>()
            MIDIInputPortCreateWithBlock(client, cfString("MusicBootCamp in"), ref.ptr) { packetList, _ ->
                var packet: CPointer<MIDIPacket>? = packetList?.pointed?.packet
                repeat(packetList?.pointed?.numPackets?.toInt() ?: 0) {
                    val current = packet ?: return@repeat
                    val length = current.pointed.length.toInt()
                    val bytes = ByteArray(length) { current.pointed.data[it].toByte() }
                    parser.parse(bytes).forEach { messages.tryEmit(it) }
                    packet = MIDIPacketNext(current)
                }
            }
            ref.value
        }
        MIDIPortConnectSource(port, source, null)
        return object : MidiInputPort {
            override val messages: Flow<MidiMessage> = messages
            override fun close() {
                MIDIPortDispose(port)
            }
        }
    }

    override fun openOutput(name: String): MidiOutputPort {
        if (name == piano.deviceName) return piano.open(0)
        val destination = named(destinations()).firstOrNull { it.first == name }?.second
            ?: throw IllegalStateException("MIDI device $name is not connected.")
        val port = memScoped {
            val ref = alloc<MIDIPortRefVar>()
            MIDIOutputPortCreate(client, cfString("MusicBootCamp out"), ref.ptr)
            ref.value
        }
        val encoder = MidiParser()
        return object : MidiOutputPort {
            override fun send(message: MidiMessage) {
                val bytes = encoder.encode(message)
                memScoped {
                    val list = alloc<MIDIPacketList>()
                    val packet = MIDIPacketListInit(list.ptr)
                    bytes.usePinned { pinned ->
                        MIDIPacketListAdd(
                            list.ptr, sizeOf<MIDIPacketList>().toULong(), packet, 0u, bytes.size.toULong(),
                            pinned.addressOf(0).reinterpret<UByteVar>(),
                        )
                    }
                    MIDISend(port, destination, list.ptr)
                }
            }

            override fun close() {
                MIDIPortDispose(port)
            }
        }
    }

    private fun sources(): List<MIDIEndpointRef> = (0 until MIDIGetNumberOfSources().toInt()).map { MIDIGetSource(it.toULong()) }

    private fun destinations(): List<MIDIEndpointRef> = (0 until MIDIGetNumberOfDestinations().toInt()).map { MIDIGetDestination(it.toULong()) }

    /** Endpoints with a readable name each, numbered where several are called the same. */
    private fun named(endpoints: List<MIDIEndpointRef>): List<Pair<String, MIDIEndpointRef>> =
        endpoints.map { displayName(it) to it }.groupBy { it.first }.flatMap { (name, same) ->
            if (same.size == 1) same else same.mapIndexed { i, (_, endpoint) -> "$name ${i + 1}" to endpoint }
        }

    private fun displayName(endpoint: MIDIEndpointRef): String = memScoped {
        val ref = alloc<CFStringRefVar>()
        if (MIDIObjectGetStringProperty(endpoint, kMIDIPropertyDisplayName, ref.ptr) != 0) return "MIDI device"
        CFBridgingRelease(ref.value) as? String ?: "MIDI device"
    }

    private fun cfString(text: String): CFStringRef? = CFStringCreateWithCString(null, text, kCFStringEncodingUTF8)
}
