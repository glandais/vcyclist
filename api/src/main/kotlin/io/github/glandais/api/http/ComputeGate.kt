package io.github.glandais.api.http

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicLong

/**
 * The one way a route runs CPU-bound engine work: at most [concurrency] computations at once, on
 * [Dispatchers.Default], answered within [timeoutMs] (waiting for a permit included).
 *
 * **What the timeout bounds, honestly:** the *response*, not the computation. The engine never
 * checks for cancellation — the pipeline, the climb detector and the map renderers are plain
 * loops — so when the delay elapses the caller gets 503 `timeout` while the work runs to its end
 * on its thread, holding its permit until then. That is why the work runs in the gate's own scope
 * and the caller only *awaits* it: a `withContext` around a loop that never suspends would not
 * return before the loop does, timeout or not. A computation still waiting for its permit is
 * cancelled for real. The protection against a request that would compute for too long is
 * upstream: `maxTrackPoints` (the pipeline is linear in the point count) and the analysis
 * options' own bounds.
 */
class ComputeGate(
    val concurrency: Int,
    val timeoutMs: Long,
) {
    init {
        require(concurrency > 0) { "concurrency must be positive, was $concurrency" }
        require(timeoutMs > 0) { "timeoutMs must be positive, was $timeoutMs" }
    }

    private val semaphore = Semaphore(concurrency)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val admittedCount = AtomicLong()

    /**
     * How many computations were ever submitted to this gate. Observability — and the proof, in
     * tests, that a request rejected for its options never reached the engine.
     */
    val admitted: Long get() = admittedCount.get()

    /**
     * Runs [block] under the gate and returns its result, or rethrows its failure.
     *
     * @throws ApiProblem `timeout` when [timeoutMs] elapses first
     */
    suspend fun <T> run(block: suspend () -> T): T {
        admittedCount.incrementAndGet()
        val work = scope.async { semaphore.withPermit { block() } }
        try {
            return withTimeout(timeoutMs) { work.await() }
        } catch (e: TimeoutCancellationException) {
            throw ApiProblem(ProblemKind.TIMEOUT, "Computation did not answer within $timeoutMs ms", cause = e)
        } finally {
            // Timed out or caller gone: stop it if it has not started; a running loop goes on.
            if (!work.isCompleted) work.cancel()
        }
    }
}
