package io.github.jonnyfrick.musicbootcamp.core.pitch

import kotlin.math.abs

/**
 * Non-negative least squares (Lawson & Hanson's active-set method) on the normal equations:
 * minimises |A x − b|² subject to x ≥ 0, given G = AᵀA and c = Aᵀb. Meant for a few dozen columns.
 */
internal object Nnls {
    fun solve(gram: Array<DoubleArray>, c: DoubleArray): DoubleArray {
        val n = c.size
        val x = DoubleArray(n)
        val passive = BooleanArray(n)
        var outer = 0
        while (outer++ < 3 * n + 10) {
            // Gradient of the objective (negated): w = c − G x.
            val w = DoubleArray(n) { i -> c[i] - (0 until n).sumOf { j -> gram[i][j] * x[j] } }
            val j = (0 until n).filter { !passive[it] && w[it] > TOLERANCE }.maxByOrNull { w[it] } ?: break
            passive[j] = true
            var inner = 0
            while (inner++ < 3 * n + 10) {
                val z = solvePassive(gram, c, passive)
                if ((0 until n).all { !passive[it] || z[it] > 0 }) {
                    z.copyInto(x)
                    break
                }
                var alpha = 1.0
                for (i in 0 until n) {
                    if (passive[i] && z[i] <= 0) alpha = minOf(alpha, x[i] / (x[i] - z[i]))
                }
                for (i in 0 until n) x[i] += alpha * (z[i] - x[i])
                for (i in 0 until n) {
                    if (passive[i] && x[i] <= TOLERANCE) {
                        passive[i] = false
                        x[i] = 0.0
                    }
                }
            }
        }
        return x
    }

    /** |A x − b|² = bᵀb − 2 cᵀx + xᵀGx. */
    fun residual(gram: Array<DoubleArray>, c: DoubleArray, bb: Double, x: DoubleArray): Double {
        var result = bb
        for (i in x.indices) {
            if (x[i] == 0.0) continue
            result -= 2 * c[i] * x[i]
            for (j in x.indices) result += x[i] * gram[i][j] * x[j]
        }
        return result.coerceAtLeast(0.0)
    }

    /** Unconstrained least squares on the passive columns (Gaussian elimination with pivoting). */
    private fun solvePassive(gram: Array<DoubleArray>, c: DoubleArray, passive: BooleanArray): DoubleArray {
        val index = passive.indices.filter { passive[it] }
        val m = index.size
        val a = Array(m) { r -> DoubleArray(m + 1) { k -> if (k < m) gram[index[r]][index[k]] + (if (r == k) RIDGE else 0.0) else c[index[r]] } }
        for (col in 0 until m) {
            val pivot = (col until m).maxBy { abs(a[it][col]) }
            val swap = a[col]; a[col] = a[pivot]; a[pivot] = swap
            val p = a[col][col]
            if (abs(p) < 1e-18) continue
            for (r in 0 until m) {
                if (r == col) continue
                val f = a[r][col] / p
                if (f != 0.0) for (k in col..m) a[r][k] -= f * a[col][k]
            }
        }
        val z = DoubleArray(c.size)
        for (r in 0 until m) z[index[r]] = if (abs(a[r][r]) < 1e-18) 0.0 else a[r][m] / a[r][r]
        return z
    }

    private const val TOLERANCE = 1e-10
    private const val RIDGE = 1e-9
}
