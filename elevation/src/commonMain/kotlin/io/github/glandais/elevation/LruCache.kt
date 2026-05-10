package io.github.glandais.elevation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Suspend-aware LRU cache with per-key load deduplication.
 *
 * - O(1) get/put using a LinkedHashMap (insertion order; re-insertion = move to end).
 * - When two coroutines call [get] for the same missing key, only one [loader] invocation runs;
 *   the second awaits the same CompletableDeferred.
 * - Beyond [maxSize], the least-recently-used entry is evicted (and [onEvict] is invoked).
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

    /** Look up [key], loading via [loader] on miss. Concurrent gets for the same key share one load. */
    suspend fun get(key: K): V {
        // Fast path: cache hit (move to most-recently-used) or wait for an in-flight load.
        val existing =
            mutex.withLock {
                entries.remove(key)?.let { v ->
                    entries[key] = v
                    return v
                }
                inFlight[key]
            }
        if (existing != null) return existing.await()

        val ours = CompletableDeferred<V>()
        val awaitInstead =
            mutex.withLock {
                // Re-check after suspension boundary (TOCTOU).
                entries.remove(key)?.let { v ->
                    entries[key] = v
                    return v
                }
                val raced = inFlight[key]
                if (raced != null) {
                    raced
                } else {
                    inFlight[key] = ours
                    null
                }
            }
        if (awaitInstead != null) return awaitInstead.await()

        val value =
            try {
                loader(key)
            } catch (t: Throwable) {
                mutex.withLock { inFlight.remove(key) }
                ours.completeExceptionally(t)
                throw t
            }

        mutex.withLock {
            inFlight.remove(key)
            if (entries.size >= maxSize && key !in entries) {
                val iter = entries.entries.iterator()
                val oldest = iter.next()
                val oldestValue = oldest.value
                iter.remove()
                onEvict?.invoke(oldestValue)
            }
            entries[key] = value
        }
        ours.complete(value)
        return value
    }

    /** Evict all entries (calling [onEvict] on each). */
    suspend fun clear() =
        mutex.withLock {
            if (onEvict != null) entries.values.forEach { onEvict.invoke(it) }
            entries.clear()
        }

    // Test inspection — internal so commonTest in this module can reach it.
    internal suspend fun snapshotKeys(): List<K> = mutex.withLock { entries.keys.toList() }

    internal suspend fun snapshotSize(): Int = mutex.withLock { entries.size }
}
