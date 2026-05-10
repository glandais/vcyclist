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
        return List(points.size) { i ->
            val smoothed = computeSmoothedValue(i, points, distances, windowSize)
            LatLonElevation(points[i].latitude, points[i].longitude, smoothed)
        }
    }

    private fun computeSmoothedValue(
        index: Int,
        points: List<CoordinatesElevation>,
        distances: DoubleArray,
        windowSize: Double,
    ): Double {
        val current = distances[index]

        var startIndex = index
        while (startIndex > 0 && current - distances[startIndex - 1] <= windowSize) {
            startIndex--
        }

        var endIndex = index
        while (endIndex < points.size - 1 && distances[endIndex + 1] - current <= windowSize) {
            endIndex++
        }

        var totalWeight = 0.0
        var weightedSum = 0.0
        for (j in startIndex..endIndex) {
            val d = (distances[j] - current).absoluteValue
            val weight = 1.0 - d / windowSize
            totalWeight += weight
            weightedSum += points[j].elevation * weight
        }

        return if (totalWeight > 0.0) weightedSum / totalWeight else points[index].elevation
    }

    private fun formatWindow(w: Double): String {
        val asLong = w.toLong()
        return if (asLong.toDouble() == w) asLong.toString() else w.toString()
    }
}
