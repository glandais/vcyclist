@file:JvmName("ElevationCorrectionJvm")

package io.github.glandais.engine

import io.github.glandais.elevation.ElevationProvider
import io.github.glandais.engine.path.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.future.future
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * Blocking and `CompletableFuture` bridges over [ElevationCorrection.fixAndCorrect]: the DEM
 * correction with its bridge decks and tunnels, for a Java caller that runs no physics. Options
 * come from `EngineModelJvm.elevationCorrectionOptions`.
 *
 * See `ElevationProviderJvm` for the contract: `…Blocking` parks the calling thread and must not
 * be called from a coroutine or a UI thread; `…Async` cancels the coroutine when the future is
 * cancelled and reports failures as a `CompletionException`.
 */
@JvmOverloads
fun fixAndCorrectBlocking(
    path: Path,
    provider: ElevationProvider,
    options: ElevationCorrectionOptions = ElevationCorrectionOptions.DEFAULT,
    maxSpacingM: Double = Enhancer.REFINE_MAX_DISTANCE_M,
): Path = runBlocking { ElevationCorrection.fixAndCorrect(path, provider, options, maxSpacingM) }

@JvmOverloads
fun fixAndCorrectAsync(
    path: Path,
    provider: ElevationProvider,
    options: ElevationCorrectionOptions = ElevationCorrectionOptions.DEFAULT,
    maxSpacingM: Double = Enhancer.REFINE_MAX_DISTANCE_M,
    executor: Executor? = null,
): CompletableFuture<Path> = jvmFuture(executor) { ElevationCorrection.fixAndCorrect(path, provider, options, maxSpacingM) }

/** See the note on `ElevationProviderJvm.jvmFuture` — deliberately duplicated per file. */
private fun <T> jvmFuture(
    executor: Executor?,
    block: suspend CoroutineScope.() -> T,
): CompletableFuture<T> {
    val dispatcher = executor?.asCoroutineDispatcher() ?: Dispatchers.IO
    return CoroutineScope(dispatcher + SupervisorJob()).future(block = block)
}
