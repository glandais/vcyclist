package io.github.glandais.engine.trajectory

import kotlin.math.abs

/**
 * Outcome of a projected-Newton solve.
 *
 * @property offset the minimiser, one lateral offset per station, metres
 * @property iterations Newton iterations actually taken
 * @property gradientInfNorm final `‖g_F‖_∞` over the free set, **relative** to the initial
 *   gradient — a number that means the same thing on a 5 m fixture and a 500 km route
 * @property converged whether the residual test was met before the iteration cap
 * @property activeConstraints how many stations finished pinned against a corridor bound
 */
internal class QpResult(
    val offset: DoubleArray,
    val iterations: Int,
    val gradientInfNorm: Double,
    val converged: Boolean,
    val activeConstraints: Int,
)

/**
 * Minimises [OffsetEnergy] subject to `lo ≤ n ≤ hi`, by projected Newton with an active set.
 *
 * ## Why this terminates, and what `converged` means
 *
 * The energy is strictly convex — the centring term makes its Hessian positive definite — over a
 * box, which is a closed convex set. Projected Newton on such a problem identifies the correct
 * active set in finitely many steps and then takes one exact Newton step to the minimiser. In
 * practice that is three to six iterations here.
 *
 * Because the free-set solve is a direct factorisation rather than a relaxation, `converged` is a
 * statement about the residual and not about the solver's patience.
 *
 * ## Scale invariance
 *
 * Both tolerances are relative, and that is not fussiness. The energy and its gradient scale with
 * `Σ w_i`, i.e. with route length in metres, so a 500 km route's gradient is some `10⁵` times a
 * 5 m fixture's. An absolute stopping threshold would therefore mean something different on every
 * route, and would be tightest relative to scale exactly where cross-target agreement is hardest.
 * The gradient test is normalised by the initial gradient, and the line search uses an Armijo
 * condition proportional to `|gᵀd|` rather than a fixed decrement.
 */
internal object OffsetQp {
    /** Armijo sufficient-decrease coefficient. */
    private const val ARMIJO_C1 = 1e-4

    /** Halvings before the line search gives up on a direction. */
    private const val MAX_BACKTRACKS = 20

    /**
     * @param seed starting point; projected onto the box before the first iteration
     * @param boundEpsilonM how close to a bound counts as on it. A finite snap width is what keeps
     *   the active set from chattering between two stations that are numerically indistinguishable
     *   from their bound — and it is why cross-target agreement on the offset is stated at 1e-3 m
     *   rather than at 1e-9: which side of the bound a station lands on is a discontinuous function
     *   of the iterate.
     */
    fun solve(
        energy: OffsetEnergy,
        lo: DoubleArray,
        hi: DoubleArray,
        seed: DoubleArray,
        maxIterations: Int,
        gradientTolerance: Double,
        boundEpsilonM: Double,
    ): QpResult {
        val n = energy.size
        val x = DoubleArray(n) { seed[it].coerceIn(lo[it], hi[it]) }
        val g = DoubleArray(n)
        val scratch = DoubleArray(n)
        val direction = DoubleArray(n)
        val candidate = DoubleArray(n)

        energy.gradient(x, g)
        var referenceGradient = 0.0
        for (i in 0 until n) {
            if (isFree(x[i], lo[i], hi[i], g[i], boundEpsilonM) && abs(g[i]) > referenceGradient) {
                referenceGradient = abs(g[i])
            }
        }
        if (referenceGradient <= 0.0) referenceGradient = 1.0

        var iterations = 0
        var converged = false
        var relativeResidual = 0.0

        // Compacted free-set arrays, allocated once and re-sliced each iteration.
        val freeIndex = IntArray(n)
        val f0 = DoubleArray(n)
        val f1 = DoubleArray(n)
        val f2 = DoubleArray(n)
        val rhs = DoubleArray(n)

        while (iterations < maxIterations) {
            energy.gradient(x, g)

            var freeCount = 0
            var residual = 0.0
            for (i in 0 until n) {
                if (isFree(x[i], lo[i], hi[i], g[i], boundEpsilonM)) {
                    freeIndex[freeCount++] = i
                    if (abs(g[i]) > residual) residual = abs(g[i])
                }
            }
            relativeResidual = residual / referenceGradient
            if (freeCount == 0 || relativeResidual <= gradientTolerance) {
                converged = true
                break
            }

            buildFreeSystem(energy, freeIndex, freeCount, f0, f1, f2)
            // The factorisation destroys its inputs, so it runs on right-sized copies; the free
            // set changes between iterations, which is why they cannot be hoisted out of the loop.
            val fa0 = f0.copyOf(freeCount)
            val fa1 = f1.copyOf(freeCount)
            val fa2 = f2.copyOf(freeCount)
            if (!BandedLdl.factor(fa0, fa1, fa2)) {
                // A non-positive pivot means the reduced Hessian is not positive definite, which
                // cannot happen for this energy — the centring term guarantees it. Stop rather
                // than continue into nonsense.
                break
            }
            for (p in 0 until freeCount) rhs[p] = -g[freeIndex[p]]
            BandedLdl.solveInPlace(fa0, fa1, fa2, rhs)

            // Newton on the free set, zero on the active set.
            //
            // Giving the active variables a descent direction of their own — the usual remedy for
            // slow constraint release — is a no-op here: a variable is active precisely because its
            // gradient pushes it *out* of the box, so its descent direction is clipped straight
            // back by the projection. Variables whose gradient points inward are already free.
            for (i in 0 until n) direction[i] = 0.0
            for (p in 0 until freeCount) direction[freeIndex[p]] = rhs[p]

            var directionalDerivative = 0.0
            for (p in 0 until freeCount) {
                val i = freeIndex[p]
                directionalDerivative += g[i] * direction[i]
            }
            if (directionalDerivative >= 0.0) break

            val current = energy.value(x, scratch)
            var step = 1.0
            var accepted = false
            for (attempt in 0 until MAX_BACKTRACKS) {
                for (i in 0 until n) {
                    candidate[i] = (x[i] + step * direction[i]).coerceIn(lo[i], hi[i])
                }
                val trial = energy.value(candidate, scratch)
                if (trial <= current + ARMIJO_C1 * step * directionalDerivative) {
                    accepted = true
                    break
                }
                step /= 2.0
            }
            if (!accepted) break
            candidate.copyInto(x)
            iterations++
        }

        var active = 0
        for (i in 0 until n) {
            if (x[i] <= lo[i] + boundEpsilonM || x[i] >= hi[i] - boundEpsilonM) active++
        }
        return QpResult(x, iterations, relativeResidual, converged, active)
    }

    /**
     * A station is free unless it sits on a bound *and* the gradient pushes it further out.
     *
     * Sitting on a bound is not by itself a reason to fix a variable: the very next step may want
     * to move it back inside, and freezing it would stall the solve at a non-optimal point.
     */
    private fun isFree(
        x: Double,
        lo: Double,
        hi: Double,
        g: Double,
        epsilon: Double,
    ): Boolean {
        if (hi - lo <= 2.0 * epsilon) return false
        if (x <= lo + epsilon && g > 0.0) return false
        if (x >= hi - epsilon && g < 0.0) return false
        return true
    }

    /**
     * Build the reduced Hessian over the free set, still banded.
     *
     * Symmetric deletion of rows and columns can only *shrink* index distances, so a bandwidth-2
     * matrix stays bandwidth 2 under it — that is what lets the free-set solve stay `O(n)` instead
     * of degrading to a dense factorisation as constraints activate.
     */
    private fun buildFreeSystem(
        energy: OffsetEnergy,
        freeIndex: IntArray,
        freeCount: Int,
        f0: DoubleArray,
        f1: DoubleArray,
        f2: DoubleArray,
    ) {
        for (p in 0 until freeCount) {
            val i = freeIndex[p]
            f0[p] = energy.h0[i]
            f1[p] = 0.0
            f2[p] = 0.0
            if (p + 1 < freeCount) {
                when (freeIndex[p + 1] - i) {
                    1 -> f1[p] = energy.h1[i]
                    2 -> f1[p] = energy.h2[i]
                }
            }
            if (p + 2 < freeCount && freeIndex[p + 2] - i == 2) {
                f2[p] = energy.h2[i]
            }
        }
    }
}
