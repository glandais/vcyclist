package io.github.glandais.api.service

import io.github.glandais.elevation.ElevationProvider
import io.github.glandais.elevation.ElevationProviderConfig
import io.github.glandais.elevation.ElevationSource
import io.github.glandais.elevation.RawTile
import io.github.glandais.elevation.defaultTileFetcher
import io.github.glandais.elevation.provider
import java.util.concurrent.ConcurrentHashMap

/**
 * The server's DEM access: one [ElevationProvider] per `(source, zoomLevel)`, built on first use
 * and kept, so its tile cache serves every request.
 *
 * **Requests share a provider concurrently**, with no lock: every piece of state on its read path
 * guards itself — the tile cache (`LruCache`, one `Mutex`, one download per tile however many
 * requests ask for it), the unreadable-tile memory and the per-host request pacer — and a `Tile`
 * is immutable once decoded. A cold ride therefore no longer holds the next request on the same
 * source for as long as it fetches; the compute gate still bounds how many run at once, and the
 * pacer how fast the tile server is asked. `ElevationGatewayConcurrencyTest` pins it.
 *
 * @param fetcherFor the tile transport of each tier — tests inject a flat Terrarium tile here;
 *   production uses the engine's [defaultTileFetcher]
 */
class ElevationGateway(
    private val fetcherFor: (ElevationProviderConfig) -> suspend (String) -> RawTile = ::defaultTileFetcher,
) {
    private val entries = ConcurrentHashMap<Pair<ElevationSource, Int>, ElevationProvider>()

    /** The provider of [source] at [zoom] (`null`: the source's default zoom). */
    fun provider(
        source: ElevationSource,
        zoom: Int?,
    ): ElevationProvider {
        val zoomLevel = zoom ?: source.defaultZoomLevel
        return entries.computeIfAbsent(source to zoomLevel) { source.provider(zoomLevel, fetcherFor = fetcherFor) }
    }
}
