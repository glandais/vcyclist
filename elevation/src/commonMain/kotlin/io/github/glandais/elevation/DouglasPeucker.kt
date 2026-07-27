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
        val ecef = points.map { EcefConverter.toEcef(it, zExaggeration) }
        return simplifyIndices(ecef, tolerance).map { points[it] }
    }

    /**
     * Douglas-Peucker on arbitrary 3D points, returning the **indices** of the points to keep,
     * in increasing order, always including the first and the last.
     *
     * This is the geometric core [simplify] runs on, exposed separately because callers that are
     * not working with geographic coordinates need it too — the climb detector in `:engine`
     * simplifies an elevation profile expressed as `(distanceAlongPath, elevation, 0)`, which
     * has no meaningful latitude or longitude to project through [EcefConverter]. Keeping one
     * implementation avoids two Douglas-Peuckers drifting apart.
     *
     * A point is dropped when its perpendicular distance to the segment joining the current
     * endpoints is at most [tolerance], in whatever unit the coordinates are expressed in.
     */
    fun simplifyIndices(
        points: List<Vector3D>,
        tolerance: Double,
    ): List<Int> {
        if (points.size <= 2) return points.indices.toList()
        val lastIndex = points.lastIndex
        return buildList(points.size) {
            add(0)
            simplifyRecursive(points, 0, lastIndex, tolerance, this)
            add(lastIndex)
        }
    }

    private fun simplifyRecursive(
        points: List<Vector3D>,
        firstIndex: Int,
        lastIndex: Int,
        tolerance: Double,
        out: MutableList<Int>,
    ) {
        val first = points[firstIndex]
        val last = points[lastIndex]

        var maxDistance = 0.0
        var maxIndex = -1

        for (i in (firstIndex + 1) until lastIndex) {
            val d = points[i].distanceToSegment(first, last)
            if (d > maxDistance) {
                maxDistance = d
                maxIndex = i
            }
        }

        if (maxDistance > tolerance && maxIndex != -1) {
            if (maxIndex - firstIndex > 1) {
                simplifyRecursive(points, firstIndex, maxIndex, tolerance, out)
            }
            out.add(maxIndex)
            if (lastIndex - maxIndex > 1) {
                simplifyRecursive(points, maxIndex, lastIndex, tolerance, out)
            }
        }
    }
}
