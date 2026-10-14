package io.github.glandais.elevation

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.ceil
import kotlin.time.TimeSource

/**
 * A tile server answered with a non-2xx [status].
 *
 * An [IllegalStateException], as the plain `check` it replaces threw, so callers catching that
 * keep working; typed so [pacedTileBytesFetcher] can tell "slow down" from "not here".
 * [retryAfterSeconds] is the server's `Retry-After` when the transport could read it — a browser
 * cannot, since IGN does not expose the header to CORS.
 */
class TileFetchException(
    val url: String,
    val status: Int,
    val retryAfterSeconds: Long? = null,
) : IllegalStateException("Tile fetch failed for $url: HTTP $status")

/**
 * [fetch], starting at most [maxRequestsPerSecond] requests a second, and retrying an HTTP 429 or
 * 503 up to [maxAttempts] times in all.
 *
 * Exists for IGN's Géoplateforme, which allows 40 requests a second per IP and answers 429 past it
 * — and [BatchCalculator] fetches ten tiles at once, which a fast connection turns into far more
 * than 40 small BIL tiles a second. Measured: a cold 130 km ride failed on its 51st tile. Pacing
 * is what keeps a run under the limit; the retry is what survives the limit being shared with
 * whatever else the machine is doing.
 *
 * The wait before a retry is the server's `Retry-After` when known, else 1, 2, 4… seconds. Any
 * other failure propagates at once: a 404 is an answer, not a congestion.
 *
 * **One pacing budget per host and rate, for the whole process**, shared by every fetcher built
 * here with the same clock: the server's limit is per IP, not per layer. IGN's terrain and surface
 * models are two tiers on the same host, each built with its own fetcher; with a budget each, two
 * concurrent rides on one shared provider — one reading the terrain, the other the decks — started
 * up to 40 requests a second, IGN's threshold. Requests to other hosts keep their own budget.
 * `maxRequestsPerSecond = 0` means no pacing, retries only.
 *
 * @param now monotonic milliseconds, injectable so a test can run on virtual time. Budgets are
 *   shared per clock as well: a test's own clock never waits behind the real one's requests.
 */
fun pacedTileBytesFetcher(
    maxRequestsPerSecond: Int,
    fetch: suspend (String) -> ByteArray = ::fetchTileBytes,
    maxAttempts: Int = 6,
    now: () -> Long = processClock,
): suspend (String) -> ByteArray {
    require(maxRequestsPerSecond >= 0) { "maxRequestsPerSecond must be >= 0, was $maxRequestsPerSecond" }
    require(maxAttempts >= 1) { "maxAttempts must be >= 1, was $maxAttempts" }
    return { url ->
        val pacer = if (maxRequestsPerSecond > 0) HostPacers.of(hostOf(url), maxRequestsPerSecond, now) else null
        var attempt = 1
        var bytes: ByteArray? = null
        while (bytes == null) {
            pacer?.awaitTurn()
            try {
                bytes = fetch(url)
            } catch (e: TileFetchException) {
                if (e.status != 429 && e.status != 503 || attempt >= maxAttempts) throw e
                delay((e.retryAfterSeconds ?: (1L shl (attempt - 1))) * 1000L)
                attempt++
            }
        }
        bytes
    }
}

private fun monotonicMillis(): () -> Long {
    val origin = TimeSource.Monotonic.markNow()
    return { origin.elapsedNow().inWholeMilliseconds }
}

/** The one real clock: every production fetcher shares it, hence shares its hosts' budgets. */
private val processClock: () -> Long = monotonicMillis()

/** `https://data.geopf.fr/wms-r/wms?…` → `data.geopf.fr`; the whole string when there is no scheme. */
internal fun hostOf(url: String): String =
    url
        .substringAfter("://")
        .substringBefore('/')
        .substringBefore('?')

/** The process-wide pacers, one per host, rate and clock. */
private object HostPacers {
    private data class Key(
        val host: String,
        val perSecond: Int,
        val clock: () -> Long,
    )

    private val mutex = Mutex()
    private val pacers = HashMap<Key, RequestPacer>()

    suspend fun of(
        host: String,
        perSecond: Int,
        clock: () -> Long,
    ): RequestPacer = mutex.withLock { pacers.getOrPut(Key(host, perSecond, clock)) { RequestPacer(perSecond, clock) } }
}

/** Hands out start slots [1 / perSecond] apart, in call order. */
private class RequestPacer(
    perSecond: Int,
    private val now: () -> Long,
) {
    private val spacingMs = 1000.0 / perSecond
    private val mutex = Mutex()
    private var nextSlotMs = Double.NEGATIVE_INFINITY

    suspend fun awaitTurn() {
        val waitMs =
            mutex.withLock {
                val t = now().toDouble()
                val slot = maxOf(t, nextSlotMs)
                nextSlotMs = slot + spacingMs
                slot - t
            }
        if (waitMs > 0) delay(ceil(waitMs).toLong())
    }
}
