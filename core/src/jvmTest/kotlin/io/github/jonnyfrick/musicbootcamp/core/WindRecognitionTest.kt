package io.github.jonnyfrick.musicbootcamp.core

import io.github.jonnyfrick.musicbootcamp.core.audio.SampleSet
import io.github.jonnyfrick.musicbootcamp.core.pitch.ChordTracker
import io.github.jonnyfrick.musicbootcamp.core.pitch.InstrumentProfile
import io.github.jonnyfrick.musicbootcamp.core.pitch.NoteTracker
import io.github.jonnyfrick.musicbootcamp.core.pitch.matchIgnoringOctaves
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Wind instruments: real tones (VSCO 2 Community Edition, CC0; see tools/prepare_wind_test_samples.py). */
class WindRecognitionTest {
    private val sampleRate = 44_100
    private val instruments = mapOf(
        "trumpet" to InstrumentProfile.WIND, "trombone" to InstrumentProfile.WIND,
        "clarinet" to InstrumentProfile.CLARINET, "flute" to InstrumentProfile.FLUTE,
    )

    private fun tones(instrument: String): Map<Int, FloatArray> {
        val folder = File(javaClass.getResource("/audio/winds/$instrument")!!.toURI())
        return SampleSet.fromFlac(folder.listFiles()!!.filter { it.name.endsWith(".flac") }.associate { it.name.take(3).toInt() to it.readBytes() }).samples
    }

    private fun mix(length: Double, vararg parts: Pair<FloatArray, Double>): FloatArray {
        val out = FloatArray((length * sampleRate).toInt())
        for ((tone, start) in parts) {
            val offset = (start * sampleRate).toInt()
            for (i in tone.indices) if (offset + i < out.size) out[offset + i] += tone[i]
        }
        return out
    }

    private fun notes(audio: FloatArray, profile: InstrumentProfile, legato: Boolean = false): List<Int> {
        val tracker = NoteTracker(sampleRate, instrument = profile, legato = legato)
        return audio.indices.chunked(512).flatMap { tracker.process(audio.sliceArray(it.first()..it.last())) }.map { it.midiNote }
    }

    private fun chords(audio: FloatArray, voices: Int, expected: List<Int>, range: IntRange, profile: InstrumentProfile): List<List<Int>> {
        val tracker = ChordTracker(sampleRate, voices, range, expected = { listOf(expected) }, instrument = profile)
        return audio.indices.chunked(512).flatMap { tracker.process(audio.sliceArray(it.first()..it.last())) }.map { it.notes }
    }

    /** As in the app: a note in another octave counts (the default with the microphone). */
    private fun heard(found: List<List<Int>>, played: List<Int>) =
        found.firstOrNull() == played || found.firstOrNull()?.let { matchIgnoringOctaves(it, listOf(played)) } != null

    @Test
    fun everySingleToneIsHeardOnceAndRight() {
        // With the piano's settings 18 of these 42 came out a semitone off (read while the note
        // still scooped into its pitch) or several times (a swelling attack looks like several).
        for ((instrument, profile) in instruments) {
            val wrong = tones(instrument).map { (note, tone) -> note to notes(mix(2.2, tone to 0.2), profile) }.filter { it.second != listOf(it.first) }
            assertTrue(wrong.isEmpty(), "$instrument: $wrong")
        }
    }

    @Test
    fun thePianoSettingsWouldMishearThem() {
        val wrong = instruments.keys.sumOf { instrument ->
            tones(instrument).count { (note, tone) -> notes(mix(2.2, tone to 0.2), InstrumentProfile.PIANO) != listOf(note) }
        }
        assertTrue(wrong >= 10, "the profiles are what makes the difference: only $wrong wrong with the piano's")
    }

    @Test
    fun aSlurIsANewNoteOnlyWhereThatIsAskedFor() {
        for ((instrument, profile) in instruments) {
            val tones = tones(instrument)
            val keys = tones.keys.sorted()
            val pairs = keys.zipWithNext() + keys.zipWithNext().map { it.second to it.first }
            val wrong = pairs.map { (a, b) -> listOf(a, b) to notes(slur(tones.getValue(a), tones.getValue(b)), profile, legato = true) }
                .filter { it.second != it.first }
            assertTrue(wrong.size <= 1, "$instrument: $wrong")
            val (a, b) = pairs.first()
            assertEquals(listOf(a), notes(slur(tones.getValue(a), tones.getValue(b)), profile, legato = false), "$instrument without legato")
        }
    }

    @Test
    fun chordsOfOneInstrumentAreHeard() {
        // Two and three players of the same instrument, starting 20–40 ms apart. The trombone's
        // lowest fifth (below F2), where seconds growl, is left out.
        for ((instrument, profile) in instruments) {
            val tones = tones(instrument).filterKeys { it >= 41 }
            val keys = tones.keys.sorted()
            val range = (keys.first() - 2)..(keys.last() + 2)
            val pairs = (keys.zipWithNext() + keys.zip(keys.drop(2))).map { (a, b) ->
                listOf(a, b) to chords(mix(2.4, tones.getValue(a) to 0.2, tones.getValue(b) to 0.23), 2, listOf(a, b), range, profile)
            }
            val triads = keys.windowed(3).map { triad ->
                triad to chords(mix(2.4, tones.getValue(triad[0]) to 0.2, tones.getValue(triad[1]) to 0.24, tones.getValue(triad[2]) to 0.22), 3, triad, range, profile)
            }
            val wrongPairs = pairs.filter { !heard(it.second, it.first) }
            val wrongTriads = triads.filter { !heard(it.second, it.first) }
            assertTrue(wrongPairs.size <= 1, "$instrument pairs: ${wrongPairs.size} of ${pairs.size}: $wrongPairs")
            assertTrue(wrongTriads.size <= 2, "$instrument triads: ${wrongTriads.size} of ${triads.size}: $wrongTriads")
        }
    }

    @Test
    fun aSectionOfDifferentInstrumentsIsHeard() {
        // Trombone below, trumpet above, the trumpet 60 ms late.
        val low = tones("trombone")
        val high = tones("trumpet")
        val wrong = listOf(46 to 53, 50 to 57, 53 to 60, 53 to 63, 60 to 67, 61 to 70, 63 to 74, 65 to 77).map { (a, b) ->
            listOf(a, b) to chords(mix(2.4, low.getValue(a) to 0.2, high.getValue(b) to 0.26), 2, listOf(a, b), 40..84, InstrumentProfile.WIND)
        }.filter { !heard(it.second, it.first) }
        assertTrue(wrong.size <= 1, "$wrong")
    }

    /** [first] held, then [second] without a new attack: its steady part faded in while the first fades out. */
    private fun slur(first: FloatArray, second: FloatArray): FloatArray {
        val out = FloatArray((2.4 * sampleRate).toInt())
        val change = (1.1 * sampleRate).toInt()
        val fade = sampleRate * 30 / 1000
        val start = (0.2 * sampleRate).toInt()
        val steady = (0.4 * sampleRate).toInt() // into the second tone, past its attack
        for (i in 0 until change + fade - start) {
            val gain = if (start + i < change) 1f else 1f - (start + i - change).toFloat() / fade
            if (i < first.size) out[start + i] += first[i] * gain
        }
        for (i in 0 until (0.9 * sampleRate).toInt()) {
            val gain = if (i < fade) i.toFloat() / fade else 1f
            if (steady + i < second.size && change + i < out.size) out[change + i] += second[steady + i] * gain
        }
        return out
    }
}
