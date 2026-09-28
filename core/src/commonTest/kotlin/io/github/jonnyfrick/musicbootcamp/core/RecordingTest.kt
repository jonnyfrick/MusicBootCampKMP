package io.github.jonnyfrick.musicbootcamp.core

import io.github.jonnyfrick.musicbootcamp.core.audio.RecordingEventType
import io.github.jonnyfrick.musicbootcamp.core.audio.RecordingFile
import io.github.jonnyfrick.musicbootcamp.core.audio.RecordingLog
import io.github.jonnyfrick.musicbootcamp.core.audio.SessionRecorder
import io.github.jonnyfrick.musicbootcamp.core.audio.Wav
import io.github.jonnyfrick.musicbootcamp.core.midi.MidiMessage
import io.github.jonnyfrick.musicbootcamp.core.persistence.SetupRepository
import io.github.jonnyfrick.musicbootcamp.core.pitch.DetectedNote
import io.github.jonnyfrick.musicbootcamp.core.practice.Evaluation
import io.github.jonnyfrick.musicbootcamp.core.practice.StepResult
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecordingTest {
    private class MemoryFile : RecordingFile {
        var bytes = ByteArray(Wav.HEADER_SIZE)
        var log = ""
        override val name = "memory"
        override fun append(bytes: ByteArray) {
            this.bytes += bytes
        }
        override fun finish(wavHeader: ByteArray, log: String) {
            wavHeader.copyInto(bytes)
            this.log = log
        }
    }

    @Test
    fun wavRoundTrip() {
        val left = FloatArray(1000) { sin(it * 0.05).toFloat() * 0.8f }
        val right = FloatArray(1000) { -left[it] / 2 }
        val bytes = Wav.header(44_100, 2, 4000L) + Wav.pcm16(listOf(left, right))
        val audio = Wav.read(bytes)
        assertEquals(44_100, audio.sampleRate)
        assertEquals(2, audio.channels.size)
        assertTrue(left.indices.all { abs(audio.channels[0][it] - left[it]) < 1e-4 && abs(audio.channels[1][it] - right[it]) < 1e-4 })
    }

    @Test
    fun anUnfinishedRecordingIsStillReadable() {
        val samples = FloatArray(300) { 0.25f }
        val audio = Wav.read(Wav.header(44_100, 1, 0) + Wav.pcm16(listOf(samples)))
        assertEquals(300, audio.channels.single().size)
    }

    @Test
    fun recorderWritesAudioAndEventsInOrder() {
        val file = MemoryFile()
        val recorder = SessionRecorder(file, 44_100, listOf("microphone"))
        val output = recorder.recording { }

        output.send(MidiMessage.noteOn(60, 90))
        recorder.step(StepResult(previousCorrect = null, storedMistake = false, given = listOf(60), startedNotes = listOf(60)))
        recorder.audio(listOf(FloatArray(512) { 0.1f }))
        recorder.detected(DetectedNote(62, 293.7, 1.5, sampleTime = 400))
        recorder.accepted(MidiMessage.noteOn(62, 100))
        recorder.audio(listOf(FloatArray(512)))
        output.send(MidiMessage.noteOff(60))
        recorder.evaluation(Evaluation(correct = false, storedMistake = false))
        recorder.finish(mapOf("breathingTime" to "1.0"))

        val audio = Wav.read(file.bytes)
        assertEquals(1024, audio.channels.single().size)
        val log = SetupRepository.json.decodeFromString<RecordingLog>(file.log)
        assertEquals("1.0", log.info["breathingTime"])
        assertEquals(
            listOf(
                0L to RecordingEventType.APP_NOTE_ON,
                0L to RecordingEventType.STEP,
                400L to RecordingEventType.DETECTED,
                512L to RecordingEventType.ACCEPTED,
                1024L to RecordingEventType.APP_NOTE_OFF,
                1024L to RecordingEventType.EVALUATION,
            ),
            log.events.map { it.sample to it.type },
        )
    }
}
