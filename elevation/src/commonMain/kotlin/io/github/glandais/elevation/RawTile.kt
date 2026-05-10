package io.github.glandais.elevation

/**
 * Raw pixel buffer produced by a tile fetcher (task 06).
 *
 * - [rgba] is a packed RGBA byte array, length `width * height * 4`.
 * - Byte ordering: R, G, B, A per pixel, rows top-to-bottom.
 */
class RawTile(
    val width: Int,
    val height: Int,
    val rgba: ByteArray,
) {
    init {
        require(rgba.size == width * height * 4) {
            "rgba size ${rgba.size} does not match width*height*4 (${width * height * 4})"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RawTile) return false
        return width == other.width && height == other.height && rgba.contentEquals(other.rgba)
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + rgba.contentHashCode()
        return result
    }
}
