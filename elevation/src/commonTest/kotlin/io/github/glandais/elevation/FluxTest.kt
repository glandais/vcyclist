// `runCurrent()` — the only way to observe in-flight concurrency without advancing virtual time —
// is `@ExperimentalCoroutinesApi`.
@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.glandais.elevation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FluxTest {
    @Test
    fun `rejects maxParallel less than 1`() =
        runTest {
            val ex =
                assertFailsWith<IllegalArgumentException> {
                    Flux.forEachParallel(listOf("a"), maxParallel = 0) { /* unused */ }
                }
            assertEquals("maxParallel must be >= 1, got 0", ex.message)
        }

    @Test
    fun `sequential when maxParallel is 1`() =
        runTest {
            val order = mutableListOf<Int>()
            Flux.forEachParallel(listOf(1, 2, 3, 4), maxParallel = 1) { i ->
                order += i
            }
            assertEquals(listOf(1, 2, 3, 4), order)
        }

    @Test
    fun `at most maxParallel actions run concurrently`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            var inFlight = 0
            var maxObserved = 0
            val res =
                async {
                    Flux.forEachParallel(List(9) { it }, maxParallel = 3) {
                        inFlight++
                        if (inFlight > maxObserved) maxObserved = inFlight
                        gate.await()
                        inFlight--
                    }
                }
            runCurrent()
            assertEquals(3, inFlight)
            assertEquals(3, maxObserved)
            gate.complete(Unit)
            res.await()
            assertEquals(3, maxObserved)
            assertEquals(0, inFlight)
        }

    @Test
    fun `exception in one action propagates and cancels the rest`() =
        runTest {
            var completed = 0
            val outcome =
                async {
                    runCatching {
                        Flux.forEachParallel(List(5) { it }, maxParallel = 5) { i ->
                            if (i == 2) error("boom")
                            completed++
                        }
                    }
                }
            val res = outcome.await()
            assertTrue(res.isFailure, "expected failure")
            assertTrue(res.exceptionOrNull() is IllegalStateException)
        }

    @Test
    fun `empty input is a no-op`() =
        runTest {
            var called = 0
            Flux.forEachParallel(emptyList<Int>(), maxParallel = 4) { called++ }
            assertEquals(0, called)
        }
}
