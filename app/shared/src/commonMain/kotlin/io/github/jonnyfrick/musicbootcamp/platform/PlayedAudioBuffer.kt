package io.github.jonnyfrick.musicbootcamp.platform

import kotlin.math.abs

/**
 * What a [RenderedOutputPort] rendered, by frame number, so [read] can hand out what the
 * loudspeaker plays right now as the echo canceller's reference. Not thread-safe: the port
 * guards it with the lock its renderer thread also takes.
 */
class PlayedAudioBuffer(private val capacity: Int = 1 shl 16) {
    private val ring = FloatArray(capacity)
    private var rendered = 0L
    private var readPosition = -1L

    fun append(samples: FloatArray, offset: Int = 0, length: Int = samples.size - offset) {
        for (i in 0 until length) ring[((rendered + i) % capacity).toInt()] = samples[offset + i]
        rendered += length
    }

    /**
     * The next [frames] samples, continuing where the previous call ended. [playingFrame] is the
     * frame the loudspeaker plays now; the samples start [lead] frames after it, so the microphone,
     * which hears them later, always lags behind the reference (never leads). Jumps back in step
     * when the two drift more than [resync] frames apart (e.g. after a dropout).
     */
    fun read(frames: Int, playingFrame: Long, lead: Int, resync: Int): FloatArray {
        val target = playingFrame + lead - frames
        if (readPosition < 0 || abs(readPosition - target) > resync) readPosition = target
        val result = FloatArray(frames) { i ->
            val position = readPosition + i
            if (position < 0 || position >= rendered || position < rendered - capacity) 0f
            else ring[(position % capacity).toInt()]
        }
        readPosition += frames
        return result
    }
}
