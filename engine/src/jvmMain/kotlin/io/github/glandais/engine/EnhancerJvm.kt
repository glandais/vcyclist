@file:JvmName("EnhancerJvm")

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
 * Blocking and `CompletableFuture` bridges over the [Enhancer] pipeline (task g22).
 *
 * The pipeline is `suspend` for one reason only — the optional `fixElevation` step pulls DEM
 * tiles over the network. With `elevationProvider = null` nothing suspends at all, which is why
 * the blocking bridge is a genuine convenience rather than a thread-blocking trap in that case.
 *
 * Top-level functions under a `@JvmName` rather than extensions on the [Enhancer] object, so
 * Java sees `EnhancerJvm.enhanceCourseBlocking(…)` instead of having to pass
 * `Enhancer.INSTANCE`.
 *
 * See `ElevationProviderJvm` for the contract: `…Blocking` parks the calling thread and must not
 * be called from a coroutine or a UI thread; `…Async` cancels the coroutine when the future is
 * cancelled and reports failures as a `CompletionException`.
 */
@JvmOverloads
fun enhanceCourseBlocking(
    course: CoursePhysics,
    options: EnhanceOptions = EnhanceOptions.DEFAULT,
    elevationProvider: ElevationProvider? = null,
): Path = runBlocking { Enhancer.enhanceCourse(course, options, elevationProvider) }

@JvmOverloads
fun enhanceCourseDefaultBlocking(
    path: Path,
    elevationProvider: ElevationProvider? = null,
    options: EnhanceOptions = EnhanceOptions.DEFAULT,
): Path = runBlocking { Enhancer.enhanceCourseDefault(path, elevationProvider, options) }

@JvmOverloads
fun enhanceCoursesBlocking(
    paths: List<Path>,
    elevationProvider: ElevationProvider? = null,
    options: EnhanceOptions = EnhanceOptions.DEFAULT,
): List<Path> = runBlocking { Enhancer.enhanceCourses(paths, elevationProvider, options) }

@JvmOverloads
fun enhanceCourseAsync(
    course: CoursePhysics,
    options: EnhanceOptions = EnhanceOptions.DEFAULT,
    elevationProvider: ElevationProvider? = null,
    executor: Executor? = null,
): CompletableFuture<Path> = jvmFuture(executor) { Enhancer.enhanceCourse(course, options, elevationProvider) }

@JvmOverloads
fun enhanceCourseDefaultAsync(
    path: Path,
    elevationProvider: ElevationProvider? = null,
    options: EnhanceOptions = EnhanceOptions.DEFAULT,
    executor: Executor? = null,
): CompletableFuture<Path> = jvmFuture(executor) { Enhancer.enhanceCourseDefault(path, elevationProvider, options) }

@JvmOverloads
fun enhanceCoursesAsync(
    paths: List<Path>,
    elevationProvider: ElevationProvider? = null,
    options: EnhanceOptions = EnhanceOptions.DEFAULT,
    executor: Executor? = null,
): CompletableFuture<List<Path>> = jvmFuture(executor) { Enhancer.enhanceCourses(paths, elevationProvider, options) }

/** See the note on `ElevationProviderJvm.jvmFuture` — deliberately duplicated per module. */
private fun <T> jvmFuture(
    executor: Executor?,
    block: suspend CoroutineScope.() -> T,
): CompletableFuture<T> {
    val dispatcher = executor?.asCoroutineDispatcher() ?: Dispatchers.IO
    return CoroutineScope(dispatcher + SupervisorJob()).future(block = block)
}
