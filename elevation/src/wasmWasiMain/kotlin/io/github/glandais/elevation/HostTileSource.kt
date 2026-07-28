@file:OptIn(UnsafeWasmMemoryApi::class)

package io.github.glandais.elevation

import kotlin.wasm.WasmImport
import kotlin.wasm.unsafe.UnsafeWasmMemoryApi
import kotlin.wasm.unsafe.withScopedMemoryAllocator

/**
 * DEM tiles served by the **host**, through the `vcyclist.fetch_tile` import.
 *
 * WASI has neither an HTTP client nor an image decoder, and this target is not going to grow
 * one: `fixElevation` is the only step of the pipeline that needs the outside world, so the
 * outside world is where it goes. The injection seam is the one task g21 already added —
 * `TileManager(fetcher = …)` — which is why nothing in `commonMain` changes.
 *
 * ## What the host provides: decoded pixels, not a WebP
 *
 * The import hands back a tile **already decoded**, as RGBA bytes. Two reasons:
 *
 * - decoding WebP is exactly what neither WASI nor a small host wants to reimplement, while
 *   *every* host has an image library one line away (`Pillow`, Go's `image`, the `image` crate);
 * - it unblocks elevation without waiting for the pure-Kotlin VP8L decoder of task w11. That
 *   decoder stays worth doing — it would make this import optional — but it no longer gates
 *   anything.
 *
 * RGBA rather than RGB because [RawTile] requires `width × height × 4` and refuses anything
 * else: matching its invariant exactly means the guest never reinterprets what the host sent.
 * Terrarium only uses R, G and B; the alpha byte is ignored, so a host may set it to anything.
 *
 * ```
 * fetch_tile(zoom: i32, x: i32, y: i32, ptr: i32, cap: i32) -> i32
 * ```
 *
 * | Return | Meaning |
 * |---|---|
 * | `cap` | the tile was written at `ptr`, in full |
 * | `0` | **no tile here** — sea, outside coverage, the host decides. Treated as elevation 0 m |
 * | anything else | a host-side failure; the guest throws, and the enhance export answers -1 |
 *
 * The host must write during the call and not keep `ptr`: same discipline as `read_input`, and
 * the same prohibition on re-entering any export from inside the callback (see the ABI KDoc in
 * `EngineWasiApi`) — the scoped allocator forbids nested scopes and would throw.
 *
 * ## Tile geometry
 *
 * `cap` tells the host how many bytes are expected, so nothing has to be hard-coded on its side.
 * The size comes from [tileSize], which the façade sets from `ElevationProviderConfig.tileSize`
 * before building a provider; hosts can read the whole geometry through the ABI's
 * `vcTileGeometryJson`. A fixed constant was the alternative, and it was rejected because
 * `:elevation` already takes `tileSize` as configuration — freezing 512 here would be a second
 * source of truth that a non-Mapterhorn tile server would silently break.
 */
object HostTileSource {
    /**
     * `TileManager` addresses tiles by URL, and this target has no URLs. The template exists so
     * the guest can build a string the fetcher below immediately takes apart again — the seam
     * is URL-shaped, and reshaping `commonMain` for one target would be the wrong trade.
     */
    const val URL_TEMPLATE: String = "host://{z}/{x}/{y}"

    /** Bytes per pixel, fixed by [RawTile]'s own invariant. */
    const val BYTES_PER_PIXEL: Int = 4

    /**
     * Edge of the tiles the host serves, in pixels. Set from the provider's configuration; 512
     * is `ElevationProviderConfig`'s default, repeated here only as the value before anyone
     * configures anything.
     */
    var tileSize: Int = 512
        set(value) {
            require(value > 0 && (value and (value - 1)) == 0) {
                "tile size must be a positive power of two, was $value"
            }
            field = value
        }

    /** Bytes one decoded tile occupies. */
    val decodedTileBytes: Int get() = tileSize * tileSize * BYTES_PER_PIXEL

    /**
     * What the host sends through `fetch_tile`.
     *
     * `RGBA` is the original contract of task w05 and stays the default, so no host that already
     * works has to change anything. `WEBP` arrived with task w11, when the module grew a
     * pure-Kotlin VP8L decoder: a host in that mode answers with the **bytes it downloaded**, and
     * needs no image library at all — one HTTP GET is the whole implementation.
     *
     * An explicit setting rather than sniffing the payload: a WebP file happens to be smaller
     * than a decoded tile *today*, but deciding by length would silently misread the day it is
     * not, and a misread tile is wrong elevations rather than an error.
     */
    enum class TileFormat { RGBA, WEBP }

    /** How the host answers `fetch_tile`. Set through the ABI's `vcSetElevationConfig`. */
    var tileFormat: TileFormat = TileFormat.RGBA

    /**
     * The buffer size the guest offers `fetch_tile`, i.e. its `cap` argument.
     *
     * In `WEBP` mode the guest cannot know the compressed size in advance, so it offers the
     * decoded size, which is a generous upper bound: lossless WebP of a DEM tile runs at roughly
     * a third of it, and a "compressed" file larger than its own pixels would be a pathological
     * encoder rather than a tile.
     */
    val tileBytes: Int get() = decodedTileBytes
}

/**
 * A fetcher for [TileManager] / [ElevationProvider], reading through the host import.
 *
 * Top-level rather than a member of [HostTileSource], and that is not a style choice: a Kotlin
 * object keeps its members reachable as a block, so `HostTileSource.tileSize` alone would have
 * dragged the `fetch_tile` import into `:elevation`'s test binary — which the KGP runner cannot
 * supply, taking out all 194 wasmWasi tests of the module with `unknown import`. Data on one
 * side, the import on the other, is what lets the geometry be tested at all.
 */
fun hostTileFetcher(): suspend (String) -> RawTile = { url -> fetchFromHost(parseTileUrl(url)) }

@WasmImport("vcyclist", "fetch_tile")
private external fun fetchTile(
    zoom: Int,
    x: Int,
    y: Int,
    ptr: Int,
    cap: Int,
): Int

/**
 * Take a `host://z/x/y` URL apart.
 *
 * Kept as a separate, pure function on purpose: it is the only part of this file a unit test can
 * reach, since anything touching the import cannot even be instantiated by the KGP test runner
 * (see `docs/kotlin-wasm-wasi.md` §5).
 */
internal fun parseTileUrl(url: String): TileCoordinates {
    val body = url.removePrefix("host://")
    val parts = body.split('/')
    require(parts.size == 3 && body != url) {
        "not a host tile URL: '$url' — expected ${HostTileSource.URL_TEMPLATE}"
    }
    val z = parts[0].toIntOrNull()
    val x = parts[1].toIntOrNull()
    val y = parts[2].toIntOrNull()
    require(z != null && x != null && y != null) { "non-integer tile coordinates in '$url'" }
    return TileCoordinates(z = z, x = x, y = y)
}

/**
 * The RGBA of a tile that reads as **0 m everywhere**, for the `no tile here` answer.
 *
 * `r = 128, g = 0, b = 0` is not arbitrary: Terrarium decodes it as `128 × 256 − 32768 = 0`.
 * Zero-filling the buffer instead would decode as −32768 m, which is not "unknown", it is a
 * catastrophically wrong elevation that the smoother would then spread over its neighbours.
 */
internal fun seaLevelTile(tileSize: Int): RawTile {
    val rgba = ByteArray(tileSize * tileSize * HostTileSource.BYTES_PER_PIXEL)
    for (i in rgba.indices step HostTileSource.BYTES_PER_PIXEL) {
        rgba[i] = 128.toByte()
        rgba[i + 3] = 255.toByte()
    }
    return RawTile(tileSize, tileSize, rgba)
}

private suspend fun fetchFromHost(tc: TileCoordinates): RawTile {
    val size = HostTileSource.tileSize
    val cap = HostTileSource.tileBytes
    val webp = HostTileSource.tileFormat == HostTileSource.TileFormat.WEBP
    var written = 0
    var payload = ByteArray(0)
    withScopedMemoryAllocator { allocator ->
        val buf = allocator.allocate(cap)
        written = fetchTile(tc.z, tc.x, tc.y, buf.address.toInt(), cap)
        if (written > 0) {
            require(written <= cap) {
                "host fetch_tile(${tc.z}/${tc.x}/${tc.y}) wrote $written bytes into a $cap-byte buffer"
            }
            // Exactly `written` bytes: in WEBP mode the tile is far smaller than the buffer, and
            // copying the whole buffer would hand the decoder a file with megabytes of trailing
            // garbage.
            payload = ByteArray(written) { i -> (buf + i).loadByte() }
        }
    }
    if (written == 0) return seaLevelTile(size)
    if (webp) return decodeTileBytes(payload, "host://${tc.z}/${tc.x}/${tc.y}")
    check(written == cap) {
        "host fetch_tile(${tc.z}/${tc.x}/${tc.y}) returned $written, expected $cap decoded bytes or 0 " +
            "(set tileFormat to \"webp\" if the host means to send the compressed file)"
    }
    return RawTile(size, size, payload)
}
