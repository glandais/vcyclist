// `runCurrent()` — used here to let both coroutines reach the cache before the gate opens — is
// `@ExperimentalCoroutinesApi`.
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

class LruCacheTest {
    @Test
    fun `maxSize zero is rejected`() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                LruCache<String, String>(maxSize = 0, loader = { it })
            }
        assertEquals("Cache size must be greater than 0", ex.message)
    }

    @Test
    fun `maxSize negative is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            LruCache<String, String>(maxSize = -1, loader = { it })
        }
    }

    @Test
    fun `get miss invokes loader once`() =
        runTest {
            var calls = 0
            val cache =
                LruCache<String, String>(maxSize = 4, loader = { k ->
                    calls++
                    "v-$k"
                })
            assertEquals("v-foo", cache.get("foo"))
            assertEquals(1, calls)
        }

    @Test
    fun `get hit does not re-invoke loader`() =
        runTest {
            var calls = 0
            val cache =
                LruCache<String, String>(maxSize = 4, loader = { k ->
                    calls++
                    "v-$k"
                })
            cache.get("foo")
            cache.get("foo")
            assertEquals(1, calls)
        }

    @Test
    fun `evicts LRU beyond maxSize`() =
        runTest {
            val cache = LruCache<String, String>(maxSize = 2, loader = { "v-$it" })
            cache.get("A")
            cache.get("B")
            cache.get("C")
            assertEquals(listOf("B", "C"), cache.snapshotKeys())
            assertEquals(2, cache.snapshotSize())
        }

    @Test
    fun `onEvict invoked on capacity eviction`() =
        runTest {
            val evicted = mutableListOf<String>()
            val cache =
                LruCache<String, String>(
                    maxSize = 2,
                    loader = { "v-$it" },
                    onEvict = { evicted += it },
                )
            cache.get("A")
            cache.get("B")
            cache.get("C")
            assertEquals(listOf("v-A"), evicted)
        }

    @Test
    fun `accessing an entry refreshes its LRU position`() =
        runTest {
            val cache = LruCache<String, String>(maxSize = 2, loader = { "v-$it" })
            cache.get("A")
            cache.get("B")
            cache.get("A")
            cache.get("C")
            assertEquals(listOf("A", "C"), cache.snapshotKeys())
        }

    @Test
    fun `deduplicates concurrent get on same key`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            var calls = 0
            val cache =
                LruCache<String, String>(
                    maxSize = 4,
                    loader = { k ->
                        calls++
                        gate.await()
                        "v-$k"
                    },
                )

            val a = async { cache.get("foo") }
            val b = async { cache.get("foo") }
            runCurrent()
            assertEquals(1, calls)
            gate.complete(Unit)
            assertEquals("v-foo", a.await())
            assertEquals("v-foo", b.await())
            assertEquals(1, calls)
        }

    @Test
    fun `loader exception is forwarded to all waiters`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            var calls = 0
            val cache =
                LruCache<String, String>(
                    maxSize = 4,
                    loader = { _ ->
                        calls++
                        gate.await()
                        error("boom")
                    },
                )

            // runCatching to keep exceptions from propagating to the runTest scope.
            val a = async { runCatching { cache.get("foo") } }
            val b = async { runCatching { cache.get("foo") } }
            runCurrent()
            assertEquals(1, calls)
            gate.complete(Unit)
            val rA = a.await()
            val rB = b.await()
            assertTrue(rA.isFailure, "expected failure on A, got=$rA")
            assertTrue(rB.isFailure, "expected failure on B, got=$rB")
            assertTrue(rA.exceptionOrNull() is IllegalStateException)
            assertTrue(rB.exceptionOrNull() is IllegalStateException)
            assertEquals(1, calls)
        }

    @Test
    fun `loader failure does not poison the key`() =
        runTest {
            var calls = 0
            var shouldFail = true
            val cache =
                LruCache<String, String>(
                    maxSize = 4,
                    loader = { k ->
                        calls++
                        if (shouldFail) error("boom") else "v-$k"
                    },
                )

            assertFailsWith<IllegalStateException> { cache.get("foo") }
            shouldFail = false
            assertEquals("v-foo", cache.get("foo"))
            assertEquals(2, calls)
        }

    @Test
    fun `clear empties the cache and notifies onEvict`() =
        runTest {
            val evicted = mutableListOf<String>()
            val cache =
                LruCache<String, String>(
                    maxSize = 4,
                    loader = { "v-$it" },
                    onEvict = { evicted += it },
                )
            cache.get("A")
            cache.get("B")
            cache.clear()
            assertEquals(0, cache.snapshotSize())
            assertTrue(evicted.containsAll(listOf("v-A", "v-B")), "evicted=$evicted")
            assertEquals(2, evicted.size)
        }

    @Test
    fun `get after clear reloads`() =
        runTest {
            var calls = 0
            val cache =
                LruCache<String, String>(maxSize = 4, loader = {
                    calls++
                    "v-$it"
                })
            cache.get("A")
            cache.clear()
            cache.get("A")
            assertEquals(2, calls)
        }

    @Test
    fun `complex LRU ordering across evictions`() =
        runTest {
            val cache = LruCache<String, String>(maxSize = 3, loader = { "v-$it" })
            cache.get("A")
            cache.get("B")
            cache.get("C")
            assertEquals(listOf("A", "B", "C"), cache.snapshotKeys())

            cache.get("A")
            assertEquals(listOf("B", "C", "A"), cache.snapshotKeys())

            cache.get("D")
            assertEquals(listOf("C", "A", "D"), cache.snapshotKeys())

            cache.get("C")
            assertEquals(listOf("A", "D", "C"), cache.snapshotKeys())

            cache.get("E")
            assertEquals(listOf("D", "C", "E"), cache.snapshotKeys())
        }
}
