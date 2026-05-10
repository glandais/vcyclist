package io.github.glandais.elevation

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object EcefConverter {
    /**
     * Convert WGS84 [coordinates] to ECEF (Earth-Centered, Earth-Fixed) Cartesian coordinates.
     * Applies [zExaggeration] to the elevation component (default 3, used by Douglas-Peucker
     * to emphasize vertical deviations).
     */
    fun toEcef(
        coordinates: Coordinates,
        zExaggeration: Double = 3.0,
    ): Vector3D {
        val latRad = coordinates.latitude * MathConstants.DEG_TO_RAD
        val lonRad = coordinates.longitude * MathConstants.DEG_TO_RAD
        val elevationExaggerated = zExaggeration * (coordinates.elevation ?: 0.0)

        val sinLat = sin(latRad)
        val n =
            EarthConstants.SEMI_MAJOR_AXIS /
                sqrt(1.0 - EarthConstants.FIRST_ECCENTRICITY_SQUARED * sinLat * sinLat)

        val cosLat = cos(latRad)
        val cosLon = cos(lonRad)
        val sinLon = sin(lonRad)

        val x = (n + elevationExaggerated) * cosLat * cosLon
        val y = (n + elevationExaggerated) * cosLat * sinLon
        val z = (n * (1.0 - EarthConstants.FIRST_ECCENTRICITY_SQUARED) + elevationExaggerated) * sinLat

        return Vector3D(x, y, z)
    }

    /** Batch-convert a list of coordinates to ECEF vectors. Order is preserved. */
    fun convertBatch(
        coordinates: List<Coordinates>,
        zExaggeration: Double = 3.0,
    ): List<Vector3D> = coordinates.map { toEcef(it, zExaggeration) }
}
