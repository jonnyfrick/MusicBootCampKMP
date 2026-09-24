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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders every tab of the real UI off-screen into `build/screenshots`, with an
 * imported Java setup and a fake MIDI system, so the layout can be checked
 * without a display. Also presses Go! and lets the exercise run a few steps.
 */
@OptIn(ExperimentalComposeUiApi::class)
class ScreenshotTest {

    private val legacyFolder = File("../../core/src/jvmTest/resources/golden")
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
                "lin",
                File(legacyFolder, "settings_lin.xml").readText(),
            ) { File(legacyFolder, it).takeIf(File::isFile)?.readText() }
            SetupRepository(store).save(imported.setup)
        }
        val midi = FakeMidi()
        val services = PlatformServices(midi, store, legacyFiles = { null })

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

        click(112f, tabY)
        click(142f, 396f) // Go!
        settle(40)
        Thread.sleep(2000) // settings_lin: one step every 0.81 s
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
