package io.github.glandais.elevation

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.cancellation.CancellationException

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
    /**
     * When set, a tile whose fetch fails is a tile of **no data** instead of an exception: the
     * provider that owns this manager has a fallback, which then reads those points. Recorded in
     * the caller's [TileFailureLog], and remembered for [UnreadableTiles.retryAfterMs] so that every
     * point of a dead tile does not ask the server again.
     */
    internal var unreadable: UnreadableTiles? = null

    private val cache: LruCache<TileCoordinates, Tile> =
        LruCache(
            maxSize = cacheSize,
            loader = { tc -> Tile(fetcher(buildUrl(tc))) },
        )

    suspend fun getTile(tileCoords: TileCoordinates): Tile {
        val unreadable = unreadable ?: return cache.get(tileCoords)
        unreadable.cached(tileCoords)?.let { return it }
        return try {
            cache.get(tileCoords)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A cancelled job can surface as something else (a fetcher wrapping it): ask the job.
            currentCoroutineContext().ensureActive()
            unreadable.remember(tileCoords, e)
        }
    }

    suspend fun clear() = cache.clear()

    private fun buildUrl(tc: TileCoordinates): String =
        urlTemplate
            .replace("{z}", tc.z.toString())
            .replace("{x}", tc.x.toString())
            .replace("{y}", tc.y.toString())
            .let { if ("{bbox}" in it) it.replace("{bbox}", wmsBbox4326(tc)) else it }

    internal suspend fun cachedKeys(): List<TileCoordinates> = cache.snapshotKeys()
}
