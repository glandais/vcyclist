package io.github.glandais.elevation

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.sinh

/**
 * Where IGN LiDAR HD can have data: metropolitan France and Corsica, as two coarse polygons drawn
 * **generously** — 5 to 20 km outside the border and the coast, so that a slice of Switzerland,
 * Belgium or Kent is inside and no French ground is out.
 *
 * Only a filter in front of the network: a tile wholly outside is answered as a tile of no data
 * without a request, which is what the WMS would have returned (`-9999` everywhere) at the cost of
 * a 256 kB download per tile, terrain and surface model alike. A tile inside is fetched as before,
 * and its no-data points still fall back. Too tight would only cost precision at the edge (the
 * fallback reads those points); too wide only costs what every request cost before. Never a
 * statement about the data itself.
 */
internal object IgnLidarHdCoverage {
    /** Metropolitan France, `lon, lat` pairs, clockwise from the North Sea. */
    private val METROPOLE: DoubleArray =
        doubleArrayOf(
            2.40,
            51.40,
            3.00,
            51.15,
            3.40,
            50.85,
            4.30,
            50.55,
            4.70,
            50.35,
            5.00,
            50.25,
            5.20,
            49.95,
            5.85,
            49.62,
            6.40,
            49.56,
            7.40,
            49.30,
            8.35,
            49.10,
            8.00,
            48.40,
            7.75,
            47.75,
            7.70,
            47.60,
            6.95,
            47.20,
            6.55,
            46.75,
            7.00,
            46.45,
            7.15,
            46.00,
            7.05,
            45.60,
            7.35,
            45.30,
            7.15,
            44.90,
            7.15,
            44.60,
            7.85,
            44.20,
            7.70,
            43.65,
            7.70,
            43.30,
            6.50,
            42.90,
            4.50,
            43.10,
            3.40,
            42.90,
            3.40,
            42.35,
            2.30,
            42.25,
            1.30,
            42.35,
            0.40,
            42.55,
            -0.70,
            42.65,
            -1.40,
            42.90,
            -1.90,
            43.20,
            -2.10,
            43.50,
            -1.70,
            44.50,
            -2.60,
            46.60,
            -3.40,
            47.20,
            -5.30,
            47.80,
            -5.35,
            48.65,
            -4.20,
            48.95,
            -2.40,
            49.05,
            -2.10,
            49.80,
            -0.20,
            49.85,
            1.20,
            50.25,
            1.40,
            51.05,
        )

    /** Corsica, a box: the island spans 8.53–9.56° E, 41.37–43.03° N. */
    private val CORSICA: DoubleArray = doubleArrayOf(8.40, 43.15, 9.70, 43.15, 9.70, 41.25, 8.40, 41.25)

    private val POLYGONS = listOf(METROPOLE, CORSICA)

    /** Whether [latitude], [longitude] (degrees) is inside the coverage. */
    fun contains(
        latitude: Double,
        longitude: Double,
    ): Boolean = POLYGONS.any { inside(it, longitude, latitude) }

    /** Whether tile [tc] touches the coverage, its edges included. */
    fun intersects(tc: TileCoordinates): Boolean {
        val tiles = (1 shl tc.z).toDouble()
        val west = tc.x / tiles * 360.0 - 180.0
        val east = (tc.x + 1) / tiles * 360.0 - 180.0
        val north = latitudeOfRow(tc.y, tiles)
        val south = latitudeOfRow(tc.y + 1, tiles)
        return POLYGONS.any { polygon -> intersects(polygon, west, south, east, north) }
    }

    private fun latitudeOfRow(
        y: Int,
        tiles: Double,
    ): Double = atan(sinh(PI - 2.0 * PI * y / tiles)) * 180.0 / PI

    private fun intersects(
        polygon: DoubleArray,
        west: Double,
        south: Double,
        east: Double,
        north: Double,
    ): Boolean {
        // A tile corner inside the polygon, a polygon vertex inside the tile, or an edge crossing
        // one of the tile's: between them they cover every way a rectangle and a polygon can meet.
        val corners = arrayOf(west to south, west to north, east to south, east to north)
        if (corners.any { (x, y) -> inside(polygon, x, y) }) return true
        val n = polygon.size / 2
        for (i in 0 until n) {
            val x = polygon[2 * i]
            val y = polygon[2 * i + 1]
            if (x in west..east && y in south..north) return true
        }
        val sides =
            arrayOf(
                doubleArrayOf(west, south, east, south),
                doubleArrayOf(east, south, east, north),
                doubleArrayOf(east, north, west, north),
                doubleArrayOf(west, north, west, south),
            )
        for (i in 0 until n) {
            val j = (i + 1) % n
            val ax = polygon[2 * i]
            val ay = polygon[2 * i + 1]
            val bx = polygon[2 * j]
            val by = polygon[2 * j + 1]
            if (sides.any { s -> segmentsCross(ax, ay, bx, by, s[0], s[1], s[2], s[3]) }) return true
        }
        return false
    }

    /** Even-odd ray casting; [x] is the longitude, [y] the latitude. */
    private fun inside(
        polygon: DoubleArray,
        x: Double,
        y: Double,
    ): Boolean {
        val n = polygon.size / 2
        var result = false
        var j = n - 1
        for (i in 0 until n) {
            val xi = polygon[2 * i]
            val yi = polygon[2 * i + 1]
            val xj = polygon[2 * j]
            val yj = polygon[2 * j + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) result = !result
            j = i
        }
        return result
    }

    private fun segmentsCross(
        ax: Double,
        ay: Double,
        bx: Double,
        by: Double,
        cx: Double,
        cy: Double,
        dx: Double,
        dy: Double,
    ): Boolean {
        fun orient(
            px: Double,
            py: Double,
            qx: Double,
            qy: Double,
            rx: Double,
            ry: Double,
        ): Double = (qx - px) * (ry - py) - (qy - py) * (rx - px)
        val d1 = orient(cx, cy, dx, dy, ax, ay)
        val d2 = orient(cx, cy, dx, dy, bx, by)
        val d3 = orient(ax, ay, bx, by, cx, cy)
        val d4 = orient(ax, ay, bx, by, dx, dy)
        return (d1 > 0) != (d2 > 0) && (d3 > 0) != (d4 > 0)
    }
}

/**
 * [fetcher] behind [IgnLidarHdCoverage]: a tile of [config] wholly outside it is a tile of no data,
 * answered without calling [fetcher] — no request, no disk read, no `TileStore` lookup.
 */
internal fun ignCoverageFiltered(
    config: ElevationProviderConfig,
    fetcher: suspend (String) -> RawTile,
): suspend (String) -> RawTile =
    { url ->
        if (IgnLidarHdCoverage.intersects(tileCoordinatesOf(url, config))) fetcher(url) else noDataTile(config.tileSize)
    }

/** A tile whose every pixel is RGB `(0, 0, 0)`, which [Tile] reads as no data — as [decodeBil32Tile] writes it. */
internal fun noDataTile(tileSize: Int): RawTile {
    val rgba = ByteArray(tileSize * tileSize * 4)
    for (i in 3 until rgba.size step 4) rgba[i] = 255.toByte()
    return RawTile(tileSize, tileSize, rgba)
}
