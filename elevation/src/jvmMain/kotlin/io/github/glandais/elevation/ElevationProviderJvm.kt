/**
 * Blocking and `CompletableFuture` bridges over [ElevationProvider]'s suspending API (task g22).
 *
 * `getElevation` and `setElevations` do network I/O, so they are `suspend` in `commonMain` —
 * there is no `runBlocking` on Kotlin/JS, and making them blocking there would be a regression.
 * From Java, however, a `suspend` function means hand-writing a `Continuation`, which nobody
 * does. These bridges are the shorter door.
 *
 * ## Blocking
 *
 * `…Blocking` parks the calling thread until the result is ready. **Never call it from a UI
 * thread, from inside a coroutine, or from a thread of the pool you passed as [Executor]** —
 * the first two freeze the caller, the third can deadlock the pool. Exceptions propagate
 * unchanged.
 *
 * ## Async
 *
 * `…Async` returns immediately. Cancelling the returned future (`cancel(true)`) cancels the
 * coroutine underneath — the work stops instead of running on unobserved. Failures arrive as a
 * `CompletionException` wrapping the original exception, per `CompletableFuture` convention;
 * unwrap with `getCause()`.
 *
 * The optional [Executor] parameter takes a JDK type rather than a `CoroutineDispatcher` on
 * purpose: Java callers have executors, not dispatchers, and the coroutines dependency stays an
 * implementation detail of this library. `null` (the default) means `Dispatchers.IO`, the right
 * pool for the network-bound work these functions do.
 */
@file:JvmName("ElevationProviderJvm")

package io.github.glandais.elevation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.future.future
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.function.Function

/**
 * Java-callable factories for the provider and its configuration (task g27, extended by g32).
 *
 * `new ElevationProvider()` does not compile from Java: both constructor parameters are Kotlin
 * defaults, and the second is a `suspend` function type that has no Java literal at all.
 *
 * The line this file draws is now at **suspending** fetchers, not at fetchers: a blocking one is
 * a plain [Function] and is accepted below (g32), while a fetcher that genuinely suspends stays
 * Kotlin's business — a caller who already has a non-blocking HTTP client has no reason to be
 * routed through [Dispatchers.IO].
 */
fun newElevationProvider(): ElevationProvider = ElevationProvider()

fun newElevationProvider(config: ElevationProviderConfig): ElevationProvider = ElevationProvider(config)

/**
 * A provider whose tiles come from **your** code — the injection point for a disk cache, an
 * object store, or tiles bundled with the application (task g32).
 *
 * A typical body: look in the cache, and on a miss call
 * [TileFetcherJvm.fetchTileBytesBlocking] then [TileFetcherJvm.decodeTileBytesBlocking] — the two
 * halves task g21 made public so that "my transport, your decoder" would be possible. See
 * `elevation/README.md` for a complete example, including the atomic write the concurrency
 * contract below makes necessary.
 *
 * ## Contract for the fetcher
 *
 * - **It may block.** It is invoked on [Dispatchers.IO], never on the caller's thread, precisely
 *   so that reading a file or opening a socket is legitimate.
 * - **It must be thread-safe.** `BatchCalculator` fetches up to ten tiles at once, so several
 *   invocations overlap on different threads. A disk cache that wrote straight into its
 *   destination file would hand out corrupt tiles; write to a temporary file and `Files.move` it.
 * - Exceptions propagate unchanged to the caller of `getElevationBlocking` / `setElevationsBlocking`.
 *
 * Two hand-written overloads rather than one with `@JvmOverloads`: the annotation drops
 * **trailing** parameters, and here the defaulted one (`config`) comes first while the required
 * one (`fetcher`) comes last. It would generate a `newElevationProvider(config)` that loses the
 * fetcher *and* clashes with the factory above.
 *
 * @param fetcher maps a tile URL — built from [ElevationProviderConfig.tileUrlTemplate] — to its
 *   decoded pixels.
 */
fun newElevationProvider(fetcher: Function<String, RawTile>): ElevationProvider = newElevationProvider(ElevationProviderConfig(), fetcher)

/** Same, with a custom [config]. See the other overload for the fetcher's contract. */
fun newElevationProvider(
    config: ElevationProviderConfig,
    fetcher: Function<String, RawTile>,
): ElevationProvider =
    ElevationProvider(config) { url ->
        // withContext, not a bare call: the fetcher blocks, and TileManager invokes it from
        // whatever coroutine context BatchCalculator is parallelising on. Running blocking I/O
        // there would serialise the ten concurrent tile fetches at best, starve them at worst.
        withContext(Dispatchers.IO) { fetcher.apply(url) }
    }

/**
 * Every field of [ElevationProviderConfig], in declaration order.
 *
 * [attribution] is last and defaults to the same value the Kotlin constructor uses, so the short
 * forms are unaffected. It belongs here all the same: a caller who points [tileUrlTemplate] at
 * their own tile server is exactly the caller whose attribution text is no longer Mapterhorn's,
 * and leaving it out would have them credit the wrong source in their UI.
 */
@JvmOverloads
fun elevationProviderConfig(
    zoomLevel: Int = 12,
    cacheSize: Int = 100,
    tileUrlTemplate: String = "https://tiles.mapterhorn.com/{z}/{x}/{y}.webp",
    tileSize: Int = 512,
    attribution: Attribution = ElevationProviderConfig().attribution,
): ElevationProviderConfig =
    ElevationProviderConfig(
        zoomLevel = zoomLevel,
        cacheSize = cacheSize,
        tileUrlTemplate = tileUrlTemplate,
        tileSize = tileSize,
        attribution = attribution,
    )

/** `new Attribution(text)` does not compile from Java: `url` is a Kotlin default. */
@JvmOverloads
fun attribution(
    text: String,
    url: String? = null,
): Attribution = Attribution(text, url)

/** `new LatLon(lat, lon)` does not compile from Java: `elevation` is a Kotlin default. */
@JvmOverloads
fun latLon(
    latitude: Double,
    longitude: Double,
    elevation: Double? = null,
): LatLon = LatLon(latitude, longitude, elevation)

@JvmOverloads
fun ElevationProvider.getElevationBlocking(
    latitude: Double,
    longitude: Double,
    interpolation: Boolean = true,
): Double = runBlocking { getElevation(latitude, longitude, interpolation) }

@JvmOverloads
fun ElevationProvider.setElevationsBlocking(
    coordinates: List<Coordinates>,
    interpolation: Boolean = true,
): List<CoordinatesElevation> = runBlocking { setElevations(coordinates, interpolation) }

@JvmOverloads
fun ElevationProvider.getElevationAsync(
    latitude: Double,
    longitude: Double,
    interpolation: Boolean = true,
    executor: Executor? = null,
): CompletableFuture<Double> = jvmFuture(executor) { getElevation(latitude, longitude, interpolation) }

@JvmOverloads
fun ElevationProvider.setElevationsAsync(
    coordinates: List<Coordinates>,
    interpolation: Boolean = true,
    executor: Executor? = null,
): CompletableFuture<List<CoordinatesElevation>> = jvmFuture(executor) { setElevations(coordinates, interpolation) }

/**
 * Runs [block] on a scope of its own and hands back a future wired both ways: the coroutine
 * completes the future, and cancelling the future cancels the coroutine.
 *
 * A fresh [CoroutineScope] with a [SupervisorJob] rather than `GlobalScope`: work launched on a
 * scope nobody can cancel is a leak waiting for a slow server, and the whole point of the
 * `…Async` shape is that the caller keeps the handle.
 *
 * Internal-but-duplicated in `:gpx` and `:engine` — three near-identical private helpers beat a
 * public utility that would exist only to be shared, or a cross-module dependency added for six
 * lines.
 */
private fun <T> jvmFuture(
    executor: Executor?,
    block: suspend CoroutineScope.() -> T,
): CompletableFuture<T> {
    val dispatcher = executor?.asCoroutineDispatcher() ?: Dispatchers.IO
    return CoroutineScope(dispatcher + SupervisorJob()).future(block = block)
}
