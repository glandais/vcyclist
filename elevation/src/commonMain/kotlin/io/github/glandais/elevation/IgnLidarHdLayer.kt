package io.github.glandais.elevation

/**
 * IGN's **LiDAR HD rasters** served by the Géoplateforme raster WMS, as raw layers: the terrain
 * model (MNT), the surface model (MNS: the top of whatever stands on the ground — decks, trees,
 * roofs) and the height above ground (MNH).
 *
 * **Not a DEM source.** No wire id, no `byId`, absent from [ElevationSource.entries]: no door
 * (`--dem-source`, `demSource`, `vcSetElevationConfig`) can parse one, so a cyclist can never pick
 * the MNS as the ground they ride on — under a canopy it reads the treetops. These layers exist for
 * the bridge decks: [ElevationSource.IGN_LIDAR_HD]'s provider carries the [MNS] as its
 * [ElevationProvider.surfaceModel], and the enhancer's bridge-deck step (`DeckDetector`,
 * `DeckProfileBuilder` in `:gpx`) rides the MNS only where it has established that a deck stands
 * (`docs/archive/tasks/elevation-bridge-decks.md`, *Input 4* and E3).
 *
 * **No fallback, and no-data stays no-data.** A [provider] of a layer answers `NaN` wherever IGN has
 * no data — abroad, at sea, in a survey gap — never Mapterhorn and never 0 m: mixing a Mapterhorn
 * terrain into an MNS would invent a 0 m MNH at every coverage border. Bilinearly, one no-data pixel
 * among the four makes the point `NaN`. Callers therefore sample with
 * [ElevationProvider.setElevations] or [ElevationProvider.getElevation]; `getElevationsAlong` with
 * smoothing would spread a `NaN` over the whole window.
 *
 * [ElevationSource.IGN_LIDAR_HD] is [MNT] with the Mapterhorn fallback chained behind it; its
 * configuration is [MNT]'s, so the request, the rate and the attribution have one spelling.
 *
 * @property wmsLayer the WMS `LAYERS` name — an IGN identifier, not a wire id.
 */
enum class IgnLidarHdLayer(
    internal val wmsLayer: String,
) {
    /** Terrain model: the bare ground. The raster [ElevationSource.IGN_LIDAR_HD] reads. */
    MNT("IGNF_LIDAR-HD_MNT_ELEVATION.ELEVATIONGRIDCOVERAGE.WGS84G"),

    /** Surface model: the top of the first return — decks, but also treetops and roofs. */
    MNS("IGNF_LIDAR-HD_MNS_ELEVATION.ELEVATIONGRIDCOVERAGE.WGS84G"),

    /**
     * Height above ground, computed by IGN. Not bit-identical to `MNS − MNT` sampled here: IGN
     * derives it at 1 m before the WMS resamples, and the detector's thresholds were measured on
     * `MNS − MNT` (`docs/deck-strava-data/analysis/common.py`).
     */
    MNH("IGNF_LIDAR-HD_MNH_ELEVATION.ELEVATIONGRIDCOVERAGE.WGS84G"),
    ;

    /**
     * The zoom used when the caller names none.
     *
     * 256-px tiles at z15 are ~3.4 m per pixel across France: a road bench resolved to a couple of
     * samples, in a quarter-megabyte tile. z15 at 512 px would halve the pixel and quadruple the
     * download — a 100 km ride already costs ~45 MB at this setting, uncompressed (the WMS does not
     * gzip), and GPS error is metres anyway.
     */
    val defaultZoomLevel: Int get() = 15

    /** The provider configuration for this layer, at [zoomLevel] or [defaultZoomLevel]. */
    fun config(zoomLevel: Int? = null): ElevationProviderConfig =
        ElevationProviderConfig(
            zoomLevel = zoomLevel ?: defaultZoomLevel,
            tileSize = IGN_TILE_SIZE,
            tileUrlTemplate = ignWmsTemplate(wmsLayer, IGN_TILE_SIZE),
            encoding = TileEncoding.BIL32,
            // Half the Géoplateforme's published 40/s: the other half is for whatever else shares
            // the IP — the demo's own relief layer, a second run. Per provider: callers reading
            // several layers fetch them one after the other.
            maxRequestsPerSecond = 20,
            attribution =
                Attribution(
                    text = "© IGN — LiDAR HD $name, Licence Ouverte Etalab 2.0",
                    url = "https://geoservices.ign.fr/lidarhd",
                ),
        )
}

/**
 * A provider for this layer that answers `NaN` wherever IGN has no data — never a fallback, never
 * 0 m. See [IgnLidarHdLayer].
 *
 * [ElevationProvider.withFallback] refuses the result: no fallback for these layers, a hard
 * contract rather than a convention.
 *
 * @param fetcherFor the fetcher, given the layer's configuration — the bench's disk cache, the CLI's
 *   `DiskCachedElevation`. Their cache keys hash the query string, which names the layer, so the
 *   three layers never collide on disk.
 */
fun IgnLidarHdLayer.provider(
    zoomLevel: Int? = null,
    fetcherFor: (ElevationProviderConfig) -> suspend (String) -> RawTile = ::defaultTileFetcher,
): ElevationProvider {
    val config = config(zoomLevel)
    return ElevationProvider(config, fetcherFor(config), keepNoData = true)
}

internal const val IGN_TILE_SIZE = 256
internal const val IGN_WMS_URL = "https://data.geopf.fr/wms-r/wms"

/**
 * A GetMap request covering one XYZ tile, with `{bbox}` left for [TileManager] to fill.
 *
 * EPSG:4326 rather than EPSG:3857: the service accepts the latter but resamples it one row out of
 * two, which doubles the vertical gradient on every other row (measured in `leaflet-relief`, the
 * same service). Rows then run linearly in latitude instead of in Mercator y, a shift below
 * 0.005 px inside a z15 tile.
 */
internal fun ignWmsTemplate(
    layer: String,
    tileSize: Int,
): String =
    "$IGN_WMS_URL?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=$layer&STYLES=normal" +
        "&CRS=EPSG:4326&BBOX={bbox}&WIDTH=$tileSize&HEIGHT=$tileSize&FORMAT=image%2Fx-bil%3Bbits%3D32"
