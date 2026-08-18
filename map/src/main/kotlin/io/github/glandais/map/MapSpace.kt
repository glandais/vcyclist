package io.github.glandais.map

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

/**
 * Web Mercator projection: latitude/longitude ↔ pixel coordinates, for a world that is
 * `tileSize * 2^zoom` pixels square. The convention used by OpenStreetMap and Google — the
 * origin `(0, 0)` is the top-left corner, at latitude ≈ +85.05 and longitude −180.
 *
 * The projection is the classic power-of-two tile pyramid popularised by MOBAC.
 *
 * ## Relationship with `:elevation`
 *
 * `ElevationFunctions.toTileCoordinatesFloat` implements **the same projection**. That was
 * checked algebraically rather than assumed: this class computes
 * `0.5 − ln((1+sin φ)/(1−sin φ)) / 4π` while `:elevation` computes
 * `(1 − ln(tan φ + sec φ)/π) / 2`, and since `ln((1+sin φ)/(1−sin φ)) = 2·ln(tan φ + sec φ)`
 * the two are identical. [MapSpaceCrossCheckTest] pins that agreement numerically so the two
 * implementations cannot silently drift.
 *
 * It is nevertheless a separate implementation rather than a call into `:elevation`, for three
 * reasons that are conventions, not maths:
 *
 * 1. **Units.** `:elevation` returns *tile* coordinates; map rendering wants *pixels*. They
 *    differ by a factor of [tileSize].
 * 2. **Zoom range.** `:elevation` rejects zoom above 15 — its DEM source has no deeper tiles.
 *    Map rendering routinely goes to 16-18, and this class supports up to 22.
 * 3. **Out-of-range behaviour.** `:elevation` *throws* outside ±85.0511°, which is right for a
 *    lookup. A renderer must not: it clamps, so a stray point near the pole yields an edge
 *    pixel instead of failing the whole image.
 *
 * @param tileSize edge length of a tile in pixels. 256 for every common raster source.
 */
class MapSpace(
    val tileSize: Int = DEFAULT_TILE_SIZE,
) {
    init {
        require(tileSize > 0) { "tileSize must be positive, got $tileSize" }
    }

    /** World size in pixels at [zoom]: `tileSize * 2^zoom`. */
    fun maxPixels(zoom: Int): Int {
        requireValidZoom(zoom)
        return tileSize shl zoom
    }

    /**
     * Longitude → pixel X.
     *
     * Clamped to `maxPixels - 1` at the eastern edge, so a point at
     * exactly +180° lands inside the image rather than one pixel past it.
     */
    fun lonToX(
        lon: Double,
        zoom: Int,
    ): Double {
        val mp = maxPixels(zoom)
        return minOf(mp * (lon + 180.0) / 360.0, mp - 1.0)
    }

    /** Latitude → pixel Y. Latitude is clamped to the Mercator limits first. */
    fun latToY(
        lat: Double,
        zoom: Int,
    ): Double {
        val mp = maxPixels(zoom)
        val clamped = lat.coerceIn(MIN_LAT, MAX_LAT)
        val sinLat = sin(Math.toRadians(clamped))
        val log = ln((1.0 + sinLat) / (1.0 - sinLat))
        return minOf(mp * (0.5 - log / (4.0 * PI)), mp - 1.0)
    }

    /** Pixel X → longitude. */
    fun xToLon(
        x: Double,
        zoom: Int,
    ): Double = 360.0 * x / maxPixels(zoom) - 180.0

    /** Pixel Y → latitude. */
    fun yToLat(
        y: Double,
        zoom: Int,
    ): Double {
        val shifted = y + falseNorthing(zoom)
        val latitude = PI / 2 - 2 * atan(exp(-shifted / radius(zoom)))
        return -Math.toDegrees(latitude)
    }

    /** Fractional tile index along X for [lon]. */
    fun lonToTileX(
        lon: Double,
        zoom: Int,
    ): Double = lonToX(lon, zoom) / tileSize

    /** Fractional tile index along Y for [lat]. */
    fun latToTileY(
        lat: Double,
        zoom: Int,
    ): Double = latToY(lat, zoom) / tileSize

    /**
     * Geographic bounds of the tile at `(tileX, tileY)`, as
     * `[minLon, minLat, maxLon, maxLat]`.
     */
    fun tileBounds(
        zoom: Int,
        tileX: Int,
        tileY: Int,
    ): DoubleArray {
        val px = tileX.toDouble() * tileSize
        val py = tileY.toDouble() * tileSize
        return doubleArrayOf(
            xToLon(px, zoom),
            yToLat(py + tileSize, zoom),
            xToLon(px + tileSize, zoom),
            yToLat(py, zoom),
        )
    }

    private fun radius(zoom: Int): Double = maxPixels(zoom) / (2.0 * PI)

    private fun falseNorthing(zoom: Int): Double = -maxPixels(zoom) / 2.0

    private fun requireValidZoom(zoom: Int) {
        require(zoom in 0..MAX_ZOOM) { "Zoom must be in 0..$MAX_ZOOM, got $zoom" }
    }

    companion object {
        const val DEFAULT_TILE_SIZE: Int = 256

        /**
         * Deepest zoom supported; also the point beyond which
         * `tileSize shl zoom` would overflow a signed Int for a 256 px tile.
         */
        const val MAX_ZOOM: Int = 22

        /** Northern Mercator limit — the latitude at which the projection becomes square. */
        const val MAX_LAT: Double = 85.05112877980659

        /** Southern Mercator limit. */
        const val MIN_LAT: Double = -85.05112877980659

        /** Shared 256 px instance — the tile size of every common raster source. */
        val TILE_256: MapSpace = MapSpace(DEFAULT_TILE_SIZE)
    }
}
