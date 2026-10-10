package io.github.glandais.elevation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TLC trace (LruCacheCancelOrphan.cfg, invariant NoOrphanInFlight): p1 registers inFlight[k2]
 * and runs its loader; p2 holds the mutex; p1 is cancelled, its loader throws, and the catch
 * block's `mutex.withLock { inFlight.remove(key) }` must suspend on a held lock — which a
 * cancelled coroutine cannot do, so it throws before removing the entry or completing `ours`.
 * inFlight[k2] then points at a deferred nobody will ever complete: every later get(k2) awaits
 * it forever.
 *
 * Here the mutex is held by another get() whose `onEvict` (run under the lock) is still busy.
 */
class FormalModelLruCacheOrphanTest {
    @Test
    fun `a load cancelled while the mutex is contended does not wedge the key forever`() {
        val yAttempts = AtomicInteger()
        val yLoaderStarted = CountDownLatch(1)
        val evictStarted = CountDownLatch(1)
        val releaseEvict = CountDownLatch(1)
        val blockEvict = AtomicBoolean(false)

        val cache =
            LruCache<String, String>(
                maxSize = 1,
                loader = { k ->
                    if (k == "y" && yAttempts.incrementAndGet() == 1) {
                        yLoaderStarted.countDown()
                        awaitCancellation()
                    }
                    "v-$k"
                },
                onEvict = {
                    if (blockEvict.get()) {
                        evictStarted.countDown()
                        releaseEvict.await(10, TimeUnit.SECONDS)
                    }
                },
            )
        runBlocking { cache.get("a") } // fill the single slot so the next insert evicts

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val loadY = scope.launch { cache.get("y") }
        assertTrue(yLoaderStarted.await(5, TimeUnit.SECONDS), "loader for y never started")

        blockEvict.set(true)
        val other = thread { runBlocking { cache.get("b") } } // evicts "a", holds the mutex
        assertTrue(evictStarted.await(5, TimeUnit.SECONDS), "eviction never started")

        loadY.cancel()
        runBlocking { withTimeout(5_000) { loadY.join() } }

        releaseEvict.countDown()
        other.join(5_000)
        scope.cancel()

        val again = runBlocking { withTimeoutOrNull(2_000) { cache.get("y") } }
        assertEquals("v-y", again, "get(\"y\") hung: inFlight[\"y\"] was orphaned by the cancelled load")
    }
}
