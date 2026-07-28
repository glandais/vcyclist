/**
 * Blocking and `CompletableFuture` bridges over [ElevationStep.fixElevation] (task g22).
 *
 * Only `fixElevation` needs a bridge: [ElevationStep.smoothElevation] is already synchronous, and
 * so are `PathSimplifier` and the resamplers. It appears here all the same since task g27, which
 * added the Java-callable form of its default window — see `GpxWriterJvm` for that rationale.
 *
 * Top-level functions under a `@JvmName`, not extensions on the [ElevationStep] object: an
 * extension would read `ElevationStepJvmKt.fixElevationBlocking(ElevationStep.INSTANCE, …)` from
 * Java, which is precisely the kind of friction this task exists to remove. Java sees a plain
 * static utility class instead.
 *
 * See `ElevationProviderJvm` for the blocking/async contract — same rules here: `…Blocking`
 * parks the calling thread and must not be called from a coroutine or a UI thread; `…Async`
 * propagates cancellation from the future to the coroutine and wraps failures in a
 * `CompletionException`.
 */
@file:JvmName("ElevationStepJvm")

package io.github.glandais.engine.path

import io.github.glandais.elevation.ElevationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.future.future
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * Java-callable form of [ElevationStep.smoothElevation] (task g27): synchronous already, it only
 * needed its default window to be reachable.
 */
@JvmOverloads
fun smoothElevation(
    source: Path,
    windowM: Double = ElevationStep.DEFAULT_SMOOTH_WINDOW_M,
): Path = ElevationStep.smoothElevation(source, windowM)

fun fixElevationBlocking(
    source: Path,
    provider: ElevationProvider,
): Path = runBlocking { ElevationStep.fixElevation(source, provider) }

@JvmOverloads
fun fixElevationAsync(
    source: Path,
    provider: ElevationProvider,
    executor: Executor? = null,
): CompletableFuture<Path> = jvmFuture(executor) { ElevationStep.fixElevation(source, provider) }

/** See the note on `ElevationProviderJvm.jvmFuture` — deliberately duplicated per module. */
private fun <T> jvmFuture(
    executor: Executor?,
    block: suspend CoroutineScope.() -> T,
): CompletableFuture<T> {
    val dispatcher = executor?.asCoroutineDispatcher() ?: Dispatchers.IO
    return CoroutineScope(dispatcher + SupervisorJob()).future(block = block)
}
