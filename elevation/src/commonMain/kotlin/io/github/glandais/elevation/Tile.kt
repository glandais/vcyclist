package io.github.glandais.elevation

import kotlin.math.round

/**
 * In-memory tile : RGBA pixel buffer with lazy per-pixel Terrarium decoding and a memoization cache.
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

    private val cache: DoubleArray = DoubleArray(width * height) { Double.NaN }

    internal var decodeCount: Int = 0
        private set

    fun getElevation(pixel: Pixel): Double {
        require(pixel.x in 0 until width) {
            "Invalid x position: ${pixel.x}. Must be between 0 and ${width - 1}"
        }
        require(pixel.y in 0 until height) {
            "Invalid y position: ${pixel.y}. Must be between 0 and ${height - 1}"
        }

        val idx = pixel.y * width + pixel.x
        val cached = cache[idx]
        if (!cached.isNaN()) return cached

        val byteOffset = idx * 4
        val r = rgba[byteOffset].toInt() and 0xFF
        val g = rgba[byteOffset + 1].toInt() and 0xFF
        val b = rgba[byteOffset + 2].toInt() and 0xFF
        val elevation = decodeTerrariumElevation(r, g, b)
        cache[idx] = elevation
        decodeCount++
        return elevation
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
