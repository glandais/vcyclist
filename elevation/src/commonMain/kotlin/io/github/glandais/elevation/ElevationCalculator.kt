package io.github.glandais.elevation

import kotlin.math.floor

/**
 * Computes elevations from cached [Tile]s served by [tileManager].
 *
 * Two modes:
 * - [interpolation] = false → nearest-pixel lookup (fastest).
 * - [interpolation] = true  → bilinear interpolation from the 4 neighbour pixels using the true
 *   sub-pixel position from [ElevationFunctions.toPixelFloat]. The original TS port computed
 *   `dx`/`dy` from already-floored Int pixel coordinates, making the interpolation degenerate
 *   into nearest-neighbour ; this Kotlin port keeps the fractional position.
 */
class ElevationCalculator(
    private val tileManager: TileManager,
    private val tileSize: Int = 256,
) {
    suspend fun getElevation(
        coords: Coordinates,
        zoomLevel: Int,
        interpolation: Boolean = true,
    ): Double =
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
