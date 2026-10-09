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

    /** How often [read] had to jump to get back in step (for diagnosis: each jump upsets the echo cancellation). */
    var resyncs = 0
        private set

    private var outOfStep = 0

    /**
     * The next [frames] samples, continuing where the previous call ended. [playingFrame] is the
     * frame the loudspeaker plays now; the samples start [lead] frames after it, so the microphone,
     * which hears them later, always lags behind the reference (never leads).
     *
     * Microphone blocks do not arrive evenly: when the reader was held up, several come at once,
     * each still belonging right after the one before. So being more than [resync] frames off
     * only counts once it lasted for [patience] frames (a second or so): then samples were really
     * lost (or the output stalled), and the reading jumps back in step.
     */
    fun read(frames: Int, playingFrame: Long, lead: Int, resync: Int, patience: Int = DEFAULT_PATIENCE): FloatArray {
        val target = playingFrame + lead - frames
        if (readPosition < 0) {
            readPosition = target
        } else if (abs(readPosition - target) > resync) {
            outOfStep += frames
            if (outOfStep >= patience) {
                readPosition = target
                outOfStep = 0
                resyncs++
            }
        } else {
            outOfStep = 0
        }
        val result = FloatArray(frames) { i ->
            val position = readPosition + i
            if (position < 0 || position >= rendered || position < rendered - capacity) 0f
            else ring[(position % capacity).toInt()]
        }
        readPosition += frames
        return result
    }

    private companion object {
        const val DEFAULT_PATIENCE = 44_100
    }
}
