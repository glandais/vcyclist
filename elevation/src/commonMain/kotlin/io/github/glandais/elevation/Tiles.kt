package io.github.glandais.elevation

/** Integer tile coordinates in the Web Mercator pyramid. */
data class TileCoordinates(
    val x: Int,
    val y: Int,
    val z: Int,
)

/** Tile coordinates with sub-pixel resolution (used internally for projection math). */
data class TileCoordinatesFloat(
    val x: Int,
    val y: Int,
    val xFloat: Double,
    val yFloat: Double,
    val z: Int,
)

/** A pixel inside a specific tile, with integer pixel coordinates `(x, y)`. */
data class Pixel(
    val tile: TileCoordinates,
    val x: Int,
    val y: Int,
)

/** Single RGB triplet, components in `0..255`. */
data class RGBColor(
    val red: Int,
    val green: Int,
    val blue: Int,
)
