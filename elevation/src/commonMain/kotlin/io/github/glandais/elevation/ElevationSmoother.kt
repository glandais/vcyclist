package io.github.glandais.elevation

import kotlin.math.absoluteValue

object ElevationSmoother {
    /**
     * Apply distance-based elevation smoothing using a triangular kernel.
     *
     * For each point, average the elevation profile over the [windowSize] meters on each side
     * (along the cumulative path distance), weighted by `1 - d / windowSize`. The average is taken
     * over **distance**, not over samples — see [smoothProfile] — so the result does not depend on
     * how densely the terrain is sampled.
     *
     * Returns the input unchanged if the path has fewer than [AlgorithmConstants.MIN_SMOOTHING_POINTS]
     * points. Throws if [windowSize] is not strictly positive.
     */
    fun smooth(
        points: List<CoordinatesElevation>,
        windowSize: Double = 50.0,
    ): List<CoordinatesElevation> {
        if (points.size < AlgorithmConstants.MIN_SMOOTHING_POINTS) return points
        require(windowSize > 0.0) { "Invalid window size: ${formatWindow(windowSize)}. Must be positive" }

        val distances = Distance.cumulativeDistances(points)
        val elevations = DoubleArray(points.size) { points[it].elevation }
        val smoothed = smoothProfile(distances, elevations, windowSize)
        return List(points.size) { i ->
            LatLonElevation(points[i].latitude, points[i].longitude, smoothed[i])
        }
    }

    /**
     * The kernel of [smooth], on flat arrays.
     *
     * [distanceM] must be non-decreasing and the same length as [elevationM]. The window is a
     * **half-width** applied on each side, so a `windowSize` of 150 spans 300 m of path — the
     * extreme members carry a weight of ~0.
     *
     * The profile is read as the piecewise-linear interpolant of its samples, and each output is
     * the exact integral of that interpolant against the triangular kernel, divided by the
     * kernel's integral over the part of the window that lies on the path (so the ends use a
     * one-sided, renormalised window). Weighting by path length rather than per sample is what
     * makes the window truly metric: inserting points on the profile's own straight segments
     * leaves every original sample's output unchanged, and segments longer than the window are
     * still smoothed instead of being passed through untouched.
     *
     * Exists as its own entry point because callers that already hold a profile as arrays (the
     * cumulative-ascent accumulator, the engine's pipeline) would otherwise have to allocate a
     * list of [LatLonElevation] per point, which is a real cost on Kotlin/JS at 10^5 points.
     *
     * Returns a copy of [elevationM] if there are fewer than [AlgorithmConstants.MIN_SMOOTHING_POINTS]
     * points, or if [windowSize] is not strictly positive — unlike [smooth], which throws. A
     * non-positive window means "do not smooth", which is a legal request here (the `RAW` gain
     * preset makes it).
     */
    fun smoothProfile(
        distanceM: DoubleArray,
        elevationM: DoubleArray,
        windowSize: Double,
    ): DoubleArray {
        require(distanceM.size == elevationM.size) {
            "distanceM (${distanceM.size}) and elevationM (${elevationM.size}) must have the same length"
        }
        if (elevationM.size < AlgorithmConstants.MIN_SMOOTHING_POINTS || windowSize <= 0.0) {
            return elevationM.copyOf()
        }

        val n = elevationM.size
        val out = DoubleArray(n)
        // The bounds are monotone in `i`, so the two cursors sweep the profile once between them
        // rather than being re-searched per point: O(n * pointsInWindow), not O(n^2).
        var startIndex = 0
        var endIndex = 0
        for (i in 0 until n) {
            val current = distanceM[i]
            while (current - distanceM[startIndex] > windowSize) startIndex++
            if (endIndex < i) endIndex = i
            while (endIndex < n - 1 && distanceM[endIndex + 1] - current <= windowSize) endIndex++

            // Segments [j, j + 1] that overlap the window: the one entering it from before
            // `startIndex`, every segment inside, and the one leaving it after `endIndex`.
            val lowerWindow = current - windowSize
            val upperWindow = current + windowSize
            var totalWeight = 0.0
            var weightedSum = 0.0
            for (j in maxOf(startIndex - 1, 0)..minOf(endIndex, n - 2)) {
                val d0 = distanceM[j]
                val d1 = distanceM[j + 1]
                val length = d1 - d0
                if (length <= 0.0) continue
                val a = maxOf(d0, lowerWindow)
                val b = minOf(d1, upperWindow)
                if (b <= a) continue
                val e0 = elevationM[j]
                val slope = (elevationM[j + 1] - e0) / length
                val m = 0.5 * (a + b)
                val ka = 1.0 - (a - current).absoluteValue / windowSize
                val km = 1.0 - (m - current).absoluteValue / windowSize
                val kb = 1.0 - (b - current).absoluteValue / windowSize
                val fa = e0 + slope * (a - d0)
                val fm = e0 + slope * (m - d0)
                val fb = e0 + slope * (b - d0)
                // `current` is a vertex, so no segment straddles the kernel's apex: on [a, b] both
                // the kernel and the interpolant are affine, their product is quadratic, and
                // Simpson's rule is exact. The kernel's own integral is exact by the trapezoid.
                totalWeight += 0.5 * (b - a) * (ka + kb)
                weightedSum += (b - a) / 6.0 * (fa * ka + 4.0 * fm * km + fb * kb)
            }
            out[i] = if (totalWeight > 0.0) weightedSum / totalWeight else elevationM[i]
        }
        return out
    }

    private fun formatWindow(w: Double): String {
        val asLong = w.toLong()
        return if (asLong.toDouble() == w) asLong.toString() else w.toString()
    }
}
