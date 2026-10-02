package io.github.jonnyfrick.musicbootcamp.core

import io.github.jonnyfrick.musicbootcamp.core.audio.SampleSet
import io.github.jonnyfrick.musicbootcamp.core.audio.Sampler
import io.github.jonnyfrick.musicbootcamp.core.midi.MidiMessage
import io.github.jonnyfrick.musicbootcamp.core.midi.Tuning
import io.github.jonnyfrick.musicbootcamp.core.pitch.PitchDetector
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The sampler with a real piano sample (A7 of the Salamander Grand Piano). */
class SamplerTest {
    private val set = SampleSet.fromFlac(mapOf(105 to javaClass.getResourceAsStream("/audio/piano-105.flac")!!.readBytes()))

    private fun Sampler.take(seconds: Double, sampleRate: Int) = FloatArray((seconds * sampleRate).toInt()).also { render(it) }

    private fun rms(samples: FloatArray) = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)

    private fun pitch(samples: FloatArray, sampleRate: Int): Double {
        val detector = PitchDetector(sampleRate)
        return detector.detect(samples.copyOfRange(0, detector.windowSize))!!.frequencyHz
    }

    private fun cents(a: Double, b: Double) = 1200 * ln(a / b) / ln(2.0)

    @Test
    fun aNoteIsPlayedFromTheNearestSampleAtItsPitch() {
        fun played(note: Int, sampleRate: Int): Double {
            val sampler = Sampler(set, sampleRate)
            sampler.send(MidiMessage.noteOn(note, 100))
            sampler.take(0.03, sampleRate)
            return pitch(sampler.take(0.2, sampleRate), sampleRate)
        }
        // The recording itself: a real piano's top is tuned sharp ("stretched"), up to half a semitone.
        val recorded = played(105, 44_100)
        assertTrue(abs(cents(recorded, 440.0 * 2.0.pow((105 - 69) / 12.0))) < 50, "A7 as recorded: $recorded Hz")
        // Notes without a recording of their own are that one re-pitched, at any output rate.
        for (sampleRate in listOf(44_100, 48_000)) {
            for (note in listOf(105, 103, 93)) {
                val off = cents(played(note, sampleRate), recorded * 2.0.pow((note - 105) / 12.0))
                assertTrue(abs(off) < 10, "note $note at $sampleRate Hz: $off cents off")
            }
        }
    }

    @Test
    fun theReferenceATunesTheSamplesToo() {
        fun a6(referenceAHz: Double): Double {
            val sampler = Sampler(set, 44_100)
            Tuning.messages(referenceAHz).forEach(sampler::send)
            sampler.send(MidiMessage.noteOn(93, 100))
            sampler.take(0.03, 44_100)
            return pitch(sampler.take(0.2, 44_100), 44_100)
        }
        assertEquals(443.0 / 440.0, a6(443.0) / a6(440.0), 0.001)
    }

    @Test
    fun velocityScalesAndReleaseAndTheSamplesEndStopTheNote() {
        fun level(velocity: Int): Double {
            val sampler = Sampler(set, 44_100)
            sampler.send(MidiMessage.noteOn(105, velocity))
            return rms(sampler.take(0.2, 44_100))
        }
        assertTrue(level(120) > 1.5 * level(60), "louder with more velocity")

        val sampler = Sampler(set, 44_100)
        sampler.send(MidiMessage.noteOn(105, 100))
        sampler.take(0.1, 44_100)
        sampler.send(MidiMessage.noteOff(105))
        sampler.take(1.0, 44_100)
        assertTrue(sampler.isSilent, "released")

        sampler.send(MidiMessage.noteOn(105, 100))
        sampler.take(8.0, 44_100)
        assertTrue(sampler.isSilent, "the sample is over")
    }
}
