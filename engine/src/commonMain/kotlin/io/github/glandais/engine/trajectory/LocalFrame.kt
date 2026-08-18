package io.github.glandais.engine.trajectory

import io.github.glandais.elevation.EarthConstants
import io.github.glandais.engine.path.Path
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Projects a [Path] into a single anchored [PlanarFrame] and conditions its geometry.
 *
 * See [PlanarFrame] for the projection and its conventions.
 */
internal object LocalFrame {
    /** Above this latitude `cos(lat0)` degenerates and the equirectangular map is unusable. */
    private const val MAX_ABS_LATITUDE_RAD = 85.0 * PI / 180.0

    /** Fewer points than this and no window-based estimate is meaningful. */
    const val MIN_POINTS = 8

    /**
     * Project [path] and smooth the resulting coordinates with a triangular kernel of half-width
     * [smoothWindowM] metres.
     *
     * Returns `null` when the path cannot be projected — too short, non-finite coordinates, or
     * too close to a pole. Callers treat `null` as "leave the curvature field NaN", which makes
     * every downstream consumer fall back to its own estimate.
     *
     * ## On smoothing and shrinkage
     *
     * Convolving a circular arc with a symmetric kernel contracts it: a triangular kernel of
     * half-width `W` has `E[t²] = W²/6`, biasing the radius by `ΔR ≈ W²/(12R)` — 14 cm at
     * `W = 5`, `R = 15`.
     *
     * No compensation is applied, for two reasons. Symmetric smoothing preserves the *total turn*
     * over an arc, so the heading-versus-arclength slope is largely insensitive to `W`; and what
     * bias remains reports a bend as slightly **tighter** than it is, which errs toward a lower
     * speed limit. Compensating would trade a safe bias for an unsafe one.
     *
     * That argument only holds when heading and arclength are measured on the *same* curve, which
     * is why [project] re-derives `s` from the smoothed coordinates rather than reusing
     * `path.distance` — see the comment at the call site.
     *
     * The window is deliberately small. A 20–25 m kernel — the usual advice for noisy traces —
     * would distort a hairpin badly, since it is then a large fraction of the bend's own scale;
     * noise is handled by the estimator's scale selection instead, which is the layer that can
     * afford to be adaptive.
     */
    fun project(
        path: Path,
        smoothWindowM: Double,
    ): PlanarFrame? {
        val n = path.size
        if (n < MIN_POINTS) return null

        val bounds = path.boundsRad
        val lat0 = (bounds.minLat + bounds.maxLat) / 2.0
        var lon0 = (bounds.minLon + bounds.maxLon) / 2.0
        if (!lat0.isFinite() || !lon0.isFinite()) return null
        if (abs(lat0) > MAX_ABS_LATITUDE_RAD) return null

        // A path straddling the antimeridian has a bounds centre on the wrong side of the globe;
        // recentre on the first point, then unwrap every longitude about it.
        if (bounds.maxLon - bounds.minLon > PI) lon0 = path.longitude(0)

        val k = cos(lat0)
        val rawX = DoubleArray(n)
        val rawY = DoubleArray(n)
        val s = DoubleArray(n)
        for (i in 0 until n) {
            val lat = path.latitude(i)
            val lon = path.longitude(i)
            val dist = path.distance(i)
            if (!lat.isFinite() || !lon.isFinite() || !dist.isFinite()) return null
            val dLon = unwrap(lon - lon0)
            rawX[i] = EarthConstants.MEAN_RADIUS * k * dLon
            rawY[i] = EarthConstants.MEAN_RADIUS * (lat - lat0)
            s[i] = dist
        }

        val x = smooth(rawX, s, smoothWindowM)
        val y = smooth(rawY, s, smoothWindowM)
        // Re-derive arclength from the *smoothed* coordinates.
        //
        // This matters more than it looks. Heading is measured on the smoothed curve, so
        // regressing it against the raw path's arclength mixes two different curves: smoothing
        // contracts a bend, shortening it, and dividing the smoothed curve's turn by the raw
        // curve's length understates curvature — by 13 % on a 7 m hairpin, in the *unsafe*
        // direction. Measured on its own arclength the result is the smoothed curve's true
        // curvature, whose only residual bias is the classical contraction `W²/(12R)`, which
        // reports a slightly *tighter* bend than reality and so errs safe.
        arclengthOf(x, y, s)
        return PlanarFrame(
            x = x,
            y = y,
            s = s,
            theta = DoubleArray(n),
            kappa = DoubleArray(n),
            lat0 = lat0,
            lon0 = lon0,
            k = k,
        )
    }

    /**
     * Inverse of the projection: planar metres back to `[latitude, longitude]` in radians.
     *
     * Exact, because the forward map is a fixed affine transform — `k = cos(lat0)` is a constant,
     * not a per-point cosine. That is the whole reason the frame is anchored once instead of being
     * re-derived per corner.
     */
    fun unproject(
        frame: PlanarFrame,
        x: Double,
        y: Double,
    ): DoubleArray {
        val lat = frame.lat0 + y / EarthConstants.MEAN_RADIUS
        val lon = frame.lon0 + x / (EarthConstants.MEAN_RADIUS * frame.k)
        return doubleArrayOf(lat, lon)
    }

    /**
     * Overwrite [s] with the cumulative planar arclength of ([x], [y]).
     *
     * Strictly non-decreasing by construction, so every window search downstream can rely on
     * monotonicity. `sqrt(a*a + b*b)` rather than `hypot`: the latter is not bit-identical across
     * the JVM and JS, and this feeds a threshold comparison.
     */
    internal fun arclengthOf(
        x: DoubleArray,
        y: DoubleArray,
        s: DoubleArray,
    ) {
        s[0] = 0.0
        for (i in 1 until x.size) {
            val dx = x[i] - x[i - 1]
            val dy = y[i] - y[i - 1]
            s[i] = s[i - 1] + sqrt(dx * dx + dy * dy)
        }
    }

    /** Wrap an angle difference into `(-π, π]` without a loop — exact, and branch-free. */
    fun unwrap(a: Double): Double = a - 2.0 * PI * round(a / (2.0 * PI))

    /**
     * Distance-weighted triangular convolution of [v] against the arclength [s], two-pointer,
     * `O(n)`.
     *
     * This is `ElevationSmoother.smooth`'s algorithm. It is reimplemented rather than reused
     * because that object is hard-wired to `List<CoordinatesElevation>`, and this stage works on
     * bare `DoubleArray`s — building a list of coordinate objects per axis per call would allocate
     * two objects per point for nothing.
     */
    internal fun smooth(
        v: DoubleArray,
        s: DoubleArray,
        windowM: Double,
    ): DoubleArray {
        val n = v.size
        if (windowM <= 0.0 || n < 3) return v.copyOf()
        val out = DoubleArray(n)
        var lo = 0
        var hi = 0
        for (i in 0 until n) {
            val c = s[i]
            while (lo < i && c - s[lo] > windowM) lo++
            if (hi < i) hi = i
            while (hi < n - 1 && s[hi + 1] - c <= windowM) hi++
            var weightSum = 0.0
            var valueSum = 0.0
            for (j in lo..hi) {
                val w = 1.0 - abs(s[j] - c) / windowM
                if (w <= 0.0) continue
                weightSum += w
                valueSum += v[j] * w
            }
            out[i] = if (weightSum > 0.0) valueSum / weightSum else v[i]
        }
        return out
    }
}
