package io.github.glandais.elevation

/**
 * Caches decoded [Tile]s keyed by [TileCoordinates], using [fetcher] to download missing tiles
 * via [urlTemplate].
 *
 * The [urlTemplate] follows the `https://host/{z}/{x}/{y}.webp` convention — the `{z}`, `{x}`
 * and `{y}` placeholders are substituted with the integer tile coordinates, and `{bbox}` with the
 * tile's WMS 1.3.0 EPSG:4326 footprint (see [ElevationProviderConfig]).
 *
 * @param fetcher pluggable for tests; defaults to the platform-specific [fetchAndDecodeTile].
 */
class TileManager(
    val urlTemplate: String,
    cacheSize: Int,
    private val fetcher: suspend (String) -> RawTile = ::fetchAndDecodeTile,
) {
    private val cache: LruCache<TileCoordinates, Tile> =
        LruCache(
            maxSize = cacheSize,
            loader = { tc -> Tile(fetcher(buildUrl(tc))) },
        )

    suspend fun getTile(tileCoords: TileCoordinates): Tile = cache.get(tileCoords)

    suspend fun clear() = cache.clear()

    private fun buildUrl(tc: TileCoordinates): String =
        urlTemplate
            .replace("{z}", tc.z.toString())
            .replace("{x}", tc.x.toString())
            .replace("{y}", tc.y.toString())
            .let { if ("{bbox}" in it) it.replace("{bbox}", wmsBbox4326(tc)) else it }

    internal suspend fun cachedKeys(): List<TileCoordinates> = cache.snapshotKeys()
}
