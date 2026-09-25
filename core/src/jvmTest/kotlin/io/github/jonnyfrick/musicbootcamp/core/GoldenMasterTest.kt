package io.github.jonnyfrick.musicbootcamp.core

import io.github.jonnyfrick.musicbootcamp.core.Golden.int
import io.github.jonnyfrick.musicbootcamp.core.Golden.string
import io.github.jonnyfrick.musicbootcamp.core.learning.LearnedSequences
import io.github.jonnyfrick.musicbootcamp.core.legacy.LegacyImport
import io.github.jonnyfrick.musicbootcamp.core.midi.MidiMessage
import io.github.jonnyfrick.musicbootcamp.core.midi.NoteNames
import io.github.jonnyfrick.musicbootcamp.core.midi.Tuning
import io.github.jonnyfrick.musicbootcamp.core.practice.PracticeSession
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Compares the Kotlin port with outputs recorded from the unchanged Java code.
 * See `MIGRATION.md` for how the fixtures are produced.
 */
class GoldenMasterTest {

    private companion object {
        const val SYNTHETIC = "synthetic/"
        val SYNTHETIC_LEARNED_FILES = listOf("learned_sequences_settings_mono.xml", "learned_sequences_settings_two_voices.xml")
    }

    // Fixtures come in two sets:
    //  - golden/synthetic/: a small generated data set that is in git, so these tests always run;
    //  - golden/: your own settings (in git) and learned sequences (not in git, tests skipped without them).

    @Test
    fun learnedSequenceFilesImportExactlyLikeJava() {
        checkLearnedImport(SYNTHETIC, SYNTHETIC_LEARNED_FILES)
    }

    @Test
    fun personalLearnedSequenceFilesImportExactlyLikeJava() {
        assumeTrue(Golden.LEARNED_DATA_HINT, Golden.learnedDataAvailable())
        checkLearnedImport("", Golden.learnedFiles)
    }

    private fun checkLearnedImport(dir: String, files: List<String>) {
        for (file in files) {
            val memory = LegacyImport.parseLearnedSequences(Golden.text(dir + file))
            val expected = Golden.textOrNull(dir + "canonical_" + file.removeSuffix(".xml") + ".txt")
            assumeTrue("canonical dump of $file missing — regenerate the fixtures", expected != null)
            expected!!
            Golden.assertSameLines(expected, memory.canonicalText(), file)
            assertEquals(expected.count { it == '\n' }, memory.size, "$file sequence count")
        }
    }

    @Test
    fun settingsFilesImportExactlyLikeJava() {
        checkSettingsImport(SYNTHETIC, checkLearned = true)
        // Your settings files are in git; their learned sequences only where present locally.
        checkSettingsImport("", checkLearned = Golden.learnedDataAvailable())
    }

    private fun checkSettingsImport(dir: String, checkLearned: Boolean) {
        for (entry in Golden.json(dir + "settings.json").jsonArray.map { it.jsonObject }) {
            val file = entry.string("settingsFile")
            val parameters = entry.getValue("replayParameters").jsonObject
            val result = LegacyImport.importSetup("imported", Golden.text(dir + file)) { Golden.textOrNull(dir + it) }

            assertEquals(Golden.practiceSettings(parameters), result.setup.settings, file)
            assertEquals(parameters.int("numberOfVoices"), result.setup.settings.mode.voices, "$file voices")
            assertEquals(parameters.int("numberOfVoices"), result.legacySettings.numberOfVoices, "$file voices in file")
            assertEquals(entry.getValue("referenceAHz").jsonPrimitive.double, result.legacySettings.referenceAHz, "$file reference A")
            assertEquals(entry.string("currentSettingsFilePath"), result.legacySettings.currentSettingsFilePath, file)
            assertEquals(entry.string("learnedSequencesFile"), result.legacySettings.learnedSequencesFileName, file)
            assertEquals(entry.string("midiInDevice"), result.legacySettings.midiInputDevice, file)
            assertEquals(entry.string("midiOutDevice"), result.legacySettings.midiOutputDevice, file)

            if (checkLearned) {
                val memory = result.setup.memory()
                assertEquals(entry.int("learnedCount"), memory.size, "$file learned count")
                assertEquals(entry.string("learnedSha256"), Golden.sha256(memory.canonicalText()), "$file learned content")
                assertEquals(emptyList(), result.warnings, "$file warnings")
            }
        }
    }

    @Test
    fun practiceScenariosReproduceJavaStepByStep() {
        for (file in listOf("scenario_mono_fresh.json", "scenario_two_voices_no_learning.json", "scenario_two_voices_fresh_learning.json")) {
            runScenario("", file)
        }
    }

    /** Embedding, practising and downgrading learned sequences, with the synthetic memory. */
    @Test
    fun practiceScenariosWithLearnedSequencesReproduceJavaStepByStep() {
        for (file in listOf("scenario_mono_memory.json", "scenario_mono_memory_no_learning.json", "scenario_two_voices_memory.json")) {
            runScenario(SYNTHETIC, file)
        }
    }

    /** The same with your real learned sequences. */
    @Test
    fun practiceScenariosWithPersonalLearnedSequencesReproduceJavaStepByStep() {
        assumeTrue(Golden.LEARNED_DATA_HINT, Golden.learnedDataAvailable())
        for (file in listOf("scenario_mono_lin_memory.json", "scenario_two_voices_memory.json")) runScenario("", file)
    }

    private fun runScenario(dir: String, file: String) {
        val scenario = Golden.json(dir + file).jsonObject
        val settings = Golden.practiceSettings(scenario.getValue("replayParameters").jsonObject)

        val initialFile = scenario.getValue("initialLearnedSequencesFile").takeIf { it != JsonNull }?.jsonPrimitive?.content
        val memory = initialFile?.let { LegacyImport.parseLearnedSequences(Golden.text(dir + it)) } ?: LearnedSequences()
        assertEquals(scenario.int("initialLearnedCount"), memory.size, "$file initial memory")
        assertEquals(scenario.string("initialLearnedSha256"), Golden.sha256(memory.canonicalText()), "$file initial memory")

        val random = ReplayRandomSource()
        val sent = mutableListOf<MidiMessage>()
        val session = PracticeSession(settings, memory, random) { sent += it }

        scenario.getValue("ticks").jsonArray.forEachIndexed { index, tickElement ->
            val tick = tickElement.jsonObject
            val context = "$file tick $index"
            Golden.ints(tick.getValue("input")).forEach { session.onMidiInput(MidiMessage.noteOn(it, 100)) }
            random.enqueue(tick.getValue("random").jsonArray)
            sent.clear()

            val result = session.step()

            random.assertAllConsumed(context)
            val expectedMidi = tick.getValue("midi").jsonArray.map { Golden.ints(it) }
            assertEquals(expectedMidi, sent.map { listOf(it.status, it.data1, it.data2) }, "$context MIDI output")
            assertEquals(Golden.ints(tick.getValue("positions")), result.given, "$context given notes")
            assertEquals(tick.int("learnedCount"), memory.size, "$context memory size")
        }

        assertEquals(scenario.int("finalLearnedCount"), memory.size, "$file final memory size")
        assertEquals(scenario.string("finalLearnedSha256"), Golden.sha256(memory.canonicalText()), "$file final memory")
    }

    @Test
    fun tuningMessagesMatchJava() {
        for (entry in Golden.json("tuning.json").jsonArray.map { it.jsonObject }) {
            val reference = entry.getValue("referenceAHz").jsonPrimitive.double
            val expected = entry.getValue("midi").jsonArray.map { Golden.ints(it) }
            assertEquals(expected, Tuning.messages(reference).map { listOf(it.status, it.data1, it.data2) }, "A = $reference Hz")
        }
    }

    @Test
    fun noteNamesMatchJava() {
        val expected = Golden.json("note_names.json").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(expected, (0..127).map { NoteNames.legacyName(it) })
    }
}
