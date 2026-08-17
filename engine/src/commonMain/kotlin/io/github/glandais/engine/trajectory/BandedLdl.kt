package io.github.glandais.engine.trajectory

/**
 * `LDLᵀ` factorisation and solve for a **symmetric pentadiagonal** system — bandwidth 2.
 *
 * The matrix is held as three diagonals: `a0[i] = A[i][i]`, `a1[i] = A[i][i+1]`,
 * `a2[i] = A[i][i+2]`. Everything else is zero, which is what makes both the factorisation and the
 * solve `O(n)` with a handful of flops per row.
 *
 * ## Why direct, and why no pivoting
 *
 * The alternative — successive over-relaxation, multigrid, any iterative relaxation — brings a
 * convergence rate, a relaxation parameter to tune, and a `converged` flag whose meaning depends
 * on both. A direct factorisation has none of those: the solve is exact, so a residual test
 * measures the residual rather than the solver's patience.
 *
 * Pivoting is skipped because the matrices this is used on are strictly positive definite by
 * construction — the trajectory energy's centring term contributes a positive multiple of the
 * identity — and `LDLᵀ` on a positive-definite matrix is stable without it. [factor] reports a
 * non-positive pivot rather than continuing into nonsense, so the assumption is checked rather
 * than assumed.
 */
internal object BandedLdl {
    /**
     * Factor in place: `A = L·D·Lᵀ` with `L` unit lower triangular of bandwidth 2.
     *
     * On return [a0] holds `D`'s diagonal, [a1] holds `L[i][i-1]` at index `i-1`, and [a2] holds
     * `L[i][i-2]` at index `i-2`. The inputs are destroyed.
     *
     * @return `true` on success, `false` if a pivot was not strictly positive — which means the
     *   matrix was not positive definite and the caller's construction is wrong, not that a
     *   fallback is needed
     */
    fun factor(
        a0: DoubleArray,
        a1: DoubleArray,
        a2: DoubleArray,
    ): Boolean {
        val n = a0.size
        if (n == 0) return true
        // l1[i] = L[i][i-1], l2[i] = L[i][i-2]; written back into a1/a2 shifted, see the KDoc.
        val l1 = DoubleArray(n)
        val l2 = DoubleArray(n)
        val d = DoubleArray(n)

        for (i in 0 until n) {
            val dm1 = if (i >= 1) d[i - 1] else 0.0
            val dm2 = if (i >= 2) d[i - 2] else 0.0

            l2[i] = if (i >= 2) a2[i - 2] / dm2 else 0.0
            l1[i] =
                if (i >= 1) {
                    val correction = if (i >= 2) l2[i] * l1[i - 1] * dm2 else 0.0
                    (a1[i - 1] - correction) / dm1
                } else {
                    0.0
                }
            var pivot = a0[i]
            if (i >= 1) pivot -= l1[i] * l1[i] * dm1
            if (i >= 2) pivot -= l2[i] * l2[i] * dm2
            if (!(pivot > 0.0) || !pivot.isFinite()) return false
            d[i] = pivot
        }

        d.copyInto(a0)
        for (i in 0 until n) {
            if (i >= 1) a1[i - 1] = l1[i]
            if (i >= 2) a2[i - 2] = l2[i]
        }
        return true
    }

    /**
     * Solve `A·x = b` in place on [b], using the output of [factor].
     *
     * Forward substitution through `L`, a divide by `D`, then backward substitution through `Lᵀ`.
     */
    fun solveInPlace(
        d: DoubleArray,
        l1: DoubleArray,
        l2: DoubleArray,
        b: DoubleArray,
    ) {
        val n = d.size
        if (n == 0) return

        for (i in 0 until n) {
            var v = b[i]
            if (i >= 1) v -= l1[i - 1] * b[i - 1]
            if (i >= 2) v -= l2[i - 2] * b[i - 2]
            b[i] = v
        }
        for (i in 0 until n) b[i] /= d[i]
        for (i in n - 1 downTo 0) {
            var v = b[i]
            if (i + 1 < n) v -= l1[i] * b[i + 1]
            if (i + 2 < n) v -= l2[i] * b[i + 2]
            b[i] = v
        }
    }

    /** `y = A·x` for the unfactored three-diagonal form. Used for residual checks and gradients. */
    fun multiply(
        a0: DoubleArray,
        a1: DoubleArray,
        a2: DoubleArray,
        x: DoubleArray,
        y: DoubleArray,
    ) {
        val n = a0.size
        for (i in 0 until n) {
            var v = a0[i] * x[i]
            if (i >= 1) v += a1[i - 1] * x[i - 1]
            if (i + 1 < n) v += a1[i] * x[i + 1]
            if (i >= 2) v += a2[i - 2] * x[i - 2]
            if (i + 2 < n) v += a2[i] * x[i + 2]
            y[i] = v
        }
    }
}
