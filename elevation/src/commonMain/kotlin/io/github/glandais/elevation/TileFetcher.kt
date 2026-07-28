package io.github.glandais.elevation

/**
 * Download the raw bytes of the tile at [url], without decoding them.
 *
 * Split out of [fetchAndDecodeTile] (task g21) so a caller can own the transport — a disk or
 * object-store cache, an HTTP client with its own policy, tiles shipped alongside the
 * application — while still using this library's decoder. See [decodeTileBytes].
 *
 * @throws IllegalStateException if the HTTP status is not 2xx
 */
expect suspend fun fetchTileBytes(url: String): ByteArray

/**
 * Decode a tile image into unpacked RGBA bytes.
 *
 * Accepts whatever the target's image decoder accepts — in practice WebP and PNG, the two
 * formats Terrarium tiles are served in. [sourceUrl] is only used to make error messages
 * traceable ; it may be left empty for bytes that did not come from a URL.
 *
 * Suspending **by necessity, not for symmetry**: the browser path goes through
 * `createImageBitmap`, which is asynchronous. A blocking signature could not be implemented on
 * Kotlin/JS.
 *
 * Each target provides its own implementation:
 * - JVM: ImageIO (TwelveMonkeys for WebP), on [kotlinx.coroutines.Dispatchers.IO].
 * - JS (browser): `createImageBitmap` + canvas `getImageData`.
 * - JS (Node/Bun): the `@jsquash/webp` WASM decoder.
 *
 * @throws IllegalStateException if the bytes cannot be decoded
 */
expect suspend fun decodeTileBytes(
    bytes: ByteArray,
    sourceUrl: String = "",
): RawTile

/**
 * Download the tile at [url] and decode it into a [RawTile] (RGBA bytes).
 *
 * Equivalent to `decodeTileBytes(fetchTileBytes(url), url)` — an equivalence asserted by
 * `TileDecodeSplitTest` on every target. It stays a separate implementation rather than that
 * literal composition because each target has a shorter path when it owns both halves: the JVM
 * needs a single `Dispatchers.IO` hop instead of two, and the browsers can hand the `Response`
 * straight to `createImageBitmap` as a `Blob` without a `ByteArray` round-trip.
 *
 * This is the default fetcher of [TileManager] and [ElevationProvider]; pass your own to those
 * to insert a cache, typically as `decodeTileBytes(myCache.getOrFetch(url), url)`.
 *
 * @throws IllegalStateException if HTTP status is not 2xx or decoding fails
 */
expect suspend fun fetchAndDecodeTile(url: String): RawTile
