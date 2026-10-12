package io.github.glandais.elevation

/** Attribution information for elevation data, as the tile source requires it to be displayed. */
data class Attribution(
    val text: String,
    val url: String? = null,
)

/**
 * Configuration for [ElevationProvider]. Defaults to mapterhorn.com Terrarium tiles at zoom 12,
 * 512-px tiles, with a 100-tile LRU cache. [ElevationSource] holds the ready-made configurations.
 *
 * [tileUrlTemplate] takes `{z}`, `{x}` and `{y}` for an XYZ server, and `{bbox}` for a WMS: the
 * tile's footprint as `south,west,north,east` in degrees, the WMS 1.3.0 EPSG:4326 axis order.
 * [encoding] says how the fetched bytes encode elevation; it only steers the default fetcher, a
 * custom one decodes as it sees fit.
 */
data class ElevationProviderConfig(
    val zoomLevel: Int = 12,
    val cacheSize: Int = 100,
    val tileUrlTemplate: String = "https://tiles.mapterhorn.com/{z}/{x}/{y}.webp",
    val tileSize: Int = 512,
    val attribution: Attribution =
        Attribution(
            text = "Mapterhorn elevation data. See mapterhorn.com/attribution/ for details.",
            url = "https://mapterhorn.com/attribution/",
        ),
    val encoding: TileEncoding = TileEncoding.TERRARIUM,
    /**
     * Requests started per second by the default fetcher, `0` for no limit. The server's policy,
     * not a tuning knob: IGN answers 429 past 40 a second. See [pacedTileBytesFetcher].
     */
    val maxRequestsPerSecond: Int = 0,
)
