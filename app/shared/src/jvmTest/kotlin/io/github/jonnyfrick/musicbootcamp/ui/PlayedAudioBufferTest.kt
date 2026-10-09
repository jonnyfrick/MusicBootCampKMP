package io.github.jonnyfrick.musicbootcamp.ui

import io.github.jonnyfrick.musicbootcamp.platform.PlayedAudioBuffer
import kotlin.test.Test
import kotlin.test.assertEquals

/** The reference for the echo cancellation stays aligned with the microphone samples. */
class PlayedAudioBufferTest {
    private val block = 512

    /** A buffer holding a ramp, so a sample's value is its frame number. */
    private fun ramp(frames: Int) = PlayedAudioBuffer().apply { append(FloatArray(frames) { it.toFloat() }) }

    @Test
    fun blocksThatArriveLateContinueWhereThePreviousEnded() {
        // Found live on a phone: after the microphone reader was held up, the reference jumped
        // ahead and back, and for that step the app's own sound was taken for an answer.
        val buffer = ramp(40_000)
        var playing = 2_000L
        val first = buffer.read(block, playing, lead = 0, resync = 4096)
        // The reader stalls for 8 blocks' time, then delivers them all at once.
        playing += 9L * block
        var expected = first.last() + 1
        repeat(9) {
            val samples = buffer.read(block, playing, lead = 0, resync = 4096)
            assertEquals(expected, samples.first(), "continuous through the burst")
            expected = samples.last() + 1
        }
        assertEquals(0, buffer.resyncs)
    }

    @Test
    fun lostSamplesAreCaughtUpAfterAWhile() {
        val buffer = ramp(60_000)
        var playing = 2_000L
        buffer.read(block, playing, lead = 0, resync = 4096)
        // 6000 microphone samples were lost: from now on every block is that far behind.
        playing += 6_000
        var last = 0f
        repeat(30) {
            playing += block
            last = buffer.read(block, playing, lead = 0, resync = 4096, patience = 8_000).last()
        }
        assertEquals(1, buffer.resyncs)
        assertEquals((playing - 1).toFloat(), last, "back in step with what is playing")
    }
}
