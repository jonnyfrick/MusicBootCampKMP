package io.github.jonnyfrick.musicbootcamp.core

import io.github.jonnyfrick.musicbootcamp.core.audio.PianoSynth
import io.github.jonnyfrick.musicbootcamp.core.audio.SoftwareSynth
import io.github.jonnyfrick.musicbootcamp.core.audio.SwitchingSynth
import io.github.jonnyfrick.musicbootcamp.core.midi.MidiMessage
import io.github.jonnyfrick.musicbootcamp.core.midi.MidiParser
import io.github.jonnyfrick.musicbootcamp.core.midi.Tuning
import io.github.jonnyfrick.musicbootcamp.core.pitch.PitchDetector
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PianoSynthTest {
    private val sampleRate = 44_100

    private fun SoftwareSynth.take(seconds: Double) = FloatArray((seconds * sampleRate).toInt()).also { render(it) }

    private fun rms(samples: FloatArray) = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)

    private fun pitchOf(samples: FloatArray): Double {
        val detector = PitchDetector(sampleRate)
        return detector.detect(samples.copyOfRange(0, detector.windowSize))!!.frequencyHz
    }

    @Test
    fun aNoteSoundsAtItsPitchAndOurRecognitionHearsIt() {
        for (note in listOf(45, 60, 69, 84)) {
            val synth = PianoSynth(sampleRate)
            synth.send(MidiMessage.noteOn(note, 100))
            synth.take(0.05)
            val expected = 440.0 * 2.0.pow((note - 69) / 12.0)
            val cents = 1200 * ln(pitchOf(synth.take(0.2)) / expected) / ln(2.0)
            // Stretched partials, as a piano string's, sound a little sharp to a period detector.
            assertTrue(abs(cents) < 20, "note $note: $cents cents off")
        }
    }

    @Test
    fun theReferenceATunesTheWholeOutput() {
        fun a4(referenceAHz: Double): Double {
            val synth = PianoSynth(sampleRate)
            Tuning.messages(referenceAHz).forEach(synth::send)
            synth.send(MidiMessage.noteOn(69, 100))
            synth.take(0.05)
            return pitchOf(synth.take(0.2))
        }
        assertEquals(443.0 / 440.0, a4(443.0) / a4(440.0), 0.001)
    }

    @Test
    fun releasedNotesDieAwayAndTheSustainPedalHoldsThem() {
        val synth = PianoSynth(sampleRate)
        synth.send(MidiMessage.noteOn(60, 100))
        val sounding = rms(synth.take(0.1))
        synth.send(MidiMessage.controlChange(64, 127))
        synth.send(MidiMessage.noteOff(60))
        assertTrue(rms(synth.take(0.5)) > sounding * 0.2, "held by the pedal")
        synth.send(MidiMessage.controlChange(64, 0))
        synth.take(1.5)
        assertTrue(synth.isSilent, "released with the pedal")

        synth.send(MidiMessage.noteOn(64, 100))
        synth.take(0.1)
        synth.send(MidiMessage.allNotesOff())
        synth.take(1.5)
        assertTrue(synth.isSilent, "all notes off")
    }

    @Test
    fun aChordStaysWithinFullScale() {
        val synth = PianoSynth(sampleRate)
        listOf(36, 48, 55, 60, 64, 67, 72, 76).forEach { synth.send(MidiMessage.noteOn(it, 127)) }
        assertTrue(synth.take(0.5).all { it in -1f..1f })
    }

    @Test
    fun theParserHandlesRunningStatusSplitChunksAndSystemBytes() {
        val parser = MidiParser()
        val first = parser.parse(byteArrayOf(0x90.toByte(), 60, 100, 64, 90, 0xF8.toByte(), 67))
        assertEquals(listOf(MidiMessage.noteOn(60, 100), MidiMessage.noteOn(64, 90)), first, "running status, clock ignored")
        assertEquals(listOf(MidiMessage.noteOn(67, 80)), parser.parse(byteArrayOf(80)), "a message split across chunks")
        val sysex = byteArrayOf(0xF0.toByte(), 0x7E, 0x10, 0xF7.toByte(), 0x80.toByte(), 60, 0, 0xC0.toByte(), 5)
        assertEquals(listOf(MidiMessage(0x80, 60, 0), MidiMessage(0xC0, 5, 0)), parser.parse(sysex))
        assertEquals(listOf(MidiMessage.noteOn(62, 70)), parser.parse(parser.encode(MidiMessage.noteOn(62, 70))))
    }

    @Test
    fun aSynthesizerThatBecomesAvailableTakesOverFromTheNextNote() {
        // Found in a browser: the samples were still loading when the exercise started, and the
        // whole run sounded synthetic.
        var ready = false
        val later = PianoSynth(sampleRate)
        val synth = SwitchingSynth(PianoSynth(sampleRate)) { if (ready) later else null }
        Tuning.messages(443.0).forEach(synth::send)
        synth.send(MidiMessage.noteOn(60, 100))
        synth.take(0.1)
        assertFalse(synth.switched)
        ready = true
        synth.send(MidiMessage.noteOff(60))
        assertFalse(synth.switched, "only a new note switches")
        synth.take(1.5) // the first note rings out, so the pitch below is the new one's alone
        synth.send(MidiMessage.noteOn(69, 100))
        assertTrue(synth.switched)
        synth.take(0.05)
        val a4 = pitchOf(synth.take(0.2))
        val reference = PianoSynth(sampleRate).run {
            Tuning.messages(443.0).forEach(::send)
            send(MidiMessage.noteOn(69, 100))
            take(0.05)
            pitchOf(take(0.2))
        }
        assertEquals(reference, a4, 1.0, "the tuning was passed on")
        synth.send(MidiMessage.noteOff(69))
        synth.take(2.0)
        assertTrue(synth.isSilent, "both have rung out")
    }
}
