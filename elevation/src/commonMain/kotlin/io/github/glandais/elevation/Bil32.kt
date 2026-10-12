package io.github.glandais.elevation

import kotlin.math.roundToLong

/**
 * How a source's tile bytes encode elevation.
 *
 * - [TERRARIUM]: an image (WebP or PNG) whose RGB packs the elevation, the Mapterhorn format;
 *   decoded by the platform's image decoder, [decodeTileBytes].
 * - [BIL32]: raw little-endian IEEE-754 float32 metres, one per pixel, rows north to south, no
 *   header — what IGN's WMS answers to `FORMAT=image/x-bil;bits=32`. Decoded by [decodeBil32Tile],
 *   which is pure Kotlin and identical on every target.
 */
enum class TileEncoding { TERRARIUM, BIL32 }

/**
 * BIL samples at or below this are IGN's no-data marker (`-9999`: outside the surveyed area, the
 * sea). Loose rather than equal, so a value that lost precision on the way still reads as no-data
 * — the threshold `leaflet-relief` uses for the same service.
 */
internal const val BIL_NO_DATA_THRESHOLD: Double = -9000.0

/**
 * Decode a BIL float32 tile into a [RawTile], re-encoding each sample as **Terrarium RGB**.
 *
 * Re-encoding rather than widening [RawTile] is the point: `RawTile` is the contract of every
 * custom fetcher — the CLI's disk cache, Java's `Function<String, RawTile>`, the WASI host — and
 * keeping it means a BIL source changes none of them. The cost is quantisation to 1/256 m, which
 * [Tile] then rounds to the centimetre anyway, against a source accurate to ~10 cm.
 *
 * A no-data sample (≤ [BIL_NO_DATA_THRESHOLD], or NaN) becomes RGB `(0, 0, 0)`, Terrarium's floor
 * of −32768 m, which [Tile] reads back as **no data**. No terrain is anywhere near it, so the code
 * cannot collide with a real elevation.
 *
 * Strict on the length: a body that is not exactly `tileSize² × 4` bytes is rejected. A WMS
 * reports its own errors as a 200 with an XML body, and accepting a short read would turn such a
 * page into a tile of plausible zeros.
 *
 * @throws IllegalStateException if [bytes] is not exactly one `tileSize × tileSize` grid
 */
fun decodeBil32Tile(
    bytes: ByteArray,
    tileSize: Int,
    sourceUrl: String = "",
): RawTile {
    val samples = tileSize * tileSize
    check(bytes.size == samples * 4) {
        "BIL32 tile ${sourceUrl.ifEmpty { "(no url)" }} is ${bytes.size} bytes, expected " +
            "${samples * 4} for $tileSize × $tileSize float32 samples" +
            if (bytes.isNotEmpty() && bytes[0] == '<'.code.toByte()) " — the body looks like an XML error page" else ""
    }
    val rgba = ByteArray(samples * 4)
    for (i in 0 until samples) {
        val o = i * 4
        val bits =
            (bytes[o].toInt() and 0xFF) or
                ((bytes[o + 1].toInt() and 0xFF) shl 8) or
                ((bytes[o + 2].toInt() and 0xFF) shl 16) or
                ((bytes[o + 3].toInt() and 0xFF) shl 24)
        val value = Float.fromBits(bits).toDouble()
        if (value.isNaN() || value <= BIL_NO_DATA_THRESHOLD) {
            rgba[o + 3] = 255.toByte()
            continue
        }
        val packed = encodeTerrarium(value)
        rgba[o] = (packed shr 16).toByte()
        rgba[o + 1] = (packed shr 8).toByte()
        rgba[o + 2] = packed.toByte()
        rgba[o + 3] = 255.toByte()
    }
    return RawTile(tileSize, tileSize, rgba)
}

/**
 * Metres → the 24-bit Terrarium code `(v + 32768) × 256`, clamped so that the floor code `0` stays
 * reserved for no-data and the top one cannot overflow.
 */
internal fun encodeTerrarium(metres: Double): Int = ((metres + 32768.0) * 256.0).roundToLong().coerceIn(1L, 0xFFFFFFL).toInt()
