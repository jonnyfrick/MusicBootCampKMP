package io.github.jonnyfrick.musicbootcamp.desktop

import io.github.jonnyfrick.musicbootcamp.core.legacy.LegacyImport
import io.github.jonnyfrick.musicbootcamp.core.persistence.SetupRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Imports the real Java files, stores them as JSON files on disk and loads them back. */
class DesktopPersistenceTest {

    private val legacyFolder = File("../../core/src/jvmTest/resources/golden")

    @Test
    fun concurrentWritesOfTheSameDocumentDoNotFail() = runBlocking {
        val directory = Files.createTempDirectory("musicbootcamp-test").toFile()
        try {
            val store = FileDocumentStore(directory)
            // The first legacy import saved the preferences twice at once, which crashed the app.
            (1..50).map { i -> async(Dispatchers.Default) { store.write("preferences.json", "{\"n\":$i}") } }.awaitAll()
            assertEquals(listOf("preferences.json"), store.list())
            assertTrue(store.read("preferences.json")!!.startsWith("{\"n\":"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun importedSetupsSurviveTheFileStore() = runTest {
        // The learned sequences are personal data and not in git; see core's Golden.LEARNED_DATA_HINT.
        assumeTrue(
            "learned sequences not present",
            File(legacyFolder, "learned_sequences_settings_lin.xml").isFile &&
                File(legacyFolder, "learned_sequences_settings_two_voices_mid_range.xml").isFile,
        )
        val directory = Files.createTempDirectory("musicbootcamp-test").toFile()
        try {
            val repository = SetupRepository(FileDocumentStore(directory))
            for (settingsFile in listOf("settings_lin.xml", "settings_two_voices_mid_range.xml")) {
                val result = LegacyImport.importSetup(
                    name = settingsFile.removeSuffix(".xml"),
                    settingsXml = File(legacyFolder, settingsFile).readText(),
                    readSibling = { File(legacyFolder, it).takeIf(File::isFile)?.readText() },
                )
                assertTrue(result.setup.memory().size > 0, "$settingsFile has learned sequences")
                repository.save(result.setup)

                val loaded = repository.load(result.setup.name)!!
                assertEquals(result.setup.settings, loaded.settings)
                assertEquals(result.setup.memory().canonicalText(), loaded.memory().canonicalText())

                val xmlSize = File(legacyFolder, result.learnedSequencesFileName!!).length()
                val jsonSize = directory.listFiles()!!.single { it.name.contains(result.setup.name) }.length()
                println("$settingsFile: ${result.setup.memory().size} sequences, XML $xmlSize bytes -> JSON $jsonSize bytes")
            }
            assertEquals(listOf("settings_lin", "settings_two_voices_mid_range"), repository.setupNames())
        } finally {
            directory.deleteRecursively()
        }
    }
}
