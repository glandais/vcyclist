package io.github.glandais.elevation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Suspend-aware LRU cache with per-key load deduplication.
 *
 * - O(1) get/put using a LinkedHashMap (insertion order; re-insertion = move to end).
 * - When two coroutines call [get] for the same missing key, only one [loader] invocation runs;
 *   the second awaits the same CompletableDeferred.
 * - A waiter only ever sees the shared value or a real loader failure. When the load ends in a
 *   [CancellationException] (typically: the owner's coroutine was cancelled), that cancellation
 *   belongs to the owner alone. Waiters are told to retry, and one of them becomes the new owner.
 * - The in-flight bookkeeping is released **non-cancellably**: a cancelled owner still removes its
 *   in-flight entry and completes its deferred, even if the mutex is contended at that moment.
 *   Otherwise the key would point at a deferred that nobody completes, and every later [get] of
 *   it would hang.
 * - Beyond [maxSize], the least-recently-used entry is evicted and [onEvict] is invoked on it —
 *   **outside** the mutex, after the waiters have been released.
 * - All mutable state is guarded by a single [Mutex].
 *
 * Intended for use from coroutine contexts. Not safe for non-coroutine concurrent access.
 */
class LruCache<K : Any, V : Any>(
    private val maxSize: Int,
    private val loader: suspend (K) -> V,
    private val onEvict: ((V) -> Unit)? = null,
) {
    init {
        require(maxSize > 0) { "Cache size must be greater than 0" }
    }

    private val mutex = Mutex()
    private val entries: LinkedHashMap<K, V> = LinkedHashMap()
    private val inFlight: MutableMap<K, CompletableDeferred<V>> = mutableMapOf()

    /**
     * Completes a shared deferred whose owner's load was cancelled. Deliberately *not* a
     * [CancellationException]: a waiter must never mistake it for its own cancellation.
     */
    private class LoadAbandoned : Exception("load abandoned by its cancelled owner")

    private sealed interface Lookup<out T> {
        class Hit<T>(
            val value: T,
        ) : Lookup<T>

        class Await<T>(
            val deferred: CompletableDeferred<T>,
        ) : Lookup<T>

        class Owner<T>(
            val deferred: CompletableDeferred<T>,
        ) : Lookup<T>
    }

    /** Look up [key], loading via [loader] on miss. Concurrent gets for the same key share one load. */
    suspend fun get(key: K): V {
        while (true) {
            when (val lookup = lookupOrClaim(key)) {
                is Lookup.Hit -> return lookup.value
                is Lookup.Owner -> return load(key, lookup.deferred)
                is Lookup.Await ->
                    try {
                        return lookup.deferred.await()
                    } catch (_: LoadAbandoned) {
                        // The owner was cancelled, this caller was not: look again, maybe as owner.
                    }
            }
        }
    }

    /** A hit (moved to most-recently-used), an in-flight load to await, or a claim on the load. */
    private suspend fun lookupOrClaim(key: K): Lookup<V> =
        mutex.withLock {
            val hit = entries.remove(key)
            if (hit != null) {
                entries[key] = hit
                return@withLock Lookup.Hit(hit)
            }
            val raced = inFlight[key]
            if (raced != null) {
                Lookup.Await(raced)
            } else {
                val ours = CompletableDeferred<V>()
                inFlight[key] = ours
                Lookup.Owner(ours)
            }
        }

    private suspend fun load(
        key: K,
        ours: CompletableDeferred<V>,
    ): V {
        val value =
            try {
                loader(key)
            } catch (t: Throwable) {
                // NonCancellable: a cancelled owner must still be able to wait for a held mutex.
                withContext(NonCancellable) { mutex.withLock { inFlight.remove(key) } }
                ours.completeExceptionally(if (t is CancellationException) LoadAbandoned() else t)
                throw t
            }

        val evicted =
            withContext(NonCancellable) {
                mutex.withLock {
                    inFlight.remove(key)
                    var oldest: V? = null
                    if (entries.size >= maxSize && key !in entries) {
                        val iter = entries.entries.iterator()
                        oldest = iter.next().value
                        iter.remove()
                    }
                    entries[key] = value
                    oldest
                }
            }
        ours.complete(value)
        if (evicted != null) onEvict?.invoke(evicted)
        return value
    }

    /** Evict all entries (calling [onEvict] on each, outside the mutex). */
    suspend fun clear() {
        val evicted =
            mutex.withLock {
                val all = entries.values.toList()
                entries.clear()
                all
            }
        onEvict?.let { evict -> evicted.forEach(evict) }
    }

    // Test inspection — internal so commonTest in this module can reach it.
    internal suspend fun snapshotKeys(): List<K> = mutex.withLock { entries.keys.toList() }

    internal suspend fun snapshotSize(): Int = mutex.withLock { entries.size }
}
