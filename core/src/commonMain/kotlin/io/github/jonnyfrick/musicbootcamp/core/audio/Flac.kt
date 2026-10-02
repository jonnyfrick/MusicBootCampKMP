package io.github.jonnyfrick.musicbootcamp.core.audio

/** Decoded audio: one array of samples per channel, as integers of [bitsPerSample] bits. */
class FlacAudio(val sampleRate: Int, val bitsPerSample: Int, val channels: List<IntArray>) {
    /** The MD5 the encoder computed over the unencoded audio (16 bytes), to verify a decoder. */
    var md5: ByteArray = ByteArray(0)
        internal set
}

/**
 * Decodes FLAC (https://xiph.org/flac/format.html), the lossless format the app's instrument
 * samples are stored in, in common code so every platform reads them the same way: all subframe
 * types (constant, verbatim, fixed and linear prediction), Rice-coded residuals with escapes,
 * wasted bits and the stereo decorrelation modes. Checksums are not verified.
 */
object Flac {
    fun decode(bytes: ByteArray): FlacAudio {
        val input = BitReader(bytes)
        require(input.bits(32) == 0x664C6143) { "Not a FLAC file" } // "fLaC"
        var sampleRate = 0
        var channelCount = 0
        var bitsPerSample = 0
        var totalSamples = 0L
        var md5 = ByteArray(0)
        var last = false
        while (!last) {
            last = input.bits(1) == 1
            val type = input.bits(7)
            val length = input.bits(24)
            if (type == 0) { // STREAMINFO
                input.skip(16 + 16 + 24 + 24)
                sampleRate = input.bits(20)
                channelCount = input.bits(3) + 1
                bitsPerSample = input.bits(5) + 1
                totalSamples = (input.bits(4).toLong() shl 32) or (input.bits(32).toLong() and 0xFFFFFFFFL)
                md5 = ByteArray(16) { input.bits(8).toByte() }
            } else {
                input.skip(length * 8)
            }
        }
        require(sampleRate > 0 && totalSamples in 1..Int.MAX_VALUE) { "FLAC stream without a usable STREAMINFO" }

        val channels = List(channelCount) { IntArray(totalSamples.toInt()) }
        var position = 0
        while (position < totalSamples && input.hasMore()) {
            position += decodeFrame(input, channels, position, bitsPerSample)
        }
        return FlacAudio(sampleRate, bitsPerSample, channels).also { it.md5 = md5 }
    }

    /** Decodes one frame into [channels] from [position] on; returns its number of samples. */
    private fun decodeFrame(input: BitReader, channels: List<IntArray>, position: Int, streamBits: Int): Int {
        require(input.bits(14) == 0x3FFE) { "Lost the FLAC frame sync at sample $position" }
        input.skip(2) // reserved, blocking strategy
        val blockSizeCode = input.bits(4)
        val sampleRateCode = input.bits(4)
        val assignment = input.bits(4)
        val sampleSizeCode = input.bits(3)
        input.skip(1)
        // The frame or sample number, coded like UTF-8: the leading ones give its length.
        var lead = input.bits(8)
        while (lead and 0xC0 == 0xC0) {
            input.skip(8)
            lead = lead shl 1
        }
        val blockSize = when (blockSizeCode) {
            1 -> 192
            in 2..5 -> 576 shl (blockSizeCode - 2)
            6 -> input.bits(8) + 1
            7 -> input.bits(16) + 1
            in 8..15 -> 256 shl (blockSizeCode - 8)
            else -> throw IllegalArgumentException("Reserved FLAC block size")
        }
        when (sampleRateCode) {
            12 -> input.skip(8)
            13, 14 -> input.skip(16)
        }
        input.skip(8) // CRC-8
        val bits = when (sampleSizeCode) {
            0 -> streamBits
            1 -> 8
            2 -> 12
            4 -> 16
            5 -> 20
            6 -> 24
            else -> throw IllegalArgumentException("Reserved FLAC sample size")
        }
        val count = minOf(blockSize, channels[0].size - position)
        val block = List(channels.size) { IntArray(blockSize) }
        for (channel in channels.indices) {
            // The side channel of a decorrelated pair needs one bit more.
            val side = (assignment == 8 && channel == 1) || (assignment == 9 && channel == 0) || (assignment == 10 && channel == 1)
            decodeSubframe(input, block[channel], if (side) bits + 1 else bits)
        }
        when (assignment) {
            8 -> for (i in 0 until blockSize) block[1][i] = block[0][i] - block[1][i] // left, side
            9 -> for (i in 0 until blockSize) block[0][i] = block[0][i] + block[1][i] // side, right
            10 -> for (i in 0 until blockSize) { // mid, side
                val side = block[1][i]
                val mid = (block[0][i] shl 1) or (side and 1)
                block[0][i] = (mid + side) shr 1
                block[1][i] = (mid - side) shr 1
            }
        }
        for (channel in channels.indices) block[channel].copyInto(channels[channel], position, 0, count)
        input.alignToByte()
        input.skip(16) // CRC-16
        return blockSize
    }

    private fun decodeSubframe(input: BitReader, out: IntArray, frameBits: Int) {
        input.skip(1)
        val type = input.bits(6)
        var wasted = 0
        if (input.bits(1) == 1) wasted = input.unary() + 1
        val bits = frameBits - wasted
        when {
            type == 0 -> out.fill(input.signed(bits))
            type == 1 -> for (i in out.indices) out[i] = input.signed(bits)
            type in 8..12 -> {
                val order = type - 8
                for (i in 0 until order) out[i] = input.signed(bits)
                decodeResidual(input, out, order)
                restoreFixed(out, order)
            }
            type >= 32 -> {
                val order = type - 31
                for (i in 0 until order) out[i] = input.signed(bits)
                val precision = input.bits(4) + 1
                val shift = input.signed(5)
                val coefficients = IntArray(order) { input.signed(precision) }
                decodeResidual(input, out, order)
                for (i in order until out.size) {
                    var sum = 0L
                    for (j in 0 until order) sum += coefficients[j].toLong() * out[i - 1 - j]
                    out[i] += (sum shr shift).toInt()
                }
            }
            else -> throw IllegalArgumentException("Reserved FLAC subframe type $type")
        }
        if (wasted > 0) for (i in out.indices) out[i] = out[i] shl wasted
    }

    /** Turns the residual in [out] (from [order] on) back into samples, for the fixed predictors. */
    private fun restoreFixed(out: IntArray, order: Int) {
        for (i in order until out.size) {
            out[i] += when (order) {
                0 -> 0
                1 -> out[i - 1]
                2 -> 2 * out[i - 1] - out[i - 2]
                3 -> 3 * out[i - 1] - 3 * out[i - 2] + out[i - 3]
                else -> 4 * out[i - 1] - 6 * out[i - 2] + 4 * out[i - 3] - out[i - 4]
            }
        }
    }

    /** Reads the Rice-coded residual into [out] from [order] on. */
    private fun decodeResidual(input: BitReader, out: IntArray, order: Int) {
        val parameterBits = when (input.bits(2)) {
            0 -> 4
            1 -> 5
            else -> throw IllegalArgumentException("Reserved FLAC residual coding")
        }
        val escape = (1 shl parameterBits) - 1
        val partitionOrder = input.bits(4)
        val partitions = 1 shl partitionOrder
        var index = order
        for (partition in 0 until partitions) {
            val size = (out.size shr partitionOrder) - if (partition == 0) order else 0
            val parameter = input.bits(parameterBits)
            if (parameter == escape) {
                val rawBits = input.bits(5)
                repeat(size) { out[index++] = if (rawBits == 0) 0 else input.signed(rawBits) }
            } else {
                repeat(size) {
                    val folded = (input.unary() shl parameter) or (if (parameter == 0) 0 else input.bits(parameter))
                    out[index++] = (folded ushr 1) xor -(folded and 1)
                }
            }
        }
    }

    /** Reads bits, most significant first, as FLAC writes them. */
    private class BitReader(private val bytes: ByteArray) {
        private var position = 0L // in bits

        fun hasMore() = position < bytes.size * 8L

        fun bits(count: Int): Int {
            var value = 0
            var left = count
            while (left > 0) {
                val index = (position ushr 3).toInt()
                val offset = (position and 7).toInt()
                val take = minOf(left, 8 - offset)
                val byte = bytes[index].toInt() and 0xFF
                value = (value shl take) or ((byte ushr (8 - offset - take)) and ((1 shl take) - 1))
                position += take
                left -= take
            }
            return value
        }

        fun signed(count: Int): Int {
            val value = bits(count)
            return if (count < 32) (value shl (32 - count)) shr (32 - count) else value
        }

        /** The number of zero bits before the next one bit, which is consumed too. */
        fun unary(): Int {
            var zeros = 0
            while (bits(1) == 0) zeros++
            return zeros
        }

        fun skip(count: Int) {
            position += count
        }

        fun alignToByte() {
            position = (position + 7) and 7L.inv()
        }
    }
}
