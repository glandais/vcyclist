package io.github.glandais.elevation

/**
 * A 4 × 1 **lossless** WebP (VP8L, with alpha) whose exact pixel values are known, embedded as a
 * `data:` URL so the browser decode path can be exercised **offline**.
 *
 * The four pixels bracket the byte range (0, low, mid, high) precisely because those are where a
 * colour-space conversion or a premultiplied-alpha round-trip shows up first. Terrarium encodes
 * elevation as `R*256 + G + B/256 - 32768`, so a ±1 drift on B is a 4 mm error and a ±1 drift on
 * R is a 256 m error — nothing about this may be approximate.
 *
 * Generated with Pillow (`Image.save(..., 'WEBP', lossless=True, exact=True)`) and verified to
 * round-trip through Pillow's own decoder.
 */
object InlineWebpFixture {
    const val DATA_URL: String =
        "data:image/webp;base64,UklGRjIAAABXRUJQVlA4TCUAAAAvAwAAAB8gICFkut2GCEgInlsusgGK4nVXm+Y/gFp/NShtRP8DAA=="

    const val WIDTH: Int = 4
    const val HEIGHT: Int = 1

    /**
     * The very same image as raw bytes — what [DATA_URL] carries. Lets [decodeTileBytes] be
     * exercised without any URL, and therefore on **every** target: the JVM's `HttpClient` has no
     * `data:` support, so [DATA_URL] is browser-and-Node-only.
     *
     * Written down independently rather than derived from [DATA_URL] at runtime: a fixture that
     * computes itself can be wrong in the same way twice.
     */
    val BYTES: ByteArray =
        hexToBytes(
            "5249464632000000574542505650384c250000002f030000001f20202164badd860848089e5b2eb2018ae275579be63f" +
                "805a7f35286d44ff0300",
        )

    /** Packed RGBA, row-major — exactly what [RawTile.rgba] must contain. */
    val EXPECTED_RGBA: ByteArray =
        byteArrayOf(
            0,
            0,
            0,
            255.toByte(),
            1,
            2,
            3,
            255.toByte(),
            127,
            128.toByte(),
            129.toByte(),
            255.toByte(),
            255.toByte(),
            254.toByte(),
            253.toByte(),
            255.toByte(),
        )

    fun describe(rgba: ByteArray): String = rgba.joinToString(",") { (it.toInt() and 0xFF).toString() }
}
