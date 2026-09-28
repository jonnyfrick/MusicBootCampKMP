package io.github.jonnyfrick.musicbootcamp.core.pitch

import io.github.jonnyfrick.musicbootcamp.core.midi.MidiMessage
import io.github.jonnyfrick.musicbootcamp.core.midi.Tuning
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

/** A chord recognised from audio: one per stroke of several keys. */
data class DetectedChord(
    /** The distinct notes, lowest first. */
    val notes: List<Int>,
    /** Position in the stream (samples since the tracker started) where it was decided. */
    val sampleTime: Long,
    /** Where the stroke began. */
    val onsetTime: Long,
    /** Whether the app's own sound was being removed (else callers ignore the app's current notes). */
    val ownSoundRemoved: Boolean,
    val details: RecognizedChord,
)

/**
 * Turns an audio stream into chords for exercises with several voices.
 *
 * Strokes are found as for single notes ([NoteTracker], which also learns the app's own sound
 * from the reference); strokes within [ChordDetectionParameters.chordSpreadMillis] are one chord.
 * The spectrum is then averaged over a longer window after the stroke (the slower tempo of
 * these exercises allows it, and it separates close partials down in the bass). What was
 * ringing before the stroke and the app's predicted sound are removed, and
 * [TemplateChordRecognizer] decides which notes remain. [expected] gives the chords the player
 * may be answering; it is called from the audio thread, so it must be thread-safe.
 */
class ChordTracker(
    val sampleRate: Int,
    private val voices: Int,
    range: IntRange,
    private val referenceAHz: Double = Tuning.STANDARD_A_HZ,
    private val parameters: ChordDetectionParameters = ChordDetectionParameters(),
    learned: LearnedTemplates = LearnedTemplates(),
    private val expected: () -> List<List<Int>> = { emptyList() },
) {
    private val strokes = NoteTracker(sampleRate, referenceAHz, parameters.strokes)
    private val windowStart = parameters.windowStartMillis * sampleRate / 1000
    private val windowEnd = maxOf(parameters.windowEndMillis * sampleRate / 1000, windowStart + FFT_SIZE)
    private val spread = parameters.chordSpreadMillis * sampleRate / 1000

    val templates = PianoTemplates(
        sampleRate, FFT_SIZE, referenceAHz, windowStart.toDouble() / sampleRate, windowEnd.toDouble() / sampleRate, learned,
    )
    private val recognizer = TemplateChordRecognizer(
        templates,
        (range.first - RANGE_MARGIN).coerceAtLeast(LogSpectrum.LOWEST_NOTE)..(range.last + RANGE_MARGIN).coerceAtMost(LogSpectrum.HIGHEST_NOTE - 12),
        parameters,
    )

    private val microphone = FloatArray(HISTORY_SECONDS * sampleRate)
    private val reference = FloatArray(HISTORY_SECONDS * sampleRate)
    private var written = 0L
    private var available = 0L // samples in the history (the current block included)

    private var pendingOnset = -1L
    private var pendingOwnSoundRemoved = false
    private var pendingLevel = 0.0
    private val found = mutableListOf<DetectedChord>()

    /** RMS of the latest hop, for a level meter. */
    val level: Double get() = strokes.level

    /** Receives what the stroke detection saw in every hop, for analysing recordings. */
    var trace: ((HopTrace) -> Unit)? = null

    /** Receives the spectrum each decision was based on (log axis), for analysing recordings. */
    var spectrumTrace: ((Long, DoubleArray) -> Unit)? = null

    init {
        strokes.trace = { hop ->
            trace?.invoke(hop)
            if (hop.onset) onStroke(hop.sampleTime - strokes.hopSize, hop.ownSoundRemoved, hop.level)
        }
    }

    /** Feeds samples (mono, -1..1) and what the app played meanwhile; returns the chords decided in them. */
    fun process(samples: FloatArray, reference: FloatArray? = null): List<DetectedChord> {
        found.clear()
        for (i in samples.indices) {
            val slot = ((written + i) % microphone.size).toInt()
            microphone[slot] = samples[i]
            this.reference[slot] = reference?.get(i) ?: 0f
        }
        available = written + samples.size
        // Hop by hop, so strokes are seen in time; the window of a stroke may complete in between.
        var offset = 0
        while (offset < samples.size) {
            val end = minOf(samples.size, offset + strokes.hopSize)
            strokes.process(samples.copyOfRange(offset, end), reference?.copyOfRange(offset, end))
            written += end - offset
            offset = end
            if (pendingOnset >= 0 && written >= pendingOnset + windowEnd) analyse()
        }
        return found.toList()
    }

    private fun onStroke(onset: Long, ownSoundRemoved: Boolean, level: Double) {
        if (pendingOnset >= 0) {
            // The same chord (fingers a little apart), or the beating of close notes, which the
            // level-based detection takes for strokes. Only a clearly louder stroke starts anew.
            if (onset - pendingOnset < spread || level < RESTART_RISE * pendingLevel) return
        }
        pendingOnset = onset
        pendingOwnSoundRemoved = ownSoundRemoved
        pendingLevel = level
    }

    private fun analyse() {
        val onset = pendingOnset
        pendingOnset = -1
        val from = onset + windowStart
        val to = onset + windowEnd
        val frames = framesBetween(from, to)

        val mic = averagePower(microphone, frames, 0)
        val background = averagePower(microphone, listOf(onset - FFT_SIZE), 0)
        val echo = strokes.ownSound?.takeIf { pendingOwnSoundRemoved }
        val own = echo?.delayHops?.let { delayHops ->
            val power = averagePower(reference, frames, delayHops * strokes.hopSize)
            DoubleArray(power.size) { k -> power[k] * echo.ownSoundGain(k.toDouble() * sampleRate / FFT_SIZE) }
        }
        val remaining = DoubleArray(mic.size) { k ->
            val removed = max(parameters.backgroundWeight * background[k], own?.get(k) ?: 0.0)
            (mic[k] - removed).coerceAtLeast(0.0)
        }
        val remainingLevel = rms(remaining)
        if (remainingLevel < parameters.minLevel || remainingLevel < parameters.minNewShare * rms(mic)) return

        val spectrum = LogSpectrum.fromPower(remaining, FFT_SIZE, sampleRate, referenceAHz)
        spectrumTrace?.invoke(written, spectrum)
        val recognized = recognizer.recognize(spectrum, voices, expected()) ?: return
        found += DetectedChord(recognized.notes, written, onset, pendingOwnSoundRemoved, recognized)
    }

    /** Frame start positions covering [from, to): overlapping by three quarters. */
    private fun framesBetween(from: Long, to: Long): List<Long> {
        val starts = mutableListOf<Long>()
        var start = from
        while (start + FFT_SIZE <= to) {
            starts += start
            start += FFT_SIZE / 4
        }
        if (starts.isEmpty()) starts += to - FFT_SIZE
        return starts
    }

    /** Average Hann-windowed power spectrum of [signal] at the given frame starts, [lag] samples earlier. */
    private fun averagePower(signal: FloatArray, starts: List<Long>, lag: Int): DoubleArray {
        val sum = DoubleArray(FFT_SIZE / 2 + 1)
        val re = DoubleArray(FFT_SIZE)
        val im = DoubleArray(FFT_SIZE)
        for (start in starts) {
            for (i in 0 until FFT_SIZE) {
                val position = start - lag + i
                val sample = if (position < 0 || position >= available || position < available - signal.size) 0f
                else signal[(position % signal.size).toInt()]
                re[i] = sample * HANN[i]
                im[i] = 0.0
            }
            fft(re, im)
            for (k in sum.indices) sum[k] += re[k] * re[k] + im[k] * im[k]
        }
        for (k in sum.indices) sum[k] /= starts.size
        return sum
    }

    /** RMS in the time domain that a (one-sided, Hann-windowed) power spectrum corresponds to. */
    private fun rms(power: DoubleArray): Double = sqrt(2 * power.sum() / (FFT_SIZE * HANN_ENERGY))

    private companion object {
        const val FFT_SIZE = 8192
        const val HISTORY_SECONDS = 4
        const val RANGE_MARGIN = 2
        const val RESTART_RISE = 2.0
        val HANN = DoubleArray(FFT_SIZE) { 0.5 - 0.5 * cos(2 * PI * it / FFT_SIZE) }
        val HANN_ENERGY = HANN.sumOf { it * it } / FFT_SIZE * FFT_SIZE
    }
}

/** Microphone blocks (with what the app played) → recognised chords; see [ChordTracker]. */
fun Flow<AudioBlock>.detectedChords(
    sampleRate: Int,
    voices: Int,
    range: IntRange,
    referenceAHz: Double,
    parameters: ChordDetectionParameters = ChordDetectionParameters(),
    learned: LearnedTemplates = LearnedTemplates(),
    expected: () -> List<List<Int>> = { emptyList() },
    onLevel: (Double) -> Unit = {},
): Flow<DetectedChord> = flow {
    val tracker = ChordTracker(sampleRate, voices, range, referenceAHz, parameters, learned, expected)
    collect { block ->
        val chords = tracker.process(block.microphone, block.reference)
        onLevel(tracker.level)
        chords.forEach { emit(it) }
    }
}

/**
 * A chord as the note-ons a MIDI keyboard would send: one per voice, so a unison (or a chord
 * missing a note) repeats its note — the exercise evaluates [voices] key presses per step.
 */
fun DetectedChord.toNoteOns(voices: Int): List<MidiMessage> =
    List(voices) { i -> notes.getOrElse(i) { notes.last() } }.map { MidiMessage.noteOn(it, DETECTED_CHORD_VELOCITY) }

private const val DETECTED_CHORD_VELOCITY = 100

/**
 * With octaves counting as correct: the expected chord (the latest first) whose pitch classes are
 * exactly those of [notes], or null. A unison may come back as the note and its octave.
 */
fun matchIgnoringOctaves(notes: List<Int>, expected: List<List<Int>>): List<Int>? {
    val classes = notes.map { it.mod(12) }.toSet()
    return expected.asReversed().firstOrNull { chord -> chord.map { it.mod(12) }.toSet() == classes }
}
