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

    private val goldenFolder = File("../../core/src/jvmTest/resources/golden")

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

    /** Synthetic data set, always in git. */
    @Test
    fun importedSetupsSurviveTheFileStore() = runTest {
        checkImportRoundTrip(File(goldenFolder, "synthetic"), listOf("settings_mono.xml", "settings_two_voices.xml"))
    }

    /** Your own practice data, only where it is present locally (not in git). */
    @Test
    fun personalSetupsSurviveTheFileStore() = runTest {
        assumeTrue(
            "learned sequences not present",
            File(goldenFolder, "learned_sequences_settings_lin.xml").isFile &&
                File(goldenFolder, "learned_sequences_settings_two_voices_mid_range.xml").isFile,
        )
        checkImportRoundTrip(goldenFolder, listOf("settings_lin.xml", "settings_two_voices_mid_range.xml"))
    }

    private suspend fun checkImportRoundTrip(folder: File, settingsFiles: List<String>) {
        val directory = Files.createTempDirectory("musicbootcamp-test").toFile()
        try {
            val repository = SetupRepository(FileDocumentStore(directory))
            for (settingsFile in settingsFiles) {
                val result = LegacyImport.importSetup(
                    name = settingsFile.removeSuffix(".xml"),
                    settingsXml = File(folder, settingsFile).readText(),
                    readSibling = { File(folder, it).takeIf(File::isFile)?.readText() },
                )
                assertTrue(result.setup.memory().size > 0, "$settingsFile has learned sequences")
                repository.save(result.setup)

                val loaded = repository.load(result.setup.name)!!
                assertEquals(result.setup.settings, loaded.settings)
                assertEquals(result.setup.memory().canonicalText(), loaded.memory().canonicalText())

                val xmlSize = File(folder, result.learnedSequencesFileName!!).length()
                val jsonSize = directory.listFiles()!!.single { it.name.contains(result.setup.name) }.length()
                println("$settingsFile: ${result.setup.memory().size} sequences, XML $xmlSize bytes -> JSON $jsonSize bytes")
            }
            assertEquals(settingsFiles.map { it.removeSuffix(".xml") }, repository.setupNames())
        } finally {
            directory.deleteRecursively()
        }
    }
}
