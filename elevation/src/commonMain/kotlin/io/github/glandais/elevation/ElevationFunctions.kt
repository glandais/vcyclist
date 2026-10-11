package io.github.glandais.elevation

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

object ElevationFunctions {
    fun degToRad(degrees: Double): Double = degrees * MathConstants.DEG_TO_RAD

    fun isValidLatitude(lat: Double): Boolean =
        lat >= -EarthConstants.WEB_MERCATOR_MAX_LAT_TEST && lat <= EarthConstants.WEB_MERCATOR_MAX_LAT_TEST

    fun isValidLongitude(lon: Double): Boolean = lon in -180.0..180.0

    fun isValidZoomLevel(zoom: Int): Boolean = zoom in 0..15

    /**
     * Moves a pixel that overflows its tile by less than one tile into the neighbouring tile.
     *
     * At the edge of the world (the last tile column or row, or tile 0) there is no neighbour: the
     * tile index **and** the pixel clamp to the edge pixel, on both axes. Longitude is not wrapped
     * across the antimeridian, consistently with [toTileCoordinatesFloat], which clamps the tile
     * index too; the cost is at most one pixel of edge extension next to longitude 180°.
     */
    fun normalizePixel(
        pixel: Pixel,
        tileSize: Int,
    ): Pixel {
        var x = pixel.x
        var y = pixel.y
        var tileX = pixel.tile.x
        var tileY = pixel.tile.y
        val z = pixel.tile.z

        if (x < 0) {
            x += tileSize
            tileX -= 1
        }
        if (x >= tileSize) {
            x -= tileSize
            tileX += 1
        }
        if (y < 0) {
            y += tileSize
            tileY -= 1
        }
        if (y >= tileSize) {
            y -= tileSize
            tileY += 1
        }

        // Past the edge of the world the pixel clamps with its tile: shifting it into a tile that
        // does not exist and then clamping only the tile index leaves it on the *opposite* edge of
        // the last tile, a whole tile away, which the bilinear neighbour then blends in.
        val maxTile = (1 shl z) - 1
        if (tileX < 0) {
            tileX = 0
            x = 0
        } else if (tileX > maxTile) {
            tileX = maxTile
            x = tileSize - 1
        }
        if (tileY < 0) {
            tileY = 0
            y = 0
        } else if (tileY > maxTile) {
            tileY = maxTile
            y = tileSize - 1
        }

        return Pixel(TileCoordinates(tileX, tileY, z), x, y)
    }

    fun toTileCoordinatesFloat(
        coords: Coordinates,
        z: Int,
    ): TileCoordinatesFloat {
        require(isValidLatitude(coords.latitude)) {
            "Invalid latitude: ${formatNumber(coords.latitude)}. Must be between -85.0511 and 85.0511"
        }
        require(isValidLongitude(coords.longitude)) {
            "Invalid longitude: ${formatNumber(coords.longitude)}. Must be between -180 and 180"
        }
        require(isValidZoomLevel(z)) {
            "Invalid zoom level: $z. Must be between 0 and 15"
        }

        val lat = degToRad(coords.latitude)
        val n = (1 shl z).toDouble()
        val xFloat = ((coords.longitude + 180.0) / 360.0) * n
        val yFloat = ((1.0 - ln(tan(lat) + 1.0 / cos(lat)) / PI) / 2.0) * n

        val maxTile = (1 shl z) - 1
        val x = floor(xFloat).toInt().coerceIn(0, maxTile)
        val y = floor(yFloat).toInt().coerceIn(0, maxTile)

        return TileCoordinatesFloat(x = x, y = y, xFloat = xFloat, yFloat = yFloat, z = z)
    }

    fun toTileCoordinates(
        coords: Coordinates,
        z: Int,
    ): TileCoordinates {
        val tile = toTileCoordinatesFloat(coords, z)
        return TileCoordinates(tile.x, tile.y, tile.z)
    }

    fun toPixel(
        coords: Coordinates,
        z: Int,
        tileSize: Int,
    ): Pixel {
        val tile = toTileCoordinatesFloat(coords, z)
        val px = floor((tile.xFloat - tile.x) * tileSize).toInt().coerceIn(0, tileSize - 1)
        val py = floor((tile.yFloat - tile.y) * tileSize).toInt().coerceIn(0, tileSize - 1)
        return Pixel(TileCoordinates(tile.x, tile.y, z), px, py)
    }

    /** Like [toPixel] but keeps the fractional pixel position needed for bilinear interpolation. */
    fun toPixelFloat(
        coords: Coordinates,
        z: Int,
        tileSize: Int,
    ): PixelFloat {
        val tile = toTileCoordinatesFloat(coords, z)
        val px = (tile.xFloat - tile.x) * tileSize
        val py = (tile.yFloat - tile.y) * tileSize
        return PixelFloat(TileCoordinates(tile.x, tile.y, z), px, py)
    }

    /** Formats integer-valued doubles like JS (`86` not `86.0`), so an error message reads the
     * same on every target rather than gaining a `.0` on the JVM. */
    private fun formatNumber(d: Double): String {
        val asLong = d.toLong()
        return if (asLong.toDouble() == d) asLong.toString() else d.toString()
    }
}
