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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * Java-callable factories for the provider and its configuration (task g27).
 *
 * `new ElevationProvider()` does not compile from Java: both constructor parameters are Kotlin
 * defaults, and the second is a `suspend` function type that has no Java literal at all. These
 * factories give the two forms a Java caller can actually want — all defaults, or a custom
 * configuration — and deliberately stop there: injecting a fetcher means writing a suspending
 * lambda, which is Kotlin's job.
 */
fun newElevationProvider(): ElevationProvider = ElevationProvider()

fun newElevationProvider(config: ElevationProviderConfig): ElevationProvider = ElevationProvider(config)

@JvmOverloads
fun elevationProviderConfig(
    zoomLevel: Int = 12,
    cacheSize: Int = 100,
    tileUrlTemplate: String = "https://tiles.mapterhorn.com/{z}/{x}/{y}.webp",
    tileSize: Int = 512,
): ElevationProviderConfig =
    ElevationProviderConfig(
        zoomLevel = zoomLevel,
        cacheSize = cacheSize,
        tileUrlTemplate = tileUrlTemplate,
        tileSize = tileSize,
    )

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
