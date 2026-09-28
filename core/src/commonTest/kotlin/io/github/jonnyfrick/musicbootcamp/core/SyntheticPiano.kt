package io.github.jonnyfrick.musicbootcamp.core

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/*
 * Synthetic piano tones for the pitch detection tests: stretched (inharmonic) partials,
 * a weak fundamental in the bass, faster decay of upper partials and a noisy attack.
 */

/**
 * Adds a piano-like stroke of [midiNote] to [buffer] starting at [startSeconds]; after
 * [durationSeconds] the key is released and the damper stops the string within ~0.1 s.
 */
internal fun addPianoStroke(
    buffer: FloatArray,
    midiNote: Int,
    startSeconds: Double,
    durationSeconds: Double = 1.5,
    referenceAHz: Double = 440.0,
    detuneCents: Double = 0.0,
    loudness: Double = 0.3,
    seed: Int = midiNote,
    sampleRate: Int = 44_100,
    /** Gain by frequency (room and microphone colouring), 1 = none. */
    colour: (Double) -> Double = { 1.0 },
) {
    val f0 = referenceAHz * 2.0.pow((midiNote - 69 + detuneCents / 100) / 12)
    // Inharmonicity grows towards the treble (typical B from ~0.0001 to ~0.002).
    val b = 0.0001 * 2.0.pow((midiNote - 21) / 20.0)
    // Bass strings: the fundamental is much weaker than the next partials.
    val fundamentalWeight = if (midiNote < 48) 0.25 else 1.0
    val random = Random(seed)
    val phases = DoubleArray(16) { random.nextDouble(2 * PI) }
    val start = (startSeconds * sampleRate).toInt()
    val length = ((durationSeconds + 0.3) * sampleRate).toInt()
    for (i in 0 until length) {
        val index = start + i
        if (index >= buffer.size) break
        val t = i.toDouble() / sampleRate
        var value = 0.0
        for (n in 1..16) {
            val fn = n * f0 * sqrt(1 + b * n * n)
            if (fn >= sampleRate / 2) break
            val amplitude = (if (n == 1) fundamentalWeight else 1.0) / n * colour(fn)
            value += amplitude * exp(-t * (1.2 + 0.6 * n)) * sin(2 * PI * fn * t + phases[n - 1])
        }
        // Hammer noise during the first 4 ms.
        if (t < 0.004) value += (random.nextDouble() - 0.5) * 0.6 * (1 - t / 0.004)
        val damper = if (t < durationSeconds) 1.0 else exp(-(t - durationSeconds) / 0.03)
        buffer[index] += (loudness * value * damper).toFloat()
    }
}

/**
 * The reference as the microphone hears it: [delaySamples] later (output and input latency),
 * [gain] quieter, with early reflections, a reverberation tail, and some noise.
 */
internal fun roomEcho(reference: FloatArray, delaySamples: Int, gain: Double, seed: Int = 1, sampleRate: Int = 44_100): FloatArray {
    val random = Random(seed)
    val tailLength = (0.12 * sampleRate).toInt()
    val response = DoubleArray(tailLength + sampleRate / 1000)
    response[0] = 1.0
    response[(0.003 * sampleRate).toInt()] += 0.5
    response[(0.007 * sampleRate).toInt()] -= 0.3
    // Diffuse reverberation: one random reflection per millisecond, decaying.
    for (i in (0.01 * sampleRate).toInt() until tailLength step sampleRate / 1000) {
        response[i + random.nextInt(sampleRate / 1000)] += (random.nextDouble() - 0.5) * 0.4 * exp(-i / (0.04 * sampleRate))
    }
    val taps = response.indices.filter { response[it] != 0.0 }
    val echo = FloatArray(reference.size)
    for (n in echo.indices) {
        var value = 0.0
        for (k in taps) {
            val index = n - delaySamples - k
            if (index >= 0) value += response[k] * reference[index]
        }
        echo[n] = (gain * value + (random.nextDouble() - 0.5) * 0.002).toFloat()
    }
    return echo
}

/**
 * A room's and microphone's colouring: a gain changing smoothly with frequency by about
 * ±[decibels] dB (partials of different notes at the same frequency get the same gain).
 */
internal fun roomColour(seed: Int, decibels: Double = 10.0): (Double) -> Double {
    val random = Random(seed)
    val waves = List(6) { Triple(random.nextDouble(0.5, 4.0), random.nextDouble(2 * PI), random.nextDouble(0.3, 1.0)) }
    val norm = waves.sumOf { it.third }
    return { frequency ->
        val octaves = kotlin.math.ln(frequency / 50.0) / kotlin.math.ln(2.0)
        val db = decibels * waves.sumOf { (rate, phase, weight) -> weight * sin(2 * PI * rate * octaves + phase) } / norm * 1.7
        10.0.pow(db / 20)
    }
}
