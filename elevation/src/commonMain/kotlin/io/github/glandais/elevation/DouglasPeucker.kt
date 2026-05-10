package io.github.glandais.elevation

object DouglasPeucker {
    /**
     * Simplify a 3D elevation profile using the Douglas-Peucker algorithm.
     *
     * Converts every candidate point to ECEF (with [zExaggeration] applied) and removes any
     * intermediate point whose perpendicular distance to the current segment is below [tolerance]
     * (meters). The first and last points are always preserved.
     */
    fun simplify(
        points: List<CoordinatesElevation>,
        tolerance: Double,
        zExaggeration: Double = 3.0,
    ): List<CoordinatesElevation> {
        if (points.size <= 2) return points.toList()

        val lastIndex = points.lastIndex
        return buildList(points.size) {
            add(points[0])
            simplifyRecursive(points, 0, lastIndex, tolerance, zExaggeration, this)
            add(points[lastIndex])
        }
    }

    private fun simplifyRecursive(
        points: List<CoordinatesElevation>,
        firstIndex: Int,
        lastIndex: Int,
        tolerance: Double,
        zExaggeration: Double,
        out: MutableList<CoordinatesElevation>,
    ) {
        val firstEcef = EcefConverter.toEcef(points[firstIndex], zExaggeration)
        val lastEcef = EcefConverter.toEcef(points[lastIndex], zExaggeration)

        var maxDistance = 0.0
        var maxIndex = -1

        for (i in (firstIndex + 1) until lastIndex) {
            val d =
                EcefConverter
                    .toEcef(points[i], zExaggeration)
                    .distanceToSegment(firstEcef, lastEcef)
            if (d > maxDistance) {
                maxDistance = d
                maxIndex = i
            }
        }

        if (maxDistance > tolerance && maxIndex != -1) {
            if (maxIndex - firstIndex > 1) {
                simplifyRecursive(points, firstIndex, maxIndex, tolerance, zExaggeration, out)
            }
            out.add(points[maxIndex])
            if (lastIndex - maxIndex > 1) {
                simplifyRecursive(points, maxIndex, lastIndex, tolerance, zExaggeration, out)
            }
        }
    }
}
