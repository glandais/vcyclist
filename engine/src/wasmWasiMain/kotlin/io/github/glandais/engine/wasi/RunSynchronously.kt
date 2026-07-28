package io.github.glandais.engine.wasi

import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.Continuation
import kotlin.coroutines.startCoroutine

/**
 * Run a `suspend` block to completion on the calling thread and return its result.
 *
 * A Wasm export is a plain synchronous function: it cannot return a `Promise`, and there is no
 * event loop under it to resume anything. The engine's entry points (`Enhancer.enhanceCourse`)
 * are `suspend` because elevation fetching is, so something has to bridge the two.
 *
 * The bridge is honest rather than clever: it starts the coroutine and requires that it finished
 * before `startCoroutine` returned. `kotlinx-coroutines`' `runBlocking` is not an option — it
 * does not exist on wasmWasi, and there would be no thread to block anyway. What this must never
 * do is return a wrong value: if the block really suspends, it throws, naming what suspended,
 * instead of handing back a half-computed path.
 *
 * ## Why [Dispatchers.Unconfined]
 *
 * The elevation path is not a straight line of `suspend` calls: `Flux.kt` runs its tile lookups
 * through `coroutineScope { async { … } }` behind a `Semaphore`, and the tile cache is guarded by
 * a `Mutex`. With no dispatcher in the context, those children are scheduled on an event loop
 * that nothing under WASI ever pumps, and the guard below fires — which is exactly what happened
 * the first time `fixElevation: true` reached a real host.
 *
 * `Unconfined` runs each child eagerly on the calling stack, so an uncontended `Mutex`, a
 * `Semaphore` with permits left, and an `async` whose body only calls the synchronous
 * `fetch_tile` import all complete without ever suspending. Concurrency is lost — the tiles are
 * fetched one after another — which costs nothing here, since the host is single-threaded across
 * the boundary anyway and may not re-enter the module during a callback.
 */
internal fun <T> runSynchronously(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(
        Continuation(Dispatchers.Unconfined) { result -> outcome = result },
    )
    val result =
        outcome ?: throw IllegalStateException(
            "the operation suspended, which this target cannot resume: nothing drives a " +
                "continuation under WASI (see RunSynchronously and task w05)",
        )
    return result.getOrThrow()
}
