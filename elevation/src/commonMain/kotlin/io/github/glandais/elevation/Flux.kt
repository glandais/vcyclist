package io.github.glandais.elevation

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

object Flux {
    /**
     * Apply [action] to each item in [items], with at most [maxParallel] concurrent invocations.
     * If any invocation throws, the scope is cancelled and the first error is rethrown.
     */
    suspend fun <T> forEachParallel(
        items: Iterable<T>,
        maxParallel: Int = 1,
        action: suspend (T) -> Unit,
    ) {
        require(maxParallel >= 1) { "maxParallel must be >= 1, got $maxParallel" }
        coroutineScope {
            val semaphore = Semaphore(maxParallel)
            items
                .map { item ->
                    async {
                        semaphore.withPermit { action(item) }
                    }
                }.awaitAll()
        }
    }
}
