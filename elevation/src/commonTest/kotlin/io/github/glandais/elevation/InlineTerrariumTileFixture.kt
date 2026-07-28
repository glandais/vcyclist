package io.github.glandais.elevation

/**
 * A 4 × 4 **lossless** WebP encoding a synthetic Terrarium tile: the pixel at `(px, py)` carries
 * the elevation `px * 100 + py`, i.e. exactly what the `syntheticFetcher` of `ElevationProviderTest`
 * produces in memory.
 *
 * That correspondence is the whole point: it lets a test drive an [ElevationProvider] through the
 * **bytes** path ([decodeTileBytes]) and assert, elevation for elevation, that it agrees with the
 * same provider driven through the in-memory [RawTile] path. Anything the decoder got wrong —
 * premultiplied alpha, a colour-space conversion, a channel swap — moves the elevation by at
 * least 4 mm and at most 256 m, and the comparison fails.
 *
 * 4 × 4 because `ElevationProviderConfig.tileSize` must be a power of two and the existing
 * end-to-end tests already use 4.
 *
 * Generated with Pillow (`Image.save(..., 'WEBP', lossless=True, exact=True)`) and verified to
 * round-trip through Pillow's own decoder.
 */
object InlineTerrariumTileFixture {
    const val SIZE: Int = 4

    /** Elevation encoded at pixel ([px], [py]) — the formula the fixture was generated with. */
    fun elevationAt(
        px: Int,
        py: Int,
    ): Int = px * 100 + py

    val BYTES: ByteArray =
        hexToBytes(
            "524946463e000000574542505650384c320000002f03c000007f4010081a3b9c01069a40d2c6357ff3c7fc4716400360" +
                "07e000004100c4a0050b1658b060c16c44ff2328a71b",
        )

    /** The same tile in memory, as the decoder must produce it. Terrarium: `R*256 + G + B/256 - 32768`. */
    fun expectedRawTile(): RawTile {
        val rgba = ByteArray(SIZE * SIZE * 4)
        for (py in 0 until SIZE) {
            for (px in 0 until SIZE) {
                val raw = elevationAt(px, py) + 32768
                val idx = (py * SIZE + px) * 4
                rgba[idx] = ((raw shr 8) and 0xFF).toByte()
                rgba[idx + 1] = (raw and 0xFF).toByte()
                rgba[idx + 2] = 0
                rgba[idx + 3] = 255.toByte()
            }
        }
        return RawTile(SIZE, SIZE, rgba)
    }
}
