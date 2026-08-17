package io.github.glandais.engine.trajectory

/**
 * The quadratic form minimised by the racing-line solver.
 *
 * ```
 * E(n) = Σ_i w_i [ ρ_i·κ̂_i(n)²  +  L_R⁻²·(n'_i)²  +  L_C⁻⁴·(n_i − n̄_i)² ]
 * κ̂_i(n) = κ_i + n''_i + κ_i²·n_i
 * ```
 *
 * Three terms, each earning its place:
 *
 * - **`κ̂²`** is the objective proper. `κ̂` is the first-order expansion of the offset curve's
 *   curvature in `n`, so minimising its square straightens the ridden line — which is what makes a
 *   corner faster. It is affine in `n`, so the whole energy is quadratic and has one minimum.
 * - **`(n')²`** penalises lateral movement per metre travelled. To second order this is exactly
 *   the excess length the line pays for weaving, and it doubles as the steering-rate cost, so a
 *   single term covers both.
 * - **`(n − n̄)²`** is a centring prior pulling toward the projection of zero onto the corridor.
 *   It is what makes the failure mode under noise "stay put" rather than "wander", and it is also
 *   what makes the Hessian *strictly* positive definite rather than merely semi-definite — the
 *   first two terms alone have a null space.
 *
 * Weights are arclength, `w_i = Δs_i`, so the energy is an integral over the route rather than a
 * sum over however the resampler happened to place stations.
 *
 * The Hessian is symmetric and **pentadiagonal**: `κ̂` couples `i ± 1` through `n''`, so `κ̂²`
 * couples `i ± 2`. That bandwidth is why [BandedLdl] can solve it exactly in `O(n)`.
 */
internal class OffsetEnergy(
    val h0: DoubleArray,
    val h1: DoubleArray,
    val h2: DoubleArray,
    val linear: DoubleArray,
    /** `½·gᵀ…`-free constant term; needed only so [value] returns a true energy. */
    private val constant: Double,
) {
    val size: Int get() = h0.size

    /** `E(n) = ½·nᵀHn + bᵀn + c`. */
    fun value(
        n: DoubleArray,
        scratch: DoubleArray,
    ): Double {
        BandedLdl.multiply(h0, h1, h2, n, scratch)
        var acc = constant
        for (i in n.indices) acc += 0.5 * scratch[i] * n[i] + linear[i] * n[i]
        return acc
    }

    /** `∇E(n) = Hn + b`, written into [out]. */
    fun gradient(
        n: DoubleArray,
        out: DoubleArray,
    ) {
        BandedLdl.multiply(h0, h1, h2, n, out)
        for (i in out.indices) out[i] += linear[i]
    }

    companion object {
        /**
         * Assemble the energy for [frame], with the corridor's projection-of-zero [center] as the
         * centring target and per-station time weights [rho].
         *
         * Stations 0 and `n−1` contribute no stencil: their neighbours do not both exist. They are
         * pinned by the corridor anyway, and the pins come in adjacent pairs precisely so the
         * bandwidth-2 stencil never reaches past them.
         */
        fun assemble(
            frame: PlanarFrame,
            center: DoubleArray,
            rho: DoubleArray,
            steeringLengthM: Double,
            centeringLengthM: Double,
        ): OffsetEnergy {
            val n = frame.size
            val h0 = DoubleArray(n)
            val h1 = DoubleArray(n)
            val h2 = DoubleArray(n)
            val linear = DoubleArray(n)
            var constant = 0.0

            val lambdaR = 1.0 / (steeringLengthM * steeringLengthM)
            val lambdaC = 1.0 / (centeringLengthM * centeringLengthM * centeringLengthM * centeringLengthM)

            // Local stencil coefficients, reused per station.
            val a = DoubleArray(3)
            val b = DoubleArray(3)

            for (i in 0 until n) {
                val hMinus = if (i >= 1) frame.s[i] - frame.s[i - 1] else 0.0
                val hPlus = if (i + 1 < n) frame.s[i + 1] - frame.s[i] else 0.0
                val weight = 0.5 * (hMinus + hPlus)

                // Centring term: present at every station, including the ends. This is the term
                // that keeps H strictly positive definite.
                h0[i] += 2.0 * weight * lambdaC
                linear[i] -= 2.0 * weight * lambdaC * center[i]
                constant += weight * lambdaC * center[i] * center[i]

                if (i < 1 || i + 1 >= n) continue
                val denom = hPlus * hMinus * (hPlus + hMinus)
                if (denom <= 0.0) continue

                // n''_i and n'_i by non-uniform central differences.
                a[0] = 2.0 * hPlus / denom
                a[1] = -2.0 * (hPlus + hMinus) / denom + frame.kappa[i] * frame.kappa[i]
                a[2] = 2.0 * hMinus / denom

                b[0] = -hPlus * hPlus / denom
                b[1] = (hPlus * hPlus - hMinus * hMinus) / denom
                b[2] = hMinus * hMinus / denom

                val wRho = weight * rho[i]
                val wR = weight * lambdaR
                val kappaI = frame.kappa[i]

                for (p in 0..2) {
                    val row = i - 1 + p
                    // Linear term from the constant κ_i inside (κ_i + A_i)².
                    linear[row] += 2.0 * wRho * kappaI * a[p]
                    for (q in p..2) {
                        val col = i - 1 + q
                        val contribution = 2.0 * (wRho * a[p] * a[q] + wR * b[p] * b[q])
                        when (col - row) {
                            0 -> h0[row] += contribution
                            1 -> h1[row] += contribution
                            2 -> h2[row] += contribution
                        }
                    }
                }
                constant += wRho * kappaI * kappaI
            }

            return OffsetEnergy(h0, h1, h2, linear, constant)
        }
    }
}
