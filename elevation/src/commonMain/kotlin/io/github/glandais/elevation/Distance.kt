package io.github.glandais.elevation

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object Distance {
    /**
     * Great-circle distance in meters using the Haversine formula.
     * Uses [EarthConstants.MEAN_RADIUS].
     */
    fun haversine(
        coord1: Coordinates,
        coord2: Coordinates,
    ): Double {
        val lat1Rad = coord1.latitude * MathConstants.DEG_TO_RAD
        val lat2Rad = coord2.latitude * MathConstants.DEG_TO_RAD
        val deltaLat = (coord2.latitude - coord1.latitude) * MathConstants.DEG_TO_RAD
        val deltaLon = (coord2.longitude - coord1.longitude) * MathConstants.DEG_TO_RAD

        val sinHalfLat = sin(deltaLat / 2.0)
        val sinHalfLon = sin(deltaLon / 2.0)
        val a = sinHalfLat * sinHalfLat + cos(lat1Rad) * cos(lat2Rad) * sinHalfLon * sinHalfLon
        val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
        return EarthConstants.MEAN_RADIUS * c
    }

    /** Euclidean distance between two 3D points in meters. */
    fun euclidean3D(
        point1: Vector3D,
        point2: Vector3D,
    ): Double {
        val dx = point1.x - point2.x
        val dy = point1.y - point2.y
        val dz = point1.z - point2.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * Perpendicular distance from [point] to the segment [[segmentStart], [segmentEnd]] in 3D.
     * Returns the distance to the endpoint if the projection falls outside the segment.
     */
    fun pointToSegment3D(
        point: Vector3D,
        segmentStart: Vector3D,
        segmentEnd: Vector3D,
    ): Double {
        val segmentVector = segmentEnd - segmentStart
        val segmentLengthSquared = segmentVector.dot(segmentVector)
        if (segmentLengthSquared == 0.0) return euclidean3D(point, segmentStart)

        val pointVector = point - segmentStart
        val t = (pointVector.dot(segmentVector) / segmentLengthSquared).coerceIn(0.0, 1.0)
        val closest = segmentStart + segmentVector * t
        return euclidean3D(point, closest)
    }

    /**
     * Cumulative haversine distances along the path. Returns `[0]` for an empty or single-point input
     * (matches TS reference for empty input).
     */
    fun cumulativeDistances(points: List<Coordinates>): DoubleArray {
        if (points.isEmpty()) return doubleArrayOf(0.0)
        val out = DoubleArray(points.size)
        for (i in 1 until points.size) {
            out[i] = out[i - 1] + haversine(points[i - 1], points[i])
        }
        return out
    }

    /** Total path length (sum of segment haversine distances). Returns 0 if fewer than 2 points. */
    fun totalPathDistance(points: List<Coordinates>): Double {
        if (points.size < 2) return 0.0
        var total = 0.0
        for (i in 1 until points.size) {
            total += haversine(points[i - 1], points[i])
        }
        return total
    }
}
