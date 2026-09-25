package io.github.jonnyfrick.musicbootcamp.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import io.github.jonnyfrick.musicbootcamp.core.legacy.LegacyImport
import io.github.jonnyfrick.musicbootcamp.core.midi.MidiMessage
import io.github.jonnyfrick.musicbootcamp.core.persistence.InMemoryDocumentStore
import io.github.jonnyfrick.musicbootcamp.core.persistence.SetupRepository
import io.github.jonnyfrick.musicbootcamp.platform.MidiBackend
import io.github.jonnyfrick.musicbootcamp.platform.MidiInputPort
import io.github.jonnyfrick.musicbootcamp.platform.MidiOutputPort
import io.github.jonnyfrick.musicbootcamp.platform.PlatformServices
import io.github.jonnyfrick.musicbootcamp.ui.App
import io.github.jonnyfrick.musicbootcamp.platform.AudioInputBackend
import io.github.jonnyfrick.musicbootcamp.platform.AudioInputPort
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Renders every tab of the real UI off-screen into `build/screenshots`, with an
 * imported Java setup and a fake MIDI system, so the layout can be checked
 * without a display. Also presses Go! and lets the exercise run a few steps.
 */
@OptIn(ExperimentalComposeUiApi::class)
class ScreenshotTest {

    /** The shareable synthetic data set, so the screenshots never depend on personal data. */
    private val legacyFolder = File("../../core/src/jvmTest/resources/golden/synthetic")
    private val output = File("build/screenshots").apply { mkdirs() }

    private class FakeMidi : MidiBackend {
        val sent = mutableListOf<MidiMessage>()
        val keyboard = MutableSharedFlow<MidiMessage>(extraBufferCapacity = 16)
        override val unavailableReason: String? = null
        override fun inputDevices() = listOf("Real Time Sequencer", "Digital Piano")
        override fun outputDevices() = listOf("Gervill", "Digital Piano")
        override fun openInput(name: String) = object : MidiInputPort {
            override val messages = keyboard
            override fun close() = Unit
        }
        override fun openOutput(name: String) = object : MidiOutputPort {
            override fun send(message: MidiMessage) {
                synchronized(sent) { sent += message }
            }
            override fun close() = Unit
        }
    }

    @Test
    fun renderAllScreens() {
        val store = InMemoryDocumentStore()
        runBlocking {
            val imported = LegacyImport.importSetup(
                "Demo",
                File(legacyFolder, "settings_mono.xml").readText(),
            ) { File(legacyFolder, it).takeIf(File::isFile)?.readText() }
            SetupRepository(store).save(imported.setup)
        }
        val midi = FakeMidi()
        val microphone = object : AudioInputBackend {
            override val unavailableReason: String? = null
            override fun devices() = listOf("Built-in Microphone")
            override fun open(name: String?) = object : AudioInputPort {
                override val sampleRate = 44_100
                override val blocks = flow { while (true) { emit(FloatArray(512)); delay(11) } }
                override fun close() = Unit
            }
        }
        val services = PlatformServices(midi, store, legacyFiles = { null }, audio = microphone)

        val scene = ImageComposeScene(width = 900, height = 1100, density = Density(1f)) { App(services) }
        var time = 0L
        fun settle(frames: Int = 20) = repeat(frames) {
            Thread.sleep(15)
            time += 16_000_000
            scene.render(time)
        }
        fun click(x: Float, y: Float) {
            scene.sendPointerEvent(PointerEventType.Press, Offset(x, y))
            scene.sendPointerEvent(PointerEventType.Release, Offset(x, y))
            settle()
        }
        fun snapshot(name: String) {
            settle()
            val png = scene.render(time).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File(output, "$name.png").writeBytes(png)
        }

        settle(60)
        snapshot("1-practice")

        val tabY = 88f // below the 64 px top bar
        click(337f, tabY); snapshot("2-exercise")
        click(562f, tabY); snapshot("3-memory")
        click(787f, tabY); snapshot("4-preferences")

        // MIDI test: keys arrive from the keyboard, the test note goes to MIDI Out.
        click(TEST_MIDI_X, MIDI_TEST_BUTTONS_Y)
        listOf(60 to 90, 64 to 70, 67 to 110).forEach { (note, velocity) ->
            midi.keyboard.tryEmit(MidiMessage.noteOn(note, velocity))
            midi.keyboard.tryEmit(MidiMessage.noteOff(note)) // releases are not listed
            settle(5)
        }
        click(PLAY_TEST_NOTE_X, MIDI_TEST_BUTTONS_Y)
        Thread.sleep(1300)
        snapshot("4c-preferences-midi-test")
        val testTone = synchronized(midi.sent) { midi.sent.filter { it.data1 == 69 && (it.isNoteOn || it.command == 0x80) } }
        assertEquals(listOf(MidiMessage.noteOn(69, 80), MidiMessage.noteOff(69)), testTone, "test note A4 on and off")
        click(TEST_MIDI_X, MIDI_TEST_BUTTONS_Y) // "Stop test"
        synchronized(midi.sent) { midi.sent.clear() }
        click(MICROPHONE_RADIO_X, MICROPHONE_RADIO_Y); snapshot("4b-preferences-microphone")
        click(MIDI_RADIO_X, MIDI_RADIO_Y)

        click(112f, tabY)
        click(142f, 396f) // Go!
        settle(40)
        Thread.sleep(3000) // synthetic mono setup: one step every 1.2 s
        settle(40)
        snapshot("5-running")
        click(230f, 396f) // Stop
        settle(40)
        Thread.sleep(300)
        settle(20)

        scene.close()
        val noteOns = synchronized(midi.sent) { midi.sent.count { it.isNoteOn } }
        println("MIDI messages sent: ${midi.sent.size}, note-ons: $noteOns")
        assertTrue(noteOns >= 2, "the exercise should have played notes")
    }
}

// Buttons of the MIDI test on the Preferences tab (MIDI input selected).
private const val TEST_MIDI_X = 162f
private const val PLAY_TEST_NOTE_X = 306f
private const val MIDI_TEST_BUTTONS_Y = 480f

// Radio buttons of the Input section on the Preferences tab.
private const val MIDI_RADIO_X = 118f
private const val MIDI_RADIO_Y = 194f
private const val MICROPHONE_RADIO_X = 118f
private const val MICROPHONE_RADIO_Y = 222f
