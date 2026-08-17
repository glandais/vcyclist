package io.github.glandais.engine.trajectory

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Estimates signed curvature by regressing unwrapped heading on arclength.
 *
 * ## What this replaces, and why
 *
 * The estimate it supersedes divides the bearing difference across a window of ±10 *points* by the
 * distance across the same window. That has three independent defects, all of which this approach
 * removes by construction rather than by patching:
 *
 * 1. **The ±π wrap.** A bearing difference normalised into `(-π, π]` cannot represent a turn
 *    larger than half a circle. At 1–2 m spacing the window spans ~30 m, so any bend tighter than
 *    ~9.5 m radius turns more than π across it, wraps to a *smaller* angle, and reports a
 *    correspondingly *larger* radius — roughly 2× overspeed exactly where the road is tightest.
 *    Here the heading is unwrapped into a continuous function before anything is measured, so
 *    there is no branch cut to alias against.
 * 2. **Spacing dependence.** A fixed *point* count makes every radius a function of whatever the
 *    resampler chose. The windows here are metric, so the same road yields the same curvature at
 *    any sampling density.
 * 3. **Projection shear.** See [PlanarFrame] — the frame this reads is anchored and unsheared.
 *
 * ## Scale selection
 *
 * Curvature is fitted at every window size and the **widest admissible** one wins — the widest
 * whose residual on heading stays within [SCALE_RESIDUAL_FACTOR] times the allowance, falling back
 * to the widest of all if none qualifies. Widest-first minimises variance, and a bend is not by
 * itself a reason to narrow: an arc of any radius fits a straight line in `(s, θ)` exactly, at
 * every scale. What forces a narrower window is a curvature *transition*, whose misfit grows with
 * width.
 *
 * The allowance is **measured from the trace**, not assumed — see the comment in [computeCurvature]
 * for why a fixed one cannot work, and why the measurement is taken at the widest window rather
 * than the narrowest.
 *
 * ## What it can and cannot resolve
 *
 * The narrowest window sets the tight end. A 90° bend of radius `R` is only `πR/2` of arc — 7.9 m
 * at `R = 5` — so a window wider than about `R` cannot see it at all, and the coordinate and
 * curvature kernels must both stay below it too. With the shipped defaults the measured error is
 * ~15 % high at `R = 5`, ~11 % at `R = 6`, and under 2 % from `R = 15` upward. The residual bias at
 * the tight end is toward reporting a *larger* radius, which is the unsafe direction; it is
 * tolerated because `MIN_RADIUS_M` clamps consumers at 5 m regardless, and because narrowing
 * further would start reporting noise as corners.
 *
 * The widest window sets the other end. Heading is computed from already-smoothed coordinates, so
 * adjacent residuals are correlated and the effective sample count in a window of half-width `W`
 * is `≈ W/W_g`, not the point count. Under 1.5 m of white lateral jitter the measured floor is a
 * radius of ~265 m, comfortably beyond the 200 m at which consumers stop applying a cornering
 * limit at all — so the noise lands where nothing reads it.
 */
internal object CurvatureEstimator {
    /** Multiple of `σ_θ` a window's heading residual may reach and still be admissible. */
    private const val SCALE_RESIDUAL_FACTOR = 2.0

    /**
     * Hard cap on points per side of a regression window, so a pathologically dense trace cannot
     * turn an `O(n·m)` fit into an `O(n²)` one.
     */
    private const val MAX_HALF_WINDOW_POINTS = 400

    /** Below this the normal-equation denominator is degenerate and the slope is unidentifiable. */
    private const val DEGENERACY_FACTOR = 1e-6

    /**
     * Fill [PlanarFrame.theta] with the unwrapped heading of [frame], in radians.
     *
     * Heading at `i` comes from the `i−1 → i+1` chord — centred, so it is not biased forward or
     * backward — then unwrapped cumulatively so the result is continuous across ±π.
     */
    fun computeHeadings(frame: PlanarFrame) {
        val n = frame.size
        val theta = frame.theta
        if (n == 0) return
        var previous = Double.NaN
        for (i in 0 until n) {
            val a = if (i > 0) i - 1 else 0
            val b = if (i < n - 1) i + 1 else n - 1
            val dx = frame.x[b] - frame.x[a]
            val dy = frame.y[b] - frame.y[a]
            val raw =
                if (dx == 0.0 && dy == 0.0) {
                    // Coincident neighbours carry no direction; hold the previous heading.
                    if (previous.isNaN()) 0.0 else previous
                } else {
                    atan2(dy, dx)
                }
            theta[i] =
                if (i == 0 || previous.isNaN()) {
                    raw
                } else {
                    previous + LocalFrame.unwrap(raw - previous)
                }
            previous = theta[i]
        }
    }

    /**
     * Fill [PlanarFrame.kappa] with signed curvature, m⁻¹, positive turning left.
     *
     * @param windowsM regression half-widths in metres, ascending
     * @param sigmaThetaRad floor on the heading-noise allowance; the trace's own measured noise is
     *   used instead when it is larger
     * @param curvatureSmoothM triangular half-width for the final smoothing of the fitted curvature
     */
    fun computeCurvature(
        frame: PlanarFrame,
        windowsM: DoubleArray,
        sigmaThetaRad: Double,
        curvatureSmoothM: Double,
    ) {
        val n = frame.size
        val kappa = frame.kappa
        if (n == 0) return
        require(windowsM.isNotEmpty()) { "At least one curvature window is required" }

        val scales = windowsM.size
        val slopes = DoubleArray(scales)
        val sigmas = DoubleArray(scales)
        val raw = DoubleArray(n)

        // One admissibility threshold for the whole path, from the trace's own measured noise.
        //
        // A fixed absolute gate does not work, and it took a measurement to see why. Noise raises
        // the residual at *every* scale at once — on a straight with 1.5 m of jitter the residual
        // runs 0.148 / 0.170 / 0.169 rad at 6 / 12 / 25 m, essentially flat — so a gate tight
        // enough to be useful rejects the wide windows first. That is precisely backwards: the
        // wide windows are the ones averaging the noise away, and falling back to a narrow one
        // reproduces the jitter faithfully enough to invent 29 m corners on a straight road.
        //
        // What genuinely distinguishes noise from structure is how the residual responds to a
        // *curvature transition*: a clean arc of any radius fits a line exactly in (s, θ), so a
        // bend alone leaves almost nothing (0.008 rad on a 30 m arc), while a straight-to-arc
        // transition leaves a residual that grows with window width. Setting the allowance to the
        // trace's own noise level separates the two: a jittery trace raises its own bar and stays
        // on the widest window, while a clean one keeps a tight bar and still narrows at genuine
        // transitions — which is the only place narrowing buys anything.
        // Measured at the *widest* window, deliberately. At the narrowest the fit has barely more
        // observations than parameters, so it tracks the jitter instead of measuring it and reports
        // a noise level far below the truth — which collapses the threshold and hands every station
        // back to the narrow windows. Measured that way, 1.5 m of jitter yields 20 m corners on a
        // straight; measured at the widest, 265 m.
        val measuredNoise = medianSigma(frame, windowsM[scales - 1])
        val threshold = SCALE_RESIDUAL_FACTOR * maxOf(sigmaThetaRad, measuredNoise)

        for (i in 0 until n) {
            for (w in 0 until scales) {
                val fit = fitSlope(frame, i, windowsM[w])
                slopes[w] = fit?.slope ?: Double.NaN
                sigmas[w] = fit?.residualSigma ?: Double.NaN
            }

            var chosen = Double.NaN
            var widest = Double.NaN
            for (w in scales - 1 downTo 0) {
                if (sigmas[w].isNaN()) continue
                if (widest.isNaN()) widest = slopes[w]
                if (sigmas[w] <= threshold) {
                    chosen = slopes[w]
                    break
                }
            }
            // Nothing admissible: keep the widest, never the narrowest. Under noise the narrowest
            // window is the one that reproduces the jitter most faithfully — on 1.5 m of it, that
            // means inventing 29 m corners on a straight road and capping the rider near 40 km/h
            // for a whole ride. Degrading toward the widest makes the failure mode "report no
            // corner" instead of "invent one".
            raw[i] =
                if (!chosen.isNaN()) {
                    chosen
                } else if (!widest.isNaN()) {
                    widest
                } else {
                    0.0
                }
        }

        val smoothed = LocalFrame.smooth(raw, frame.s, curvatureSmoothM)
        smoothed.copyInto(kappa)
    }

    /**
     * Median residual spread at [halfWidthM] over the whole frame — the trace's own heading-noise
     * level, used as the scale-admissibility allowance.
     *
     * The median, not the mean: corners and transitions produce large residuals by design, and a
     * mean would let a few of them raise the noise estimate for the entire route. Sorting plain
     * doubles is a total order with no tie-breaking to disagree about, so this stays deterministic
     * across targets.
     */
    private fun medianSigma(
        frame: PlanarFrame,
        halfWidthM: Double,
    ): Double {
        val n = frame.size
        if (n == 0) return 0.0
        val values = DoubleArray(n)
        var count = 0
        for (i in 0 until n) {
            val fit = fitSlope(frame, i, halfWidthM) ?: continue
            values[count++] = fit.residualSigma
        }
        if (count == 0) return 0.0
        val trimmed = values.copyOf(count)
        trimmed.sort()
        return trimmed[count / 2]
    }

    /**
     * One window's fit.
     *
     * @property slope curvature, m⁻¹
     * @property residualSigma the fit's residual standard deviation, `√(RSS/(m−2))` — the
     *   *unbiased* estimate. Dividing by `m` instead would make narrow windows look better than
     *   they are: two parameters fitted to three points always leave a small residual.
     */
    private class Fit(
        val slope: Double,
        val residualSigma: Double,
    )

    /**
     * Ordinary-least-squares slope of heading on arclength over the window of half-width
     * [halfWidthM] around [i], with the residual spread of the same fit.
     *
     * Both `s` and `θ` are centred on the window's own anchor point **inside the accumulation
     * loop**, not by subtracting prefix sums afterwards. This is not a style preference: on a
     * 500 km route `s` reaches 5·10⁵ and `Σs²` reaches 10¹¹, so forming
     * `Σ(s−s̄)² = Σs² − (Σs)²/m` from running totals cancels away three or more significant
     * digits of a quantity that is itself the regression's denominator. Centring first keeps every
     * accumulated value at the scale of the window, which is tens of metres.
     *
     * Returns `null` when the window holds too few distinct stations to identify a slope.
     */
    private fun fitSlope(
        frame: PlanarFrame,
        i: Int,
        halfWidthM: Double,
    ): Fit? {
        val n = frame.size
        val s = frame.s
        val theta = frame.theta
        val si = s[i]
        val ti = theta[i]

        var lo = i
        while (lo > 0 && si - s[lo - 1] <= halfWidthM && i - lo < MAX_HALF_WINDOW_POINTS) lo--
        var hi = i
        while (hi < n - 1 && s[hi + 1] - si <= halfWidthM && hi - i < MAX_HALF_WINDOW_POINTS) hi++

        val m = hi - lo + 1
        if (m < 3) return null

        var sumS = 0.0
        var sumSS = 0.0
        var sumT = 0.0
        var sumST = 0.0
        for (j in lo..hi) {
            val ds = s[j] - si
            val dt = theta[j] - ti
            sumS += ds
            sumSS += ds * ds
            sumT += dt
            sumST += ds * dt
        }

        val mD = m.toDouble()
        val denominator = mD * sumSS - sumS * sumS
        if (denominator < DEGENERACY_FACTOR * mD * halfWidthM * halfWidthM) return null

        val slope = (mD * sumST - sumS * sumT) / denominator
        val intercept = (sumT - slope * sumS) / mD

        var rss = 0.0
        for (j in lo..hi) {
            val ds = s[j] - si
            val residual = (theta[j] - ti) - intercept - slope * ds
            rss += residual * residual
        }
        val sigma = sqrt(rss / (mD - 2.0).coerceAtLeast(1.0))
        return if (!slope.isFinite() || !sigma.isFinite()) null else Fit(slope, sigma)
    }

    /** Radius of curvature in metres, `+∞` where the road is straight. */
    fun radiusAt(kappa: Double): Double = if (abs(kappa) < 1e-12) Double.POSITIVE_INFINITY else 1.0 / abs(kappa)
}
