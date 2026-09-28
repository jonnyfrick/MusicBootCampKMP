package io.github.jonnyfrick.musicbootcamp.core.audio

import kotlin.math.roundToInt

/** Audio read from a WAV file: one sample array per channel, -1..1. */
class WavAudio(val sampleRate: Int, val channels: List<FloatArray>)

/** 16-bit PCM WAV files, written block by block (the header is written last, when the length is known). */
object Wav {
    const val HEADER_SIZE = 44

    fun header(sampleRate: Int, channels: Int, dataBytes: Long): ByteArray {
        val bytes = ByteArray(HEADER_SIZE)
        fun text(offset: Int, value: String) = value.forEachIndexed { i, c -> bytes[offset + i] = c.code.toByte() }
        fun int(offset: Int, value: Long, size: Int) {
            for (i in 0 until size) bytes[offset + i] = (value shr (8 * i)).toByte()
        }
        val blockAlign = channels * 2
        val data = dataBytes.coerceAtMost(UInt.MAX_VALUE.toLong() - 36)
        text(0, "RIFF"); int(4, 36 + data, 4); text(8, "WAVE")
        text(12, "fmt "); int(16, 16, 4); int(20, 1, 2); int(22, channels.toLong(), 2)
        int(24, sampleRate.toLong(), 4); int(28, sampleRate.toLong() * blockAlign, 4)
        int(32, blockAlign.toLong(), 2); int(34, 16, 2)
        text(36, "data"); int(40, data, 4)
        return bytes
    }

    /** Interleaves equally long channels into 16-bit little-endian PCM. */
    fun pcm16(channels: List<FloatArray>): ByteArray {
        val frames = channels.first().size
        require(channels.all { it.size == frames }) { "Channels differ in length" }
        val bytes = ByteArray(frames * channels.size * 2)
        var index = 0
        for (frame in 0 until frames) {
            for (channel in channels) {
                val value = (channel[frame] * 32767f).roundToInt().coerceIn(-32768, 32767)
                bytes[index++] = value.toByte()
                bytes[index++] = (value shr 8).toByte()
            }
        }
        return bytes
    }

    /** Reads a 16-bit PCM WAV file as written by [header] and [pcm16]. */
    fun read(bytes: ByteArray): WavAudio {
        fun int(offset: Int, size: Int): Int {
            var value = 0
            for (i in 0 until size) value = value or ((bytes[offset + i].toInt() and 0xFF) shl (8 * i))
            return value
        }
        fun text(offset: Int) = (0 until 4).map { bytes[offset + it].toInt().toChar() }.joinToString("")
        require(text(0) == "RIFF" && text(8) == "WAVE") { "Not a WAV file" }
        var offset = 12
        var channels = 0
        var sampleRate = 0
        while (offset + 8 <= bytes.size) {
            val id = text(offset)
            val size = int(offset + 4, 4)
            val body = offset + 8
            when (id) {
                "fmt " -> {
                    require(int(body, 2) == 1 && int(body + 14, 2) == 16) { "Only 16-bit PCM is supported" }
                    channels = int(body + 2, 2)
                    sampleRate = int(body + 4, 4)
                }
                "data" -> {
                    require(channels > 0) { "No format before the data" }
                    // A recording that was not finished has no length yet: take what is there.
                    val length = if (size <= 0 || body + size > bytes.size) bytes.size - body else size
                    val frames = length / (2 * channels)
                    val result = List(channels) { FloatArray(frames) }
                    var index = body
                    for (frame in 0 until frames) {
                        for (channel in 0 until channels) {
                            val value = (bytes[index].toInt() and 0xFF) or (bytes[index + 1].toInt() shl 8)
                            result[channel][frame] = value / 32768f
                            index += 2
                        }
                    }
                    return WavAudio(sampleRate, result)
                }
            }
            offset = body + size + (size and 1)
        }
        error("No audio data")
    }
}
