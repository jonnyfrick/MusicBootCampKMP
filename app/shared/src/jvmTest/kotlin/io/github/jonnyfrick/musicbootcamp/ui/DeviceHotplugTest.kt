package io.github.jonnyfrick.musicbootcamp.ui

import io.github.jonnyfrick.musicbootcamp.core.persistence.InMemoryDocumentStore
import io.github.jonnyfrick.musicbootcamp.platform.MidiBackend
import io.github.jonnyfrick.musicbootcamp.platform.MidiInputPort
import io.github.jonnyfrick.musicbootcamp.platform.MidiOutputPort
import io.github.jonnyfrick.musicbootcamp.platform.PlatformServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

/** A keyboard plugged in while the app runs becomes the MIDI input at once; the output stays. */
class DeviceHotplugTest {
    private class PluggableMidi : MidiBackend {
        var inputs = listOf("Real Time Sequencer")
        var outputs = listOf("Gervill")
        override val devicesChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        override val unavailableReason: String? = null
        override fun inputDevices() = inputs
        override fun outputDevices() = outputs
        override fun openInput(name: String): MidiInputPort = error("not needed")
        override fun openOutput(name: String): MidiOutputPort = error("not needed")
    }

    @Test
    fun aKeyboardPluggedInIsChosenAsInput() = runBlocking {
        val midi = PluggableMidi()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = AppController(PlatformServices(midi, InMemoryDocumentStore(), legacyFiles = null), scope)
        controller.load()
        withTimeout(5_000) { while (!controller.ready) delay(10) }
        delay(100) // the device callback is collected

        midi.inputs = midi.inputs + "Digital Piano"
        midi.outputs = midi.outputs + "Digital Piano"
        midi.devicesChanged.emit(Unit)
        withTimeout(5_000) { while (controller.preferences.midiInputDevice == null) delay(10) }

        assertEquals("Digital Piano", controller.preferences.midiInputDevice)
        assertEquals(null, controller.preferences.midiOutputDevice, "the output is not changed")
        scope.cancel()
    }
}
