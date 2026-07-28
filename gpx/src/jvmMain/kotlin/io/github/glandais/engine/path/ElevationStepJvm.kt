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
 * Blocking and `CompletableFuture` bridges over [ElevationStep.fixElevation] (task g22).
 *
 * Only `fixElevation` needs one: [ElevationStep.smoothElevation] is already synchronous, and so
 * are `PathSimplifier` and the resamplers.
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
