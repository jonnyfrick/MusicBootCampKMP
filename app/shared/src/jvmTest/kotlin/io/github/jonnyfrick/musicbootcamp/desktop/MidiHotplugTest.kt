package io.github.jonnyfrick.musicbootcamp.desktop

import io.github.jonnyfrick.musicbootcamp.core.midi.MidiMessage
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A MIDI keyboard plugged in while the app runs must show up, deliver key presses and
 * disappear again when unplugged. On macOS a virtual CoreMIDI source (a small Swift
 * program) plays the keyboard; elsewhere the test is skipped.
 */
class MidiHotplugTest {

    private val deviceName = "MBC Hotplug Test"

    @Test
    fun keyboardPluggedInWhileRunningIsFoundAndUsable() = runBlocking {
        assumeTrue("macOS only", System.getProperty("os.name").lowercase().contains("mac"))
        val swift = File("/usr/bin/swiftc")
        assumeTrue("swiftc not available", swift.canExecute())

        val directory = Files.createTempDirectory("midi-hotplug").toFile()
        try {
            val source = File(directory, "VirtualMidiSource.swift")
            source.writeText(javaClass.getResource("/VirtualMidiSource.swift")!!.readText())
            val binary = File(directory, "vmidi")
            val compile = ProcessBuilder(swift.path, "-O", "-o", binary.path, source.path).redirectErrorStream(true).start()
            assumeTrue("could not compile the virtual keyboard", compile.waitFor(120, TimeUnit.SECONDS) && compile.exitValue() == 0)

            val midi = JavaSoundMidiBackend()
            assertFalse(deviceName in midi.inputDevices(), "not there before it is plugged in")

            // The keyboard is "plugged in" now, while the backend is already in use.
            val plugged = async(start = CoroutineStart.UNDISPATCHED) { midi.devicesChanged.first() }
            val keyboard = ProcessBuilder(binary.path, deviceName, "3").redirectErrorStream(true).start()
            try {
                withTimeout(10_000) { plugged.await() }
                assertTrue(deviceName in midi.inputDevices(), "found after plugging in: ${midi.inputDevices()}")

                val input = midi.openInput(deviceName)
                val press = withTimeout(10_000) { input.messages.first { it.isNoteOn } }
                assertEquals(MidiMessage.noteOn(60, 100), press)

                val unplugged = async(start = CoroutineStart.UNDISPATCHED) { midi.devicesChanged.first() }
                input.close()
                withTimeout(10_000) { unplugged.await() }
                assertFalse(deviceName in midi.inputDevices(), "gone after unplugging: ${midi.inputDevices()}")
            } finally {
                keyboard.destroy()
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
