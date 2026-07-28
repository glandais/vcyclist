package io.github.glandais.engine.wasi

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * Run a `suspend` block to completion on the calling thread and return its result.
 *
 * A Wasm export is a plain synchronous function: it cannot return a `Promise`, and there is no
 * event loop under it to resume anything. The engine's entry points (`Enhancer.enhanceCourse`)
 * are `suspend` because elevation fetching is, so something has to bridge the two.
 *
 * The bridge is honest rather than clever: it starts the coroutine and requires that it finished
 * before `startCoroutine` returned. That holds for every path this ABI exercises today, because
 * the only genuinely suspending step is `fixElevation`, which needs a provider the module does
 * not have (task w05 supplies one, host-side, and will have to make this loop instead of throw).
 *
 * `kotlinx-coroutines`' `runBlocking` is not an option — it does not exist on wasmWasi, and there
 * is no thread to block anyway. What this must never do is return a wrong value: if the block
 * really suspends, it throws, naming what suspended, instead of handing back a half-computed
 * path.
 */
internal fun <T> runSynchronously(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(
        Continuation(EmptyCoroutineContext) { result -> outcome = result },
    )
    val result =
        outcome ?: throw IllegalStateException(
            "the operation suspended, which this target cannot resume: nothing drives a " +
                "continuation under WASI (see RunSynchronously and task w05)",
        )
    return result.getOrThrow()
}
