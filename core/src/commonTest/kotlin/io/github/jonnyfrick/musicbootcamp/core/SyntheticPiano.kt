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
            val amplitude = (if (n == 1) fundamentalWeight else 1.0) / n
            value += amplitude * exp(-t * (1.2 + 0.6 * n)) * sin(2 * PI * fn * t + phases[n - 1])
        }
        // Hammer noise during the first 4 ms.
        if (t < 0.004) value += (random.nextDouble() - 0.5) * 0.6 * (1 - t / 0.004)
        val damper = if (t < durationSeconds) 1.0 else exp(-(t - durationSeconds) / 0.03)
        buffer[index] += (loudness * value * damper).toFloat()
    }
}
