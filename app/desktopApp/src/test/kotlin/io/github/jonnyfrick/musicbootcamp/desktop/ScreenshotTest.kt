package io.github.jonnyfrick.musicbootcamp.desktop

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.github.jonnyfrick.musicbootcamp.core.legacy.LegacyImport
import io.github.jonnyfrick.musicbootcamp.core.midi.MidiMessage
import io.github.jonnyfrick.musicbootcamp.core.persistence.InMemoryDocumentStore
import io.github.jonnyfrick.musicbootcamp.core.persistence.SetupRepository
import io.github.jonnyfrick.musicbootcamp.platform.AudioInputBackend
import io.github.jonnyfrick.musicbootcamp.platform.AudioInputPort
import io.github.jonnyfrick.musicbootcamp.platform.MidiBackend
import io.github.jonnyfrick.musicbootcamp.platform.MidiInputPort
import io.github.jonnyfrick.musicbootcamp.platform.MidiOutputPort
import io.github.jonnyfrick.musicbootcamp.platform.PlatformServices
import io.github.jonnyfrick.musicbootcamp.ui.App
import io.github.jonnyfrick.musicbootcamp.ui.AppController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Renders the real UI off-screen into `build/screenshots` in the three window sizes Material
 * designs for (phone, tablet, desktop), with an imported Java setup and a fake MIDI system, so
 * the layouts can be checked without a display. Finds its way by the texts on screen, like a
 * user, and also presses Start and lets the exercise run a few steps.
 */
@OptIn(ExperimentalTestApi::class)
class ScreenshotTest {

    /** The shareable synthetic data set, so the screenshots never depend on personal data. */
    private val legacyFolder = File("../../core/src/jvmTest/resources/golden/synthetic")
    private val output = File("build/screenshots").apply { mkdirs() }
    private val defaultLocale = Locale.getDefault()

    @AfterTest
    fun restoreLocale() = Locale.setDefault(defaultLocale)

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

    private val microphone = object : AudioInputBackend {
        override val unavailableReason: String? = null
        override fun devices() = listOf("Built-in Microphone")
        override fun open(name: String?) = object : AudioInputPort {
            override val sampleRate = 44_100
            override val blocks = flow { while (true) { emit(FloatArray(512)); delay(11) } }
            override fun close() = Unit
        }
    }

    /** A store with one setup imported from the synthetic Java files, plus [preferences] (JSON). */
    private fun store(setup: String, file: String, preferences: String? = null) = InMemoryDocumentStore().also { store ->
        runBlocking {
            val imported = LegacyImport.importSetup(setup, File(legacyFolder, file).readText()) {
                File(legacyFolder, it).takeIf(File::isFile)?.readText()
            }
            SetupRepository(store).save(imported.setup)
            preferences?.let { store.write("preferences.json", it) }
        }
    }

    private fun services(midi: MidiBackend, store: InMemoryDocumentStore, debugTools: Boolean = true) = PlatformServices(
        midi, store, legacyFiles = { null }, audio = microphone,
        // Never records anything: the recording switch stays off in the screenshots.
        recordings = FileRecordingStore(File("build/dev-data/recordings")),
        debugTools = debugTools,
    )

    /** Shows the app in a window of [width] × [height] dp and waits until it has loaded. */
    private fun app(width: Int, height: Int, services: PlatformServices, locale: Locale = Locale.ENGLISH, test: ComposeUiTest.() -> Unit) {
        Locale.setDefault(locale)
        runDesktopComposeUiTest(width, height) {
            lateinit var controller: AppController
            setContent {
                val scope = rememberCoroutineScope()
                controller = remember { AppController(services, scope) }
                App(controller)
            }
            waitUntil(timeoutMillis = 10_000) { controller.ready }
            test()
        }
    }

    private fun ComposeUiTest.snapshot(name: String) {
        waitForIdle()
        // Dialogs and sheets are roots of their own, drawn over the window: the last one is on top.
        val roots = onAllNodes(isRoot()).fetchSemanticsNodes()
        val bitmap = onAllNodes(isRoot())[roots.size - 1].captureToImage().asSkiaBitmap()
        File(output, "$name.png").writeBytes(Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }

    /** The clickable element showing [text] (a label can also appear as a heading). */
    private fun ComposeUiTest.clickable(text: String): SemanticsNodeInteraction =
        onAllNodes(hasText(text) and hasClickAction(), useUnmergedTree = false).onFirst()

    private fun ComposeUiTest.click(text: String) {
        val node = clickable(text)
        // Only content scrolls; navigation items and app bars have no scrolling parent.
        runCatching { node.performScrollTo() }
        node.performClick()
        waitForIdle()
    }

    /**
     * Lets [millis] pass, both on the test's virtual clock (coroutines of the UI, e.g. the test
     * note's release) and in real time (the exercise's own clock).
     */
    private fun ComposeUiTest.wait(millis: Long) {
        Thread.sleep(millis)
        mainClock.advanceTimeBy(millis)
        waitForIdle()
    }

    private fun ComposeUiTest.clickIcon(description: String) {
        onAllNodes(hasContentDescription(description) and hasClickAction()).onFirst().performClick()
        waitForIdle()
    }

    @Test
    fun phone() {
        val midi = FakeMidi()
        app(width = 412, height = 915, services(midi, store("Demo", "settings_mono.xml"))) {
            snapshot("phone-1-practice")

            click("Memory")
            snapshot("phone-3-memory")

            click("Settings")
            snapshot("phone-4-settings")
            click("Input")
            click("Microphone (acoustic piano)")
            snapshot("phone-5-settings-input-microphone")
            clickIcon("Back")

            // MIDI test: keys arrive from the keyboard, the test note goes to MIDI Out.
            click("MIDI devices")
            click("Test MIDI")
            listOf(60 to 90, 64 to 70, 67 to 110).forEach { (note, velocity) ->
                midi.keyboard.tryEmit(MidiMessage.noteOn(note, velocity))
                midi.keyboard.tryEmit(MidiMessage.noteOff(note)) // releases are not listed
                waitForIdle()
            }
            click("Play test note")
            wait(1300)
            snapshot("phone-6-settings-midi-test")
            val testTone = synchronized(midi.sent) { midi.sent.filter { it.data1 == 69 && (it.isNoteOn || it.command == 0x80) } }
            assertEquals(listOf(MidiMessage.noteOn(69, 80), MidiMessage.noteOff(69)), testTone, "test note A4 on and off")
            click("Stop test")
            synchronized(midi.sent) { midi.sent.clear() }
            clickIcon("Back")

            click("Recognition")
            click("Optimization mode")
            snapshot("phone-7-settings-recognition")
            click("Optimization mode")
            clickIcon("Back")
            click("Input")
            click("MIDI keyboard")

            click("Practice")
            clickIcon("Start")
            wait(3000) // synthetic mono setup: one step every 1.2 s
            snapshot("phone-8-running")
            clickIcon("Stop")
            wait(300)

            clickIcon("Customize exercise")
            snapshot("phone-2-exercise-sheet")
        }
        val noteOns = synchronized(midi.sent) { midi.sent.count { it.isNoteOn } }
        assertTrue(noteOns >= 2, "the exercise should have played notes, sent: $noteOns")
    }

    @Test
    fun phoneInGerman() {
        app(width = 412, height = 915, services(FakeMidi(), store("Demo", "settings_mono.xml")), locale = Locale.GERMAN) {
            snapshot("phone-de-1-practice")
            click("Einstellungen")
            click("Eingabe")
            click("Mikrofon (akustisches Klavier)")
            snapshot("phone-de-2-settings-input")
        }
    }

    @Test
    fun aReleaseBuildHasNoDeveloperTools() {
        // Even if a debug build stored the optimization mode on this device.
        val preferences = """{"lastSetup":"Demo","inputSource":"MICROPHONE","optimizationMode":true,"detectionParametersRevision":1}"""
        app(width = 412, height = 915, services(FakeMidi(), store("Demo", "settings_mono.xml", preferences), debugTools = false)) {
            clickIcon("More options")
            assertTrue(onAllNodesWithText("Save setup as…").fetchSemanticsNodes().isNotEmpty())
            assertTrue(onAllNodesWithText("Import setup from MusicBootCamp (Java)…").fetchSemanticsNodes().isEmpty(), "no Java import")
            click("Save setup as…")
            click("Cancel")
            click("Settings")
            snapshot("phone-9-settings-release")
            assertTrue(onAllNodesWithText("Recognition").fetchSemanticsNodes().isEmpty(), "no recognition tuning")
            onNodeWithText("Tuning").assertExists()
        }
    }

    @Test
    fun tablet() {
        app(width = 800, height = 1100, services(FakeMidi(), store("Demo", "settings_mono.xml"))) {
            snapshot("tablet-1-practice")
            click("Settings")
            snapshot("tablet-2-settings")
        }
    }

    @Test
    fun desktop() {
        app(width = 1200, height = 800, services(FakeMidi(), store("Demo", "settings_mono.xml"))) {
            snapshot("desktop-1-practice")
            click("Memory")
            snapshot("desktop-2-memory")
            click("Settings")
            snapshot("desktop-3-settings-input")
            click("MIDI devices")
            snapshot("desktop-4-settings-midi")
            click("Tuning")
            snapshot("desktop-5-settings-tuning")
        }
    }

    @Test
    fun desktopChordSettings() {
        // A two-voice setup with microphone input and optimization mode: the chord parameters and
        // the piano calibration appear instead of the single-note ones.
        val preferences = """{"lastSetup":"Duo","inputSource":"MICROPHONE","optimizationMode":true,"detectionParametersRevision":1}"""
        app(width = 1200, height = 2400, services(FakeMidi(), store("Duo", "settings_two_voices.xml", preferences))) {
            click("Settings")
            click("Recognition")
            snapshot("desktop-6-settings-chords")
            assertTrue(onAllNodesWithText("Piano calibration").fetchSemanticsNodes().isNotEmpty())
            onNodeWithText("Harmonic method").assertExists()
        }
    }
}
