package io.github.glandais.api.service

import io.github.glandais.elevation.ElevationProvider
import io.github.glandais.elevation.ElevationProviderConfig
import io.github.glandais.elevation.ElevationSource
import io.github.glandais.elevation.RawTile
import io.github.glandais.elevation.defaultTileFetcher
import io.github.glandais.elevation.provider
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

/**
 * The server's DEM access: one [ElevationProvider] per `(source, zoomLevel)`, built on first use
 * and kept, so its tile cache serves every request.
 *
 * **Each provider is used by one coroutine at a time**, behind its own [Mutex] ([Dem.lock]): the provider's
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
    private val entries = ConcurrentHashMap<Pair<ElevationSource, Int>, Dem>()

    /**
     * The provider of [source] at [zoom] (`null`: the source's default zoom) and its lock. Use the
     * provider only while holding [Dem.lock], and take that lock **before** a compute permit —
     * `ComputeGate.run(dem.lock) { … }` does both in that order — so a request queued behind the
     * DEM never holds a permit that a request not touching the DEM could have used.
     */
    fun dem(
        source: ElevationSource,
        zoom: Int?,
    ): Dem {
        val zoomLevel = zoom ?: source.defaultZoomLevel
        return entries.computeIfAbsent(source to zoomLevel) { Dem(source.provider(zoomLevel, fetcherFor)) }
    }
}

/** One DEM provider of the [ElevationGateway] and the lock that serialises its use. */
class Dem(
    val provider: ElevationProvider,
) {
    val lock: Mutex = Mutex()
}
