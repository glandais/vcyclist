package io.github.glandais.elevation

import kotlin.math.floor

/**
 * Computes elevations from cached [Tile]s served by [tileManager].
 *
 * Two modes:
 * - [interpolation] = false → nearest-pixel lookup (fastest).
 * - [interpolation] = true  → bilinear interpolation from the 4 neighbour pixels using the true
 *   sub-pixel position from [ElevationFunctions.toPixelFloat]. Note the *float* pixel position
 *   is load-bearing: computing `dx`/`dy` from already-floored Int coordinates would make the
 *   interpolation degenerate silently into nearest-neighbour.
 *
 * ## Pixel convention: a value stands for the pixel's centre
 *
 * Pixel `i` covers `[i, i + 1)` in [ElevationFunctions.toPixelFloat] units and its value is the
 * terrain at its **centre**, `i + ½`. Every source read here defines it that way: a WMS `GetMap`
 * `BBOX` names the outer edges of the image (IGN LiDAR HD), and Mapterhorn and the historical
 * Terrarium tiles are GDAL warps onto the tile's edges, whose `GeoTransform` puts the origin on the
 * top-left *corner* and computes each value at the cell centre. The bilinear read therefore
 * interpolates between centres, at `(pf.x − ½, pf.y − ½)`: at a pixel's exact centre it returns that
 * pixel alone — the same pixel the nearest-pixel mode returns, unless a zero-weight neighbour is
 * no-data (`NaN` survives a zero weight, see *No data*; left as is by `elevation-half-pixel`,
 * decision 2) — and a plane is reproduced without offset. Treating the integer `i` as the sample's location instead (the convention before
 * `elevation-half-pixel`) read the terrain half a pixel **south-east** of every point: 1.6 m per
 * axis at IGN z15, 6–7 m at Mapterhorn z12.
 *
 * In the western / northern half of a pixel the read reaches `x0 − 1` / `y0 − 1`, i.e. the tile to
 * the west or north at a tile edge; [ElevationFunctions.normalizePixel] crosses it, and clamps at
 * the edge of the world.
 *
 * ## No data
 *
 * A sample the tile marks as no-data (see [Tile]) makes the lookup `NaN` — bilinearly too, since
 * `NaN` survives any weight. The point is then asked of [fallback] **as a whole**, at the same
 * coordinates, rather than mixing a fallback sample into the other three: two models disagree by
 * metres, and blending them would put a step in the profile at every coastline. With no fallback
 * it reads 0 m — the sea-level convention the WASI host's "no tile here" already follows — so `NaN`
 * never reaches a caller, where the smoother would spread it over the whole ride.
 *
 * The exception is a raw LiDAR layer ([IgnLidarHdLayer.provider]), whose [fallback] answers `NaN`
 * on purpose: there no-data must stay no-data, and its callers never smooth.
 */
class ElevationCalculator(
    private val tileManager: TileManager,
    private val tileSize: Int = 256,
    private val fallback: (suspend (Coordinates, Boolean) -> Double)? = null,
) {
    suspend fun getElevation(
        coords: Coordinates,
        zoomLevel: Int,
        interpolation: Boolean = true,
    ): Double {
        val own =
            try {
                if (interpolation) {
                    getInterpolatedElevation(coords, zoomLevel)
                } else {
                    val pixel = ElevationFunctions.toPixel(coords, zoomLevel, tileSize)
                    elevationFromPixel(pixel)
                }
            } catch (t: Throwable) {
                throw IllegalStateException("Failed to get elevation: ${t.message}", t)
            }
        if (!own.isNaN()) return own
        return fallback?.invoke(coords, interpolation) ?: 0.0
    }

    private suspend fun getInterpolatedElevation(
        coords: Coordinates,
        zoomLevel: Int,
    ): Double {
        val pf = ElevationFunctions.toPixelFloat(coords, zoomLevel, tileSize)
        // Sample i sits at i + ½ (see the class KDoc): interpolate between centres.
        val fx = pf.x - 0.5
        val fy = pf.y - 0.5
        val x0i = floor(fx).toInt()
        val y0i = floor(fy).toInt()
        val x1i = x0i + 1
        val y1i = y0i + 1
        val dx = fx - x0i
        val dy = fy - y0i

        val p00 = elevationFromPixel(ElevationFunctions.normalizePixel(Pixel(pf.tile, x0i, y0i), tileSize))
        val p10 = elevationFromPixel(ElevationFunctions.normalizePixel(Pixel(pf.tile, x1i, y0i), tileSize))
        val p01 = elevationFromPixel(ElevationFunctions.normalizePixel(Pixel(pf.tile, x0i, y1i), tileSize))
        val p11 = elevationFromPixel(ElevationFunctions.normalizePixel(Pixel(pf.tile, x1i, y1i), tileSize))

        val top = p00 * (1.0 - dx) + p10 * dx
        val bottom = p01 * (1.0 - dx) + p11 * dx
        return top * (1.0 - dy) + bottom * dy
    }

    private suspend fun elevationFromPixel(pixel: Pixel): Double = tileManager.getTile(pixel.tile).getElevation(pixel)
}
