package io.github.glandais.elevation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Where a [TileStore] files one tile: a stable name for the layer, the tile's position in the Web
 * Mercator pyramid, and the extension of the bytes it holds. `{layer}/{z}/{x}/{y}.{extension}` is a
 * natural disk layout; for Mapterhorn it is `mapterhorn/{z}/{x}/{y}.webp`.
 *
 * Built from the tile coordinates, never from the URL: IGN's tiles are WMS requests whose URL
 * carries a bounding box, and no path segment of it names the tile.
 *
 * @property layer one of [MAPTERHORN], [IGN_LIDAR_HD_MNT], [IGN_LIDAR_HD_MNS], [IGN_LIDAR_HD_MNH]
 * @property extension `webp` for Terrarium tiles, `bil` for IGN's raw float32 rasters
 */
data class TileKey(
    val layer: String,
    val z: Int,
    val x: Int,
    val y: Int,
    val extension: String,
) {
    /** `{layer}/{z}/{x}/{y}.{extension}`, with `/` separators. */
    fun relativePath(): String = "$layer/$z/$x/$y.$extension"

    companion object {
        const val MAPTERHORN: String = "mapterhorn"
        const val IGN_LIDAR_HD_MNT: String = "ign-lidar-hd-mnt"
        const val IGN_LIDAR_HD_MNS: String = "ign-lidar-hd-mns"
        const val IGN_LIDAR_HD_MNH: String = "ign-lidar-hd-mnh"
    }
}

/**
 * Storage for DEM tiles, for a provider built by `ElevationProviderJvm.newElevationProvider(source,
 * store)`: the caller keeps the bytes, the library keeps the transport — the request pacing IGN
 * needs (it answers 429 past 40 requests a second), the retries, and the decoding.
 *
 * ## Contract
 *
 * - **Bytes are the raw HTTP body**, never a re-encoding: the WebP image for `mapterhorn`, the
 *   float32 BIL raster for the IGN layers. A store that already holds Mapterhorn's tiles under
 *   `mapterhorn/{z}/{x}/{y}.webp` is read as it is.
 * - [put] is called only with bytes that decoded as a tile: an error page, a truncated body or a
 *   no-data answer the decoder refuses never reaches the store.
 * - An entry [get] returns that does not decode is treated as a miss: the tile is fetched again and
 *   [put] overwrites it.
 * - **[get] throwing is a miss; [put] throwing is ignored.** The store is a cache: it never fails a
 *   DEM read.
 * - Both may block — they run on `Dispatchers.IO` — and must be **thread-safe**: up to ten tiles
 *   are fetched at once. A disk store writes to a temporary file and moves it into place.
 */
interface TileStore {
    /** The bytes stored under [key], or `null` when there are none. */
    fun get(key: TileKey): ByteArray?

    /** Stores [bytes] under [key], replacing what was there. */
    fun put(
        key: TileKey,
        bytes: ByteArray,
    )
}

/** The fetcher of one tier of a stored provider: [store] first, then the network, paced. */
internal fun storedTileFetcher(
    config: ElevationProviderConfig,
    store: TileStore,
    fetchNetwork: suspend (String) -> ByteArray = ::fetchTileBytes,
): suspend (String) -> RawTile {
    val layer = tileLayerOf(config)
    val extension =
        when (config.encoding) {
            TileEncoding.TERRARIUM -> "webp"
            TileEncoding.BIL32 -> "bil"
        }
    val fetchBytes = pacedTileBytesFetcher(config.maxRequestsPerSecond, fetchNetwork)
    return fetcher@{ url ->
        val tc = tileCoordinatesOf(url, config)
        val key = TileKey(layer, tc.z, tc.x, tc.y, extension)
        val cached = withContext(Dispatchers.IO) { readOrNull(store, key) }
        if (cached != null && cached.isNotEmpty()) {
            decodeOrNull(cached, url, config)?.let { return@fetcher it }
        }
        val fresh = fetchBytes(url)
        // Throws on a body that is not a tile, before anything reaches the store.
        val tile = decode(fresh, url, config)
        withContext(Dispatchers.IO) { writeQuietly(store, key, fresh) }
        tile
    }
}

private fun readOrNull(
    store: TileStore,
    key: TileKey,
): ByteArray? =
    try {
        store.get(key)
    } catch (e: Exception) {
        null
    }

private fun writeQuietly(
    store: TileStore,
    key: TileKey,
    bytes: ByteArray,
) {
    try {
        store.put(key, bytes)
    } catch (e: Exception) {
        // A cache that cannot write costs a download next time, never this read.
    }
}

private suspend fun decode(
    bytes: ByteArray,
    url: String,
    config: ElevationProviderConfig,
): RawTile =
    when (config.encoding) {
        TileEncoding.TERRARIUM -> decodeTileBytes(bytes, url)
        TileEncoding.BIL32 -> decodeBil32Tile(bytes, config.tileSize, url)
    }

private suspend fun decodeOrNull(
    bytes: ByteArray,
    url: String,
    config: ElevationProviderConfig,
): RawTile? =
    try {
        decode(bytes, url, config)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

/** The [TileKey.layer] of a catalogued tier; a hand-built configuration has no stable name. */
internal fun tileLayerOf(config: ElevationProviderConfig): String {
    val template = config.tileUrlTemplate
    if (template == ElevationSource.MAPTERHORN.config(config.zoomLevel).tileUrlTemplate) return TileKey.MAPTERHORN
    for (layer in IgnLidarHdLayer.entries) {
        if (template == layer.config(config.zoomLevel).tileUrlTemplate) {
            return when (layer) {
                IgnLidarHdLayer.MNT -> TileKey.IGN_LIDAR_HD_MNT
                IgnLidarHdLayer.MNS -> TileKey.IGN_LIDAR_HD_MNS
                IgnLidarHdLayer.MNH -> TileKey.IGN_LIDAR_HD_MNH
            }
        }
    }
    throw IllegalArgumentException("No TileStore layer for the tile URL template $template — only catalogued sources have one")
}
