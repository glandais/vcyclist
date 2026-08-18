package io.github.glandais.elevation

/** Distance-based elevation smoothing, off by default. */
data class SmoothingOptions(
    val windowSize: Double? = 50.0,
    val enabled: Boolean = false,
)

/** Douglas-Peucker 3D simplification settings. */
data class FilterOptions(
    val tolerance: Double? = 10.0,
    val zExaggeration: Double? = 3.0,
    val enabled: Boolean = false,
)

class BatchCalculator(
    private val calculator: ElevationCalculator,
) {
    /**
     * Compute elevations for [coordinates] in parallel, grouped by tile so the cache is hit instead
     * of refetched. Returns a new list preserving input order.
     */
    suspend fun setElevations(
        coordinates: List<Coordinates>,
        zoomLevel: Int,
        interpolation: Boolean,
        maxParallelTiles: Int = 10,
    ): List<CoordinatesElevation> {
        if (coordinates.isEmpty()) return emptyList()
        val results = arrayOfNulls<CoordinatesElevation>(coordinates.size)

        val byTile: MutableMap<String, MutableList<Int>> = LinkedHashMap()
        for ((i, p) in coordinates.withIndex()) {
            val tile = ElevationFunctions.toTileCoordinates(p, zoomLevel)
            val key = "${tile.z}/${tile.x}/${tile.y}"
            byTile.getOrPut(key) { mutableListOf() }.add(i)
        }

        Flux.forEachParallel(byTile.entries, maxParallelTiles) { (_, indices) ->
            for (i in indices) {
                val coord = coordinates[i]
                val ele = calculator.getElevation(coord, zoomLevel, interpolation)
                results[i] = LatLonElevation(coord.latitude, coord.longitude, ele)
            }
        }

        @Suppress("UNCHECKED_CAST")
        return (results.toList() as List<CoordinatesElevation>)
    }

    /**
     * Compute elevations along a path defined by [path] waypoints.
     *
     * Densifies the path with intermediate points every [step] meters (linear lat/lon interpolation),
     * skips segments shorter than [minDistance], then runs [setElevations]. Optionally applies
     * distance-based smoothing then Douglas-Peucker simplification.
     */
    suspend fun getElevationsAlong(
        path: List<Coordinates>,
        zoomLevel: Int,
        step: Double = 10.0,
        minDistance: Double = 1.0,
        interpolation: Boolean = true,
        smoothingOptions: SmoothingOptions? = null,
        filterOptions: FilterOptions? = null,
    ): List<CoordinatesElevation> {
        require(path.size >= 2) { "Path must contain at least 2 coordinates" }
        require(step > 1.0) { "Step is too small: ${formatNumber(step)} meters" }

        val densified = generateCoordinatesAlong(path, step, minDistance)
        var withElevation = setElevations(densified, zoomLevel, interpolation)

        if (smoothingOptions?.enabled == true && withElevation.size >= 3) {
            withElevation = ElevationSmoother.smooth(withElevation, smoothingOptions.windowSize ?: 50.0)
        }
        if (filterOptions?.enabled == true && withElevation.size > 2) {
            withElevation =
                DouglasPeucker.simplify(
                    withElevation,
                    filterOptions.tolerance ?: 10.0,
                    filterOptions.zExaggeration ?: 3.0,
                )
        }
        return withElevation
    }

    internal fun generateCoordinatesAlong(
        path: List<Coordinates>,
        step: Double,
        minDistance: Double,
    ): List<Coordinates> {
        if (path.isEmpty()) return emptyList()
        val out = ArrayList<Coordinates>(path.size * 2)
        out += path[0]

        for (i in 0 until path.size - 1) {
            val a = path[i]
            val b = path[i + 1]
            val d = Distance.haversine(a, b)
            if (d < minDistance) continue
            val between = generateCoordinatesBetween(a, b, step, d)
            for (j in 1 until between.size) out += between[j]
        }
        return out
    }

    private fun formatNumber(d: Double): String {
        val asLong = d.toLong()
        return if (asLong.toDouble() == d) asLong.toString() else d.toString()
    }

    internal fun generateCoordinatesBetween(
        a: Coordinates,
        b: Coordinates,
        step: Double,
        distance: Double,
    ): List<Coordinates> {
        if (distance <= step) return listOf(a, b)
        val numSteps = (distance / step).toInt()
        val out = ArrayList<Coordinates>(numSteps + 2)
        out += a
        val latDiff = b.latitude - a.latitude
        val lonDiff = b.longitude - a.longitude
        for (i in 1..numSteps) {
            val f = (i * step) / distance
            out += LatLon(a.latitude + latDiff * f, a.longitude + lonDiff * f)
        }
        out += b
        return out
    }
}
