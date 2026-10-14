package io.github.glandais.elevation

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.TimeSource

/**
 * The tiles a provider could not read while this element was in the coroutine context, and whose
 * points its fallback read instead (see [ElevationProvider.withFallback]).
 *
 * A context element rather than a counter on the provider: a provider is shared — by concurrent
 * rides on a server — and each ride must report its own failures, not its neighbour's. Install one
 * with `withContext(TileFailureLog()) { … }` around the reads, then read [failedTiles]. A tile
 * remembered as unreadable by an earlier read is recorded again by every later one that meets it.
 */
class TileFailureLog : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TileFailureLog>

    private val mutex = Mutex()
    private val tiles = LinkedHashMap<String, TileFailure>()

    internal suspend fun record(failure: TileFailure) {
        mutex.withLock { tiles.getOrPut("${failure.urlTemplate}|${failure.tile}") { failure } }
    }

    /** Every distinct tile that failed, first failure first. Read it once the reads are done. */
    val failedTiles: List<TileFailure> get() = tiles.values.toList()
}

/**
 * One tile that could not be read: the template of the provider it belongs to, its coordinates,
 * and the error (the last attempt's, after the request pacer's retries).
 */
data class TileFailure(
    val urlTemplate: String,
    val tile: TileCoordinates,
    val message: String,
)

/** The negative cache of one [TileManager] whose provider has a fallback. */
internal class UnreadableTiles(
    private val urlTemplate: String,
    private val tileSize: Int,
    /** How long a failed tile is answered as no data before it is asked again. */
    val retryAfterMs: Long = RETRY_AFTER_MS,
    private val now: () -> Long = monotonicClock,
) {
    private class Entry(
        val failure: TileFailure,
        val untilMs: Long,
    )

    private val mutex = Mutex()
    private val entries = HashMap<TileCoordinates, Entry>()
    private val empty by lazy { Tile(noDataTile(tileSize)).also { it.unreadable = true } }

    /** The no-data tile standing for [tc] while it is remembered as unreadable, recorded; else `null`. */
    suspend fun cached(tc: TileCoordinates): Tile? {
        val entry =
            mutex.withLock {
                val e = entries[tc] ?: return null
                if (now() >= e.untilMs) {
                    entries.remove(tc)
                    return null
                }
                e
            }
        currentCoroutineContext()[TileFailureLog]?.record(entry.failure)
        return empty
    }

    /** Remembers [tc] as unreadable because of [error], records it, and answers it as no data. */
    suspend fun remember(
        tc: TileCoordinates,
        error: Exception,
    ): Tile {
        val failure = TileFailure(urlTemplate, tc, error.message ?: error::class.simpleName ?: "error")
        mutex.withLock { entries[tc] = Entry(failure, now() + retryAfterMs) }
        currentCoroutineContext()[TileFailureLog]?.record(failure)
        return empty
    }

    companion object {
        /** A minute: long enough for a ride to meet a dead tile once, short enough for a server. */
        const val RETRY_AFTER_MS: Long = 60_000L

        private val monotonicClock: () -> Long =
            TimeSource.Monotonic.markNow().let { origin -> { origin.elapsedNow().inWholeMilliseconds } }
    }
}
