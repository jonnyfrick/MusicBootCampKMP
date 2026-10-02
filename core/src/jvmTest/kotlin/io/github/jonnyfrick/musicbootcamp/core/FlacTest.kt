package io.github.jonnyfrick.musicbootcamp.core

import io.github.jonnyfrick.musicbootcamp.core.audio.Flac
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlacTest {
    @Test
    fun aPianoSampleDecodesBitForBit() {
        // One of the app's piano samples (A7 of the Salamander Grand Piano, see tools/prepare_piano_samples.py).
        val bytes = javaClass.getResourceAsStream("/audio/piano-105.flac")!!.readBytes()
        val audio = Flac.decode(bytes)
        assertEquals(44_100, audio.sampleRate)
        assertEquals(16, audio.bitsPerSample)
        assertEquals(1, audio.channels.size)
        val samples = audio.channels[0]
        assertTrue(samples.size > 2 * 44_100, "a few seconds: ${samples.size} samples")

        // The encoder stored the MD5 of the audio it encoded: little-endian 16-bit samples.
        val raw = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s ->
            raw[2 * i] = s.toByte()
            raw[2 * i + 1] = (s shr 8).toByte()
        }
        assertContentEquals(audio.md5, MessageDigest.getInstance("MD5").digest(raw))
    }
}
