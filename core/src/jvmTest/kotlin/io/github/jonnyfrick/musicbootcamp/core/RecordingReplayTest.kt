package io.github.jonnyfrick.musicbootcamp.core

import io.github.jonnyfrick.musicbootcamp.core.audio.RecordingEventType
import io.github.jonnyfrick.musicbootcamp.core.audio.RecordingLog
import io.github.jonnyfrick.musicbootcamp.core.audio.Wav
import io.github.jonnyfrick.musicbootcamp.core.midi.NoteNames
import io.github.jonnyfrick.musicbootcamp.core.midi.Tuning
import io.github.jonnyfrick.musicbootcamp.core.persistence.SetupRepository
import io.github.jonnyfrick.musicbootcamp.core.pitch.NoteTracker
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test

/**
 * Replays recorded sessions (Preferences → "Record exercises") through the pitch detection and
 * prints, step by step, what the app played, what was recognised live and what is recognised now.
 *
 * ./gradlew :core:jvmTest --tests '*RecordingReplayTest*' --rerun -Pmusicbootcamp.recordings=<folder or .wav>
 */
class RecordingReplayTest {
    @Test
    fun replayRecordings() {
        val path = System.getProperty("musicbootcamp.recordings")
        assumeTrue("No recordings given (-Pmusicbootcamp.recordings=…)", path != null)
        val root = File(path!!)
        val files = if (root.isDirectory) root.listFiles { f -> f.name.endsWith(".wav") }!!.sorted() else listOf(root)
        files.forEach { println(replay(it)) }
    }

    private fun replay(wavFile: File): String {
        val audio = Wav.read(wavFile.readBytes())
        val log = File(wavFile.path.removeSuffix(".wav") + ".json").takeIf { it.isFile }
            ?.let { SetupRepository.json.decodeFromString<RecordingLog>(it.readText()) }
        val referenceA = log?.info?.get("referenceAHz")?.toDoubleOrNull() ?: Tuning.STANDARD_A_HZ

        val tracker = NoteTracker(audio.sampleRate, referenceA)
        val microphone = audio.channels[log?.channels?.indexOf("microphone")?.coerceAtLeast(0) ?: 0]
        val replayed = microphone.asList().chunked(512).flatMap { tracker.process(it.toFloatArray()) }

        fun ms(sample: Long) = sample * 1000 / audio.sampleRate
        fun name(note: Int) = NoteNames.displayName(note)
        return buildString {
            appendLine("== ${wavFile.name}: ${ms(microphone.size.toLong()) / 1000.0} s, ${log?.info ?: "no log"}")
            val lines = mutableListOf<Pair<Long, String>>()
            log?.events?.forEach { event ->
                val text = when (event.type) {
                    RecordingEventType.STEP -> "---- step: ${event.notes.joinToString { name(it) }}"
                    RecordingEventType.EVALUATION -> "     evaluated: ${if (event.correct == true) "correct" else "WRONG"}"
                    RecordingEventType.APP_NOTE_ON -> "     app on  ${name(event.notes.single())}"
                    RecordingEventType.APP_NOTE_OFF -> "     app off ${name(event.notes.single())}"
                    RecordingEventType.DETECTED -> "     live detected ${name(event.notes.single())} (${event.cents?.toInt()} ct)"
                    RecordingEventType.ACCEPTED -> "     live accepted ${name(event.notes.single())}"
                }
                lines += event.sample to text
            }
            replayed.forEach { lines += it.sampleTime to "     REPLAY detected ${name(it.midiNote)} (${it.cents.toInt()} ct)" }
            lines.sortedBy { it.first }.forEach { (sample, text) -> appendLine("%7d ms %s".format(ms(sample), text)) }
        }
    }
}
