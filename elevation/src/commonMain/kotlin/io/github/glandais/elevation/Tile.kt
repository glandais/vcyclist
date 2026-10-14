package io.github.glandais.elevation

import kotlin.math.round

/**
 * In-memory tile: an RGBA pixel buffer, decoded per pixel on read.
 *
 * **Immutable once built**, so any number of coroutines may read one at once — the HTTP door shares
 * a provider between concurrent requests. Decoding is three bytes and an addition: a memo of the
 * decoded pixels (which this class once kept) cost twice the tile's own memory, 512 KB a
 * 256 × 256 tile, and was the one unguarded write on the read path.
 *
 * RGB `(0, 0, 0)` — Terrarium's floor, −32768 m — reads as **no data**, `NaN`: it is the code
 * [decodeBil32Tile] writes where IGN has nothing, and no real terrain is within 20 km of it.
 * [ElevationCalculator] turns `NaN` into a fallback lookup or 0 m; it never reaches a caller, except
 * through a raw-layer provider built by [IgnLidarHdLayer.provider], which keeps it.
 *
 * Concrete and platform-agnostic: it reads bytes directly from [rgba] rather than delegating
 * pixel access to a per-platform hook, so the decoding logic exists once.
 */
class Tile(
    val width: Int,
    val height: Int,
    private val rgba: ByteArray,
) {
    constructor(raw: RawTile) : this(raw.width, raw.height, raw.rgba)

    /** This tile stands for one that could not be fetched (see [UnreadableTiles]): all no data. */
    internal var unreadable: Boolean = false

    fun getElevation(pixel: Pixel): Double {
        require(pixel.x in 0 until width) {
            "Invalid x position: ${pixel.x}. Must be between 0 and ${width - 1}"
        }
        require(pixel.y in 0 until height) {
            "Invalid y position: ${pixel.y}. Must be between 0 and ${height - 1}"
        }

        val byteOffset = (pixel.y * width + pixel.x) * 4
        val r = rgba[byteOffset].toInt() and 0xFF
        val g = rgba[byteOffset + 1].toInt() and 0xFF
        val b = rgba[byteOffset + 2].toInt() and 0xFF
        return if (r == 0 && g == 0 && b == 0) Double.NaN else decodeTerrariumElevation(r, g, b)
    }

    companion object {
        /**
         * Terrarium RGB → elevation decoder.
         * Formula: (r * 256 + g + b / 256) - 32768, rounded to 2 decimals.
         */
        fun decodeTerrariumElevation(
            r: Int,
            g: Int,
            b: Int,
        ): Double {
            val raw = r * 256.0 + g + b / 256.0 - 32768.0
            return round(raw * 100.0) / 100.0
        }
    }
}
