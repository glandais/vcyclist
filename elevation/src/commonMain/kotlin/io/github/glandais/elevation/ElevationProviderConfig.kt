package io.github.glandais.elevation

/** Attribution information for elevation data; mirrors the TS `Attribution` interface. */
data class Attribution(
    val text: String,
    val url: String? = null,
)

/**
 * Configuration for [ElevationProvider]. Defaults to mapterhorn.com Terrarium tiles at zoom 12,
 * 512-px tiles, with a 100-tile LRU cache.
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
)
