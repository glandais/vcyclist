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
        val x0i = floor(pf.x).toInt()
        val y0i = floor(pf.y).toInt()
        val x1i = x0i + 1
        val y1i = y0i + 1
        val dx = pf.x - x0i
        val dy = pf.y - y0i

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
