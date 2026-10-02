package io.github.jonnyfrick.musicbootcamp.ui

import io.github.jonnyfrick.musicbootcamp.core.audio.Sampler
import io.github.jonnyfrick.musicbootcamp.core.midi.MidiMessage
import io.github.jonnyfrick.musicbootcamp.core.pitch.PitchDetector
import io.github.jonnyfrick.musicbootcamp.platform.Instruments
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.File
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertTrue

/** The piano samples shipped with the app, loaded as the app loads them. */
class InstrumentsTest {
    private val sampleRate = 44_100

    @Test
    fun everyNoteOfTheSampledPianoIsHeardAsItself() = runBlocking {
        Instruments.load()
        assertTrue(Instruments.isPianoSampled)
        val detector = PitchDetector(sampleRate)
        val wrong = mutableListOf<String>()
        // The range exercises use; each note through the app's own pitch detection.
        for (note in 36..96) {
            val piano = Instruments.piano(sampleRate)
            assertTrue(piano is Sampler)
            piano.send(MidiMessage.noteOn(note, 80))
            val audio = FloatArray(sampleRate / 2).also { piano.render(it) }
            val window = audio.copyOfRange(sampleRate / 20, sampleRate / 20 + detector.windowSize)
            val heard = detector.detect(window)?.let { (69 + 12 * ln(it.frequencyHz / 440.0) / ln(2.0)).roundToInt() }
            if (heard != note) wrong += "$note heard as $heard"
        }
        assertTrue(wrong.isEmpty(), "wrong: $wrong")
    }

    @Test
    fun writeADemoToListenTo() = runBlocking {
        Instruments.load()
        val piano = Instruments.piano(sampleRate)
        val audio = mutableListOf<Float>()
        fun play(notes: List<Int>, seconds: Double) {
            notes.forEach { piano.send(MidiMessage.noteOn(it, 80)) }
            val on = FloatArray((seconds * 0.7 * sampleRate).toInt()).also { piano.render(it) }
            notes.forEach { piano.send(MidiMessage.noteOff(it)) }
            val off = FloatArray((seconds * 0.3 * sampleRate).toInt()).also { piano.render(it) }
            audio += on.toList()
            audio += off.toList()
        }
        // A chromatic run over the exercise range, then intervals and chords as the two-voice mode plays them.
        for (note in 36..96) play(listOf(note), 0.35)
        listOf(listOf(48, 55), listOf(52, 60), listOf(57, 64), listOf(60, 67), listOf(64, 72), listOf(48, 52, 55, 60)).forEach { play(it, 1.4) }
        val bytes = ByteArray(audio.size * 2)
        audio.forEachIndexed { i, sample ->
            val value = (sample * 32767).toInt()
            bytes[2 * i] = value.toByte()
            bytes[2 * i + 1] = (value shr 8).toByte()
        }
        val file = File("build/piano-demo.wav")
        AudioSystem.write(
            AudioInputStream(ByteArrayInputStream(bytes), AudioFormat(sampleRate.toFloat(), 16, 1, true, false), audio.size.toLong()),
            AudioFileFormat.Type.WAVE, file,
        )
        assertTrue(file.length() > 100_000)
    }
}
