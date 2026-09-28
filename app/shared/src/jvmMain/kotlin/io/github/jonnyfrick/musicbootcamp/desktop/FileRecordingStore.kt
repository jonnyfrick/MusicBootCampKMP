package io.github.jonnyfrick.musicbootcamp.desktop

import io.github.jonnyfrick.musicbootcamp.core.audio.RecordingFile
import io.github.jonnyfrick.musicbootcamp.core.audio.Wav
import io.github.jonnyfrick.musicbootcamp.platform.RecordingStore
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Recordings as `session-<date>_<time>.wav` plus `.json` in [directory]. */
class FileRecordingStore(private val directory: File) : RecordingStore {
    override val location: String get() = directory.path

    override fun create(): RecordingFile {
        directory.mkdirs()
        val base = "session-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
        return FileRecording(File(directory, "$base.wav"), File(directory, "$base.json"))
    }
}

/**
 * Appends audio through a buffer; the WAV header is written first with length 0 (readers take
 * what is there if the app stops unexpectedly) and replaced by [finish].
 */
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
