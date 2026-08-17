package io.github.glandais.engine.trajectory

import kotlin.math.sqrt

/**
 * The **exact** curvature of the offset curve, and the arclength stretch that goes with it.
 *
 * With `u = 1 − κn`, `v = n'`, `u' = −(κ'n + κn')`:
 *
 * ```
 * κ_traj  = [ κ(u² + v²) + u·n'' − v·u' ] / (u² + v²)^{3/2}
 * ds_traj = √(u² + v²) · ds
 * ```
 *
 * The solver minimises a *linearisation* of this, `κ̂ = κ + n'' + κ²n`, because a linearisation is
 * what makes the energy quadratic and the minimiser unique. This is the form that gets written out
 * and that feasibility is checked against, so the approximation never escapes the solver. The two
 * agree closely inside the corridor precisely because the regularity clamp keeps `u` bounded away
 * from zero — the term that blows up as the offset map approaches its fold.
 */
internal object OffsetCurvature {
    /**
     * Signed curvature of the offset curve, m⁻¹, positive turning left.
     *
     * Ends fall back to the reference curvature: a one-sided second difference there would be
     * dominated by the pinned boundary rather than by the road.
     */
    fun exact(
        frame: PlanarFrame,
        n: DoubleArray,
    ): DoubleArray {
        val size = frame.size
        val out = DoubleArray(size)
        if (size == 0) return out
        val kappa = frame.kappa
        val s = frame.s

        val kappaPrime = derivative(kappa, s)

        for (i in 0 until size) {
            if (i < 1 || i + 1 >= size) {
                out[i] = kappa[i]
                continue
            }
            val hMinus = s[i] - s[i - 1]
            val hPlus = s[i + 1] - s[i]
            val denom = hPlus * hMinus * (hPlus + hMinus)
            if (denom <= 0.0) {
                out[i] = kappa[i]
                continue
            }
            val nPrime =
                (hMinus * hMinus * n[i + 1] + (hPlus * hPlus - hMinus * hMinus) * n[i] - hPlus * hPlus * n[i - 1]) /
                    denom
            val nSecond = 2.0 * (hPlus * n[i - 1] - (hPlus + hMinus) * n[i] + hMinus * n[i + 1]) / denom

            val u = 1.0 - kappa[i] * n[i]
            val v = nPrime
            val uPrime = -(kappaPrime[i] * n[i] + kappa[i] * nPrime)
            val q = u * u + v * v
            out[i] =
                if (q <= 1e-12) {
                    kappa[i]
                } else {
                    (kappa[i] * q + u * nSecond - v * uPrime) / (q * sqrt(q))
                }
        }
        return out
    }

    /** Central difference of [v] with respect to [s], one-sided at the ends. */
    private fun derivative(
        v: DoubleArray,
        s: DoubleArray,
    ): DoubleArray {
        val size = v.size
        val out = DoubleArray(size)
        for (i in 0 until size) {
            val a = if (i > 0) i - 1 else 0
            val b = if (i < size - 1) i + 1 else size - 1
            val ds = s[b] - s[a]
            out[i] = if (ds > 0.0) (v[b] - v[a]) / ds else 0.0
        }
        return out
    }
}
