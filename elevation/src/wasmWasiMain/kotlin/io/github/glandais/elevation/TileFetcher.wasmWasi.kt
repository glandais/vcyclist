package io.github.glandais.elevation

/**
 * WASI has no standardized HTTP client and no image decoder, so the default fetcher cannot be
 * implemented on this target. Hosts must inject their own fetcher into [TileManager] /
 * [ElevationProvider] (the injection seam added by task g21), typically backed by a host-side
 * transport plus [decodeTileBytes] once a pure-Kotlin WebP decoder lands (see w01 notes).
 */
actual suspend fun fetchTileBytes(url: String): ByteArray =
    throw UnsupportedOperationException(
        "fetchTileBytes is not available on wasmWasi — inject a fetcher into TileManager/ElevationProvider",
    )

actual suspend fun decodeTileBytes(
    bytes: ByteArray,
    sourceUrl: String,
): RawTile =
    throw UnsupportedOperationException(
        "decodeTileBytes is not available on wasmWasi (no WebP decoder yet) — source: $sourceUrl",
    )

actual suspend fun fetchAndDecodeTile(url: String): RawTile =
    throw UnsupportedOperationException(
        "fetchAndDecodeTile is not available on wasmWasi — inject a fetcher into TileManager/ElevationProvider",
    )
