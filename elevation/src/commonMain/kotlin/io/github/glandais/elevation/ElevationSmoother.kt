package io.github.glandais.elevation

import kotlin.math.absoluteValue

object ElevationSmoother {
    /**
     * Apply distance-based elevation smoothing using a triangular kernel.
     *
     * For each point, average its elevation with all points within [windowSize] meters
     * (along the cumulative path distance), weighted by `1 - d / windowSize`.
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

            var totalWeight = 0.0
            var weightedSum = 0.0
            for (j in startIndex..endIndex) {
                val weight = 1.0 - (distanceM[j] - current).absoluteValue / windowSize
                totalWeight += weight
                weightedSum += elevationM[j] * weight
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
