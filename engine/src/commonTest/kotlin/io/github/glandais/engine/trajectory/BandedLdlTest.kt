package io.github.glandais.engine.trajectory

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class BandedLdlTest {
    /** Dense `y = A·x` from the three diagonals, independent of [BandedLdl.multiply]. */
    private fun denseMultiply(
        a0: DoubleArray,
        a1: DoubleArray,
        a2: DoubleArray,
        x: DoubleArray,
    ): DoubleArray {
        val n = a0.size
        val a = Array(n) { DoubleArray(n) }
        for (i in 0 until n) {
            a[i][i] = a0[i]
            if (i + 1 < n) {
                a[i][i + 1] = a1[i]
                a[i + 1][i] = a1[i]
            }
            if (i + 2 < n) {
                a[i][i + 2] = a2[i]
                a[i + 2][i] = a2[i]
            }
        }
        return DoubleArray(n) { i -> (0 until n).sumOf { j -> a[i][j] * x[j] } }
    }

    /** Diagonally dominant, hence positive definite, and not symmetric-by-accident. */
    private fun system(n: Int): Triple<DoubleArray, DoubleArray, DoubleArray> {
        val a0 = DoubleArray(n) { 10.0 + 0.5 * it }
        val a1 = DoubleArray(n) { if (it < n - 1) -2.0 - 0.1 * it else 0.0 }
        val a2 = DoubleArray(n) { if (it < n - 2) 0.75 + 0.05 * it else 0.0 }
        return Triple(a0, a1, a2)
    }

    @Test
    fun `solves a hand-built 12-node system to 1e-12`() {
        val n = 12
        val (a0, a1, a2) = system(n)
        val x = DoubleArray(n) { 1.0 + 0.3 * it - 0.02 * it * it }
        val b = denseMultiply(a0, a1, a2, x)

        val f0 = a0.copyOf()
        val f1 = a1.copyOf()
        val f2 = a2.copyOf()
        assertTrue(BandedLdl.factor(f0, f1, f2), "factorisation rejected a positive-definite system")

        val solved = b.copyOf()
        BandedLdl.solveInPlace(f0, f1, f2, solved)
        for (i in 0 until n) {
            assertTrue(
                abs(solved[i] - x[i]) < 1e-12,
                "x[$i]: expected ${x[i]}, solved ${solved[i]} (error ${abs(solved[i] - x[i])})",
            )
        }
    }

    @Test
    fun `round-trips A times A-inverse b for several sizes`() {
        for (n in listOf(1, 2, 3, 5, 40, 257)) {
            val (a0, a1, a2) = system(n)
            val b = DoubleArray(n) { 1.0 - 0.01 * it }
            val f0 = a0.copyOf()
            val f1 = a1.copyOf()
            val f2 = a2.copyOf()
            assertTrue(BandedLdl.factor(f0, f1, f2), "n=$n: not positive definite?")
            val x = b.copyOf()
            BandedLdl.solveInPlace(f0, f1, f2, x)
            val back = denseMultiply(a0, a1, a2, x)
            for (i in 0 until n) {
                assertTrue(abs(back[i] - b[i]) < 1e-9, "n=$n at $i: ${back[i]} vs ${b[i]}")
            }
        }
    }

    @Test
    fun `multiply agrees with a dense product`() {
        val n = 20
        val (a0, a1, a2) = system(n)
        val x = DoubleArray(n) { 0.5 * it - 3.0 }
        val expected = denseMultiply(a0, a1, a2, x)
        val actual = DoubleArray(n)
        BandedLdl.multiply(a0, a1, a2, x, actual)
        for (i in 0 until n) assertTrue(abs(actual[i] - expected[i]) < 1e-12, "at $i")
    }

    /**
     * The factorisation must report an indefinite matrix rather than produce a plausible-looking
     * answer. This is the guard that turns "the Hessian is positive definite by construction" from
     * an assumption into a checked one.
     */
    @Test
    fun `an indefinite system is rejected, not silently solved`() {
        val a0 = doubleArrayOf(1.0, -5.0, 1.0, 1.0)
        val a1 = doubleArrayOf(3.0, 3.0, 0.0, 0.0)
        val a2 = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        assertTrue(!BandedLdl.factor(a0, a1, a2), "an indefinite matrix must be rejected")
    }

    @Test
    fun `a diagonal system is solved exactly`() {
        val n = 6
        val a0 = DoubleArray(n) { 2.0 + it }
        val a1 = DoubleArray(n)
        val a2 = DoubleArray(n)
        val b = DoubleArray(n) { 1.0 * (it + 1) }
        assertTrue(BandedLdl.factor(a0, a1, a2))
        val x = b.copyOf()
        BandedLdl.solveInPlace(a0, a1, a2, x)
        for (i in 0 until n) {
            assertTrue(abs(x[i] - (i + 1.0) / (2.0 + i)) < 1e-14, "at $i: ${x[i]}")
        }
    }
}
