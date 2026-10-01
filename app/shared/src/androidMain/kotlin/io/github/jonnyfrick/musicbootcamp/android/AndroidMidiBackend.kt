package io.github.jonnyfrick.musicbootcamp.android

import android.content.Context
import android.content.pm.PackageManager
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiReceiver
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import io.github.jonnyfrick.musicbootcamp.core.midi.MidiMessage
import io.github.jonnyfrick.musicbootcamp.core.midi.MidiParser
import io.github.jonnyfrick.musicbootcamp.platform.MidiBackend
import io.github.jonnyfrick.musicbootcamp.platform.MidiInputPort
import io.github.jonnyfrick.musicbootcamp.platform.MidiOutputPort
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * MIDI devices through `android.media.midi` (USB keyboards, and other apps' MIDI services), plus
 * the app's own piano as an output that is always there.
 *
 * Android calls "input port" what receives data (a device's input: our output) and "output port"
 * what sends data (a keyboard's output: our input).
 */
class AndroidMidiBackend(context: Context, private val piano: AndroidPianoSynth) : MidiBackend {
    private val manager: MidiManager? =
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI)) context.getSystemService(MidiManager::class.java) else null

    /** Android delivers MIDI callbacks on this thread, so opening a device never waits for the UI thread. */
    private val handler = Handler(HandlerThread("MIDI").apply { start() }.looper)

    override val unavailableReason: String? = null

    override fun inputDevices(): List<String> = named(devices().filter { it.outputPortCount > 0 }).map { it.first }

    override fun outputDevices(): List<String> =
        listOf(piano.deviceName) + named(devices().filter { it.inputPortCount > 0 }).map { it.first }

    override val devicesChanged: Flow<Unit> = callbackFlow {
        val callback = object : MidiManager.DeviceCallback() {
            override fun onDeviceAdded(device: MidiDeviceInfo) {
                trySend(Unit)
            }

            override fun onDeviceRemoved(device: MidiDeviceInfo) {
                trySend(Unit)
            }
        }
        manager?.registerDeviceCallback(callback, handler)
        awaitClose { manager?.unregisterDeviceCallback(callback) }
    }

    override fun openInput(name: String): MidiInputPort {
        val info = named(devices().filter { it.outputPortCount > 0 }).firstOrNull { it.first == name }?.second
            ?: throw IOException("MIDI device $name is not connected.")
        val device = open(info)
        val port = device.openOutputPort(0) ?: run {
            device.close()
            throw IOException("MIDI device $name is in use by another app.")
        }
        val messages = MutableSharedFlow<MidiMessage>(extraBufferCapacity = 256)
        val parser = MidiParser()
        val receiver = object : MidiReceiver() {
            override fun onSend(data: ByteArray, offset: Int, count: Int, timestamp: Long) {
                parser.parse(data, offset, count).forEach { messages.tryEmit(it) }
            }
        }
        port.connect(receiver)
        return object : MidiInputPort {
            override val messages: Flow<MidiMessage> = messages

            override fun close() {
                runCatching { port.disconnect(receiver) }
                runCatching { port.close() }
                runCatching { device.close() }
            }
        }
    }

    override fun openOutput(name: String): MidiOutputPort {
        if (name == piano.deviceName) return piano.open(PIANO_SAMPLE_RATE)
        val info = named(devices().filter { it.inputPortCount > 0 }).firstOrNull { it.first == name }?.second
            ?: throw IOException("MIDI device $name is not connected.")
        val device = open(info)
        val port = device.openInputPort(0) ?: run {
            device.close()
            throw IOException("MIDI device $name is in use by another app.")
        }
        val encoder = MidiParser()
        return object : MidiOutputPort {
            override fun send(message: MidiMessage) {
                val bytes = encoder.encode(message)
                runCatching { port.send(bytes, 0, bytes.size) }
            }

            override fun close() {
                runCatching { port.close() }
                runCatching { device.close() }
            }
        }
    }

    @Suppress("DEPRECATION") // getDevices() still lists byte-stream devices, which are all we use
    private fun devices(): List<MidiDeviceInfo> {
        val manager = manager ?: return emptyList()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.getDevicesForTransport(MidiManager.TRANSPORT_MIDI_BYTE_STREAM).toList()
        } else {
            manager.devices.toList()
        }
    }

    /** Devices with a readable name each, numbered where several are called the same. */
    private fun named(devices: List<MidiDeviceInfo>): List<Pair<String, MidiDeviceInfo>> =
        devices.map { info ->
            val properties = info.properties
            val name = properties.getString(MidiDeviceInfo.PROPERTY_NAME)
                ?: listOfNotNull(properties.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER), properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT))
                    .joinToString(" ").ifBlank { "MIDI device ${info.id}" }
            name to info
        }.groupBy { it.first }.flatMap { (name, same) ->
            if (same.size == 1) same else same.mapIndexed { i, (_, info) -> "$name ${i + 1}" to info }
        }

    /** Opens [info], waiting for Android's callback (on the MIDI thread) for at most a few seconds. */
    private fun open(info: MidiDeviceInfo): MidiDevice {
        val manager = manager ?: throw IOException("This device does not support MIDI.")
        val opened = AtomicReference<MidiDevice?>()
        val done = CountDownLatch(1)
        manager.openDevice(info, { device ->
            opened.set(device)
            done.countDown()
        }, handler)
        if (!done.await(OPEN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) throw IOException("The MIDI device did not respond.")
        return opened.get() ?: throw IOException("The MIDI device could not be opened.")
    }

    private companion object {
        const val PIANO_SAMPLE_RATE = 44_100
        const val OPEN_TIMEOUT_SECONDS = 5L
    }
}
