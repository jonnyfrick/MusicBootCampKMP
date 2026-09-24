package io.github.jonnyfrick.musicbootcamp.core.pitch

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** In-place iterative radix-2 FFT; [re] and [im] must have the same power-of-two size. */
internal fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean = false) {
    val n = re.size
    require(n == im.size && n > 0 && n and (n - 1) == 0) { "FFT size must be a power of two" }

    // Bit-reversal permutation.
    var j = 0
    for (i in 1 until n) {
        var bit = n shr 1
        while (j and bit != 0) {
            j = j xor bit
            bit = bit shr 1
        }
        j = j xor bit
        if (i < j) {
            re[i] = re[j].also { re[j] = re[i] }
            im[i] = im[j].also { im[j] = im[i] }
        }
    }

    var length = 2
    val sign = if (inverse) 1.0 else -1.0
    while (length <= n) {
        val angle = sign * 2 * PI / length
        val stepRe = cos(angle)
        val stepIm = sin(angle)
        for (start in 0 until n step length) {
            var wRe = 1.0
            var wIm = 0.0
            for (k in 0 until length / 2) {
                val a = start + k
                val b = a + length / 2
                val tRe = re[b] * wRe - im[b] * wIm
                val tIm = re[b] * wIm + im[b] * wRe
                re[b] = re[a] - tRe
                im[b] = im[a] - tIm
                re[a] += tRe
                im[a] += tIm
                val nextRe = wRe * stepRe - wIm * stepIm
                wIm = wRe * stepIm + wIm * stepRe
                wRe = nextRe
            }
        }
        length = length shl 1
    }

    if (inverse) {
        for (i in 0 until n) {
            re[i] /= n
            im[i] /= n
        }
    }
}

internal fun nextPowerOfTwo(value: Int): Int {
    var size = 1
    while (size < value) size = size shl 1
    return size
}
