package io.github.glandais.elevation

import io.github.glandais.elevation.webp.Vp8lDecoder

/**
 * WASI has no standardized HTTP client, so raw tile bytes cannot be fetched here. Task w05 moves
 * the whole transport to the host instead: see [HostTileSource], which returns tiles already
 * decoded through the `vcyclist.fetch_tile` import.
 */
actual suspend fun fetchTileBytes(url: String): ByteArray =
    throw UnsupportedOperationException(
        "fetchTileBytes is not available on wasmWasi — tiles come from the host through " +
            "HostTileSource / the vcyclist.fetch_tile import (task w05)",
    )

/**
 * Real since task w11: the pure-Kotlin VP8L decoder of `webp/`, so this target reads a WebP
 * without help from anyone.
 *
 * That is what lets a host serve **raw tile bytes** — one HTTP GET, no image library — instead of
 * decoded RGBA (see [HostTileSource] and the `tileFormat` option). The decoder is `commonMain`,
 * tested on all four targets, and checked byte for byte against TwelveMonkeys on a real
 * Mapterhorn tile in `Vp8lAgainstImageIoTest`.
 *
 * Only **lossless** WebP is supported; a lossy `VP8 ` or an extended `VP8X` file throws with its
 * fourcc named. Mapterhorn serves VP8L, verified on a live tile at the time of writing.
 */
actual suspend fun decodeTileBytes(
    bytes: ByteArray,
    sourceUrl: String,
): RawTile =
    try {
        // `decode` then repack, rather than `decode` plus `decodeToRgba`: the latter would decode
        // the whole megapixel a second time to learn what the first pass already knows.
        val image = Vp8lDecoder.decode(bytes)
        val rgba = ByteArray(image.argb.size * 4)
        for (i in image.argb.indices) {
            val argb = image.argb[i]
            rgba[i * 4] = ((argb shr 16) and 0xFF).toByte()
            rgba[i * 4 + 1] = ((argb shr 8) and 0xFF).toByte()
            rgba[i * 4 + 2] = (argb and 0xFF).toByte()
            rgba[i * 4 + 3] = ((argb shr 24) and 0xFF).toByte()
        }
        RawTile(image.width, image.height, rgba)
    } catch (t: Throwable) {
        throw IllegalStateException("failed to decode $sourceUrl as lossless WebP: ${t.message}", t)
    }

/**
 * Unavailable **on purpose**, even though [HostTileSource] could serve it.
 *
 * This is the default value of `TileManager`'s and `ElevationProvider`'s `fetcher` parameter, so
 * anything reachable from here is reachable from every construction of a provider — and wiring
 * the host import in at this point made the import survive DCE in `:elevation`'s *test* binary,
 * which the KGP runner cannot instantiate (`unknown import: vcyclist::fetch_tile`). Measured, not
 * feared: it took out all 194 wasmWasi tests of the module.
 *
 * So the host fetcher stays opt-in, one explicit `ElevationProvider(config, hostTileFetcher())`
 * away — which is also exactly the seam task g21 built, and what the WASI façade
 * passes when a caller asks for `fixElevation`.
 */
actual suspend fun fetchAndDecodeTile(url: String): RawTile =
    throw UnsupportedOperationException(
        "fetchAndDecodeTile has no default implementation on wasmWasi — pass " +
            "hostTileFetcher() to TileManager/ElevationProvider so tiles come from the " +
            "host through the vcyclist.fetch_tile import (task w05). Requested: $url",
    )
