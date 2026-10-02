package io.github.jonnyfrick.musicbootcamp.core.audio

import kotlin.math.abs
import kotlin.math.pow

/**
 * Recordings of an instrument: single notes (mono, -1..1) by MIDI note, all at [sampleRate].
 * Not every note needs one; a missing note is played from the nearest recording, re-pitched.
 */
class SampleSet(val sampleRate: Int, val samples: Map<Int, FloatArray>) {
    init {
        require(samples.isNotEmpty()) { "A sample set needs at least one sample" }
    }

    /** The recorded note nearest to [note]. */
    fun nearest(note: Int): Int = samples.keys.minBy { abs(it - note) }

    companion object {
        /** From FLAC files by MIDI note (mono; of several channels the first is taken). */
        fun fromFlac(files: Map<Int, ByteArray>): SampleSet {
            var rate = 0
            val samples = files.mapValues { (_, bytes) ->
                val audio = Flac.decode(bytes)
                require(rate == 0 || rate == audio.sampleRate) { "All samples of a set must have the same sample rate" }
                rate = audio.sampleRate
                val scale = 1f / (1 shl (audio.bitsPerSample - 1))
                val channel = audio.channels[0]
                FloatArray(channel.size) { channel[it] * scale }
            }
            return SampleSet(rate, samples)
        }
    }
}

/**
 * Plays a [SampleSet]: each note is the nearest recording, read faster or slower for its pitch
 * (and for the output's sample rate), louder or softer by its velocity. One recording per note
 * (one loudness) is all the app needs so far: it plays every note with the same velocity.
 */
class Sampler(private val set: SampleSet, sampleRate: Int, maxVoices: Int = 32) : VoiceSynth(sampleRate, maxVoices) {
    override fun startVoice(note: Int, channel: Int, velocity: Int, semitones: Double): Voice {
        val root = set.nearest(note)
        return SampleVoice(note, channel, velocity, root, set.samples.getValue(root)).also { it.tune(semitones) }
    }

    private inner class SampleVoice(note: Int, channel: Int, velocity: Int, private val root: Int, private val data: FloatArray) :
        Voice(note, channel) {
        private val gain = GAIN * (velocity / 127.0).pow(VELOCITY_CURVE)
        private var position = 0.0
        private var step = 1.0
        private var release = 1.0
        private val releaseStep = PianoSynth.releaseStep(note, sampleRate)

        override fun tune(semitones: Double) {
            step = 2.0.pow((note - root + semitones) / 12.0) * set.sampleRate / sampleRate
        }

        override fun loudness(): Double = gain * release * (1 - position / data.size).coerceAtLeast(0.0)

        override fun render(out: FloatArray, start: Int, length: Int): Boolean {
            for (i in 0 until length) {
                val index = position.toInt()
                if (index + 1 >= data.size) return false
                val fraction = (position - index).toFloat()
                val sample = data[index] + (data[index + 1] - data[index]) * fraction
                if (releasing) release *= releaseStep
                out[start + i] += (sample * gain * release).toFloat()
                position += step
            }
            return release >= SILENCE
        }
    }

    private companion object {
        /** Full velocity plays a recording at this level, leaving room for chords. */
        const val GAIN = 0.7
        const val VELOCITY_CURVE = 1.3
    }
}
