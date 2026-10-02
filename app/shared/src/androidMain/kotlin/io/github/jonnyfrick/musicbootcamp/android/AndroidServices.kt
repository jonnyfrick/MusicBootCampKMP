package io.github.jonnyfrick.musicbootcamp.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.ApplicationInfo
import android.net.Uri
import io.github.jonnyfrick.musicbootcamp.core.audio.RecordingFile
import io.github.jonnyfrick.musicbootcamp.core.audio.Wav
import io.github.jonnyfrick.musicbootcamp.core.persistence.DocumentStore
import io.github.jonnyfrick.musicbootcamp.platform.PlatformServices
import io.github.jonnyfrick.musicbootcamp.platform.RecordingStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Services of the Android app: setups in the app's private storage, recordings where a computer
 * can fetch them over USB (Android/data/<package>/files/recordings), the microphone, MIDI devices
 * and the app's own piano [PianoSynth][io.github.jonnyfrick.musicbootcamp.core.audio.PianoSynth]
 * as sound output. What needs the activity comes in as functions: [requestPermission] asks the
 * user for a permission, [pickFiles] lets them choose files (to import a setup of the Java
 * version), [launchChooser] shows a system dialog and returns its result (null if cancelled).
 */
fun androidServices(
    context: Context,
    requestPermission: suspend (String) -> Boolean,
    pickFiles: suspend () -> List<Uri>,
    launchChooser: suspend (IntentSender) -> Intent?,
): PlatformServices {
    val app = context.applicationContext
    val synth = AndroidPianoSynth()
    val midi = AndroidMidiBackend(app, synth)
    return PlatformServices(
        midi = midi,
        documents = FileDocumentStore(app.filesDir),
        legacyFiles = AndroidLegacyFilePicker(app, pickFiles),
        audio = AndroidAudioInput(app) { requestPermission(Manifest.permission.RECORD_AUDIO) },
        bluetoothMidi = if (AndroidBluetoothMidi.isSupported(app)) AndroidBluetoothMidi(app, midi, requestPermission, launchChooser) else null,
        recordings = FileRecordingStore(File(app.getExternalFilesDir(null) ?: app.filesDir, "recordings")),
        renderedSynth = synth,
        // Debug builds (installDebug, Android Studio) are debuggable, release builds are not.
        debugTools = app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
    )
}

/** One file per document; writes go through a temp file and a rename, one at a time. */
private class FileDocumentStore(private val directory: File) : DocumentStore {
    private val writeLock = Mutex()

    override suspend fun read(name: String): String? = withContext(Dispatchers.IO) {
        file(name).takeIf { it.isFile }?.readText()
    }

    override suspend fun write(name: String, content: String) = writeLock.withLock {
        withContext(Dispatchers.IO) {
            directory.mkdirs()
            val temp = File(directory, "$name.tmp")
            temp.writeText(content)
            // A rename within one file system replaces the target atomically.
            if (!temp.renameTo(file(name))) {
                temp.delete()
                throw IOException("Could not save $name")
            }
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
        require('/' !in name && name != "..") { "Invalid document name $name" }
        return File(directory, name)
    }
}

/** Recordings as `session-<date>_<time>.wav` plus `.json` in [directory]. */
private class FileRecordingStore(private val directory: File) : RecordingStore {
    override val location: String get() = directory.path

    override fun create(): RecordingFile {
        directory.mkdirs()
        val base = "session-" + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(Date())
        return FileRecording(File(directory, "$base.wav"), File(directory, "$base.json"))
    }
}

private class FileRecording(private val wav: File, private val log: File) : RecordingFile {
    private val out = BufferedOutputStream(FileOutputStream(wav), 1 shl 16).apply { write(ByteArray(Wav.HEADER_SIZE)) }

    override val name: String get() = wav.path

    override fun append(bytes: ByteArray) = out.write(bytes)

    override fun finish(wavHeader: ByteArray, log: String) {
        out.close()
        RandomAccessFile(wav, "rw").use { it.write(wavHeader) }
        this.log.writeText(log)
    }
}
