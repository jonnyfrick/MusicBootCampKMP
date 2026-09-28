package io.github.jonnyfrick.musicbootcamp.desktop

import io.github.jonnyfrick.musicbootcamp.core.persistence.DocumentStore
import io.github.jonnyfrick.musicbootcamp.platform.LegacyFilePicker
import io.github.jonnyfrick.musicbootcamp.platform.LegacySelection
import io.github.jonnyfrick.musicbootcamp.platform.PlatformServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Services of the desktop (JVM) app. */
fun desktopServices(dataDirectory: File = defaultDataDirectory()) = PlatformServices(
    midi = JavaSoundMidiBackend(),
    documents = FileDocumentStore(dataDirectory),
    legacyFiles = AwtLegacyFilePicker(),
    audio = JavaSoundAudioInput(),
    recordings = FileRecordingStore(File(dataDirectory, "recordings")),
)

/**
 * macOS: ~/Library/Application Support, Windows: %APPDATA%, else $XDG_DATA_HOME or ~/.local/share.
 * The system property `musicbootcamp.dataDir` overrides it (for test runs).
 */
fun defaultDataDirectory(): File {
    System.getProperty("musicbootcamp.dataDir")?.let { return File(it) }
    val home = File(System.getProperty("user.home"))
    val os = System.getProperty("os.name").lowercase()
    return when {
        "mac" in os -> File(home, "Library/Application Support/MusicBootCamp")
        "win" in os -> File(System.getenv("APPDATA") ?: home.path, "MusicBootCamp")
        else -> File(System.getenv("XDG_DATA_HOME") ?: File(home, ".local/share").path, "musicbootcamp")
    }
}

/**
 * One file per document in [directory]. Writes go through their own temp file and an
 * atomic move, one at a time, so neither a crash nor concurrent saves can leave half a file.
 */
class FileDocumentStore(private val directory: File) : DocumentStore {
    private val writeLock = Mutex()

    override suspend fun read(name: String): String? = withContext(Dispatchers.IO) {
        file(name).takeIf { it.isFile }?.readText()
    }

    override suspend fun write(name: String, content: String) = writeLock.withLock {
        withContext(Dispatchers.IO) {
            val target = file(name)
            directory.mkdirs()
            val temp = File.createTempFile("$name.", ".tmp", directory)
            try {
                temp.writeText(content)
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                temp.delete()
            }
            Unit
        }
    }

    override suspend fun delete(name: String) = withContext(Dispatchers.IO) {
        file(name).delete()
        Unit
    }

    override suspend fun list(): List<String> = withContext(Dispatchers.IO) {
        directory.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") }?.map { it.name }?.sorted() ?: emptyList()
    }

    private fun file(name: String): File {
        require('/' !in name && '\\' !in name && name != "..") { "Invalid document name $name" }
        return File(directory, name)
    }
}

/** Lets the user pick a settings XML of the Java version with the native file dialog. */
class AwtLegacyFilePicker : LegacyFilePicker {
    override suspend fun pickSettingsFile(): LegacySelection? {
        val chosen = withContext(Dispatchers.Swing) {
            val dialog = FileDialog(null as Frame?, "Import setup from MusicBootCamp (Java) – choose a settings XML", FileDialog.LOAD)
            dialog.setFilenameFilter { _, name -> name.endsWith(".xml", ignoreCase = true) }
            dialog.isVisible = true
            val name = dialog.file ?: return@withContext null
            File(dialog.directory, name)
        } ?: return null

        return withContext(Dispatchers.IO) {
            val folder = chosen.parentFile
            LegacySelection(
                suggestedName = chosen.nameWithoutExtension.removePrefix("settings_").ifEmpty { chosen.nameWithoutExtension },
                settingsXml = chosen.readText(),
                // Java stored paths relative to its working directory, URL-encoded (e.g. %20).
                readSibling = { relative ->
                    listOf(relative, java.net.URLDecoder.decode(relative, Charsets.UTF_8))
                        .map { File(folder, it) }
                        .firstOrNull { it.isFile }
                        ?.readText()
                },
            )
        }
    }
}
