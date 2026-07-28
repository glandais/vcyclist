package io.github.glandais.elevation

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
 * Still unavailable, and deliberately so: the host sends **decoded** pixels, so nothing on this
 * target ever needs to read a WebP. A pure-Kotlin VP8L decoder (task w11) would make this real
 * and the host import optional.
 */
actual suspend fun decodeTileBytes(
    bytes: ByteArray,
    sourceUrl: String,
): RawTile =
    throw UnsupportedOperationException(
        "decodeTileBytes is not available on wasmWasi (no WebP decoder — task w11); the host " +
            "sends decoded RGBA instead, see HostTileSource. Source: $sourceUrl",
    )

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
