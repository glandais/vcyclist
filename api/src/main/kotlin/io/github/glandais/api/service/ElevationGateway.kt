package io.github.glandais.api.service

import io.github.glandais.elevation.ElevationProvider
import io.github.glandais.elevation.ElevationProviderConfig
import io.github.glandais.elevation.ElevationSource
import io.github.glandais.elevation.RawTile
import io.github.glandais.elevation.defaultTileFetcher
import io.github.glandais.elevation.provider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * The server's DEM access: one [ElevationProvider] per `(source, zoomLevel)`, built on first use
 * and kept, so its tile cache serves every request.
 *
 * **Each provider is used by one coroutine at a time**, behind its own [Mutex]: the provider's
 * LRU cache has never been audited for concurrent use (`Enhancer.kt`), and serialising it keeps
 * the cache, where one provider per request would lose it. Two requests on different sources or
 * zooms do not wait for each other.
 *
 * @param fetcherFor the tile transport of each tier — tests inject a flat Terrarium tile here;
 *   production uses the engine's [defaultTileFetcher]
 */
class ElevationGateway(
    private val fetcherFor: (ElevationProviderConfig) -> suspend (String) -> RawTile = ::defaultTileFetcher,
) {
    private class Entry(
        val provider: ElevationProvider,
    ) {
        val mutex = Mutex()
    }

    private val entries = ConcurrentHashMap<Pair<ElevationSource, Int>, Entry>()

    /**
     * Runs [block] with the provider of [source] at [zoom] (`null`: the source's default zoom),
     * holding that provider's lock for the whole block.
     */
    suspend fun <T> withProvider(
        source: ElevationSource,
        zoom: Int?,
        block: suspend (ElevationProvider) -> T,
    ): T {
        val zoomLevel = zoom ?: source.defaultZoomLevel
        val entry = entries.computeIfAbsent(source to zoomLevel) { Entry(source.provider(zoomLevel, fetcherFor)) }
        return entry.mutex.withLock { block(entry.provider) }
    }
}
