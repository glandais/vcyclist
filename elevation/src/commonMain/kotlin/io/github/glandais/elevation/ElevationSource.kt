package io.github.glandais.elevation

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.sinh

/**
 * The DEM sources every door can name, by their wire id.
 *
 * A catalogue rather than a free URL because a source is more than a URL: an encoding, a tile
 * geometry, a default zoom, an attribution, and what to fall back to where it has no data. The
 * doors take the [id] (`--dem-source`, `demSource`, `vcSetElevationConfig`'s `source`) and read
 * everything else from here, so no door spells a source's defaults.
 *
 * A caller with its own tile server still builds an [ElevationProviderConfig] by hand; this is the
 * list of sources the project knows, not a restriction.
 */
enum class ElevationSource(
    val id: String,
) {
    /**
     * Mapterhorn's Terrarium WebP tiles, worldwide. The default, and what every door used before
     * there was a choice.
     */
    MAPTERHORN("mapterhorn"),

    /**
     * IGN's **LiDAR HD terrain model** (MNT), metropolitan France and Corsica, gridded at 1 m and
     * vertically accurate to ~10 cm, served as BIL float32 by the Géoplateforme raster WMS.
     *
     * The terrain model, not the surface one (MNS): the MNS is the top of whatever stands on the
     * ground, trees and roofs included, and a cyclist rides under the canopy, not over it. One
     * consequence a cyclist will meet: a terrain model has **no bridges** — a viaduct samples the
     * valley floor below it. The 150 m smoother does not hide a 50 m dip; see
     * `docs/guides/elevation.md`.
     *
     * Falls back to [MAPTERHORN] wherever IGN has no data — abroad, at sea, in a survey gap — so a
     * ride crossing a border is not flattened to 0 m on the far side.
     */
    IGN_LIDAR_HD("ign-lidar-hd"),
    ;

    /** Where this source's no-data points are looked up instead, or `null` for none. */
    val fallback: ElevationSource?
        get() =
            when (this) {
                MAPTERHORN -> null
                IGN_LIDAR_HD -> MAPTERHORN
            }

    /** The zoom used when the caller names none. */
    val defaultZoomLevel: Int
        get() =
            when (this) {
                MAPTERHORN -> ElevationProviderConfig().zoomLevel
                // 256-px tiles at z15 are ~3.4 m per pixel across France: a road bench resolved
                // to a couple of samples, in a quarter-megabyte tile. z15 at 512 px would halve the
                // pixel and quadruple the download — a 100 km ride already costs ~45 MB at this
                // setting, uncompressed (the WMS does not gzip), and GPS error is metres anyway.
                IGN_LIDAR_HD -> 15
            }

    /** The provider configuration for this source, at [zoomLevel] or the source's default. */
    fun config(zoomLevel: Int?): ElevationProviderConfig =
        when (this) {
            MAPTERHORN -> ElevationProviderConfig(zoomLevel = zoomLevel ?: defaultZoomLevel)
            IGN_LIDAR_HD ->
                ElevationProviderConfig(
                    zoomLevel = zoomLevel ?: defaultZoomLevel,
                    tileSize = IGN_TILE_SIZE,
                    tileUrlTemplate = ignWmsTemplate(IGN_LIDAR_HD_MNT_LAYER, IGN_TILE_SIZE),
                    encoding = TileEncoding.BIL32,
                    // Half the Géoplateforme's published 40/s: the other half is for whatever
                    // else shares the IP — the demo's own relief layer, a second run.
                    maxRequestsPerSecond = 20,
                    attribution =
                        Attribution(
                            text = "© IGN — LiDAR HD MNT, Licence Ouverte Etalab 2.0",
                            url = "https://geoservices.ign.fr/lidarhd",
                        ),
                )
        }

    /** [config] at the source's default zoom. A separate overload because Java has no defaults. */
    fun config(): ElevationProviderConfig = config(null)

    companion object {
        /** The source a door uses when the caller names none. */
        val DEFAULT: ElevationSource = MAPTERHORN

        /** Every wire id, in declaration order — for help texts and error messages. */
        val ids: List<String> get() = entries.map { it.id }

        /** The source called [id]. Unknown ids are an error, as every unknown wire value is. */
        fun byId(id: String): ElevationSource =
            entries.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("Unknown DEM source '$id' — expected one of ${ids.joinToString()}")

        private const val IGN_TILE_SIZE = 256
        private const val IGN_WMS_URL = "https://data.geopf.fr/wms-r/wms"
        private const val IGN_LIDAR_HD_MNT_LAYER = "IGNF_LIDAR-HD_MNT_ELEVATION.ELEVATIONGRIDCOVERAGE.WGS84G"

        /**
         * A GetMap request covering one XYZ tile, with `{bbox}` left for [TileManager] to fill.
         *
         * EPSG:4326 rather than EPSG:3857: the service accepts the latter but resamples it one row
         * out of two, which doubles the vertical gradient on every other row (measured in
         * `leaflet-relief`, the same service). Rows then run linearly in latitude instead of in
         * Mercator y, a shift below 0.005 px inside a z15 tile.
         */
        private fun ignWmsTemplate(
            layer: String,
            tileSize: Int,
        ): String =
            "$IGN_WMS_URL?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=$layer&STYLES=normal" +
                "&CRS=EPSG:4326&BBOX={bbox}&WIDTH=$tileSize&HEIGHT=$tileSize&FORMAT=image%2Fx-bil%3Bbits%3D32"
    }
}

/**
 * The `{bbox}` of tile [tc] for a WMS 1.3.0 request in EPSG:4326: `south,west,north,east` — that
 * version orders the axes of EPSG:4326 latitude first.
 */
internal fun wmsBbox4326(tc: TileCoordinates): String {
    val tiles = (1 shl tc.z).toDouble()
    val west = tc.x / tiles * 360.0 - 180.0
    val east = (tc.x + 1) / tiles * 360.0 - 180.0
    return "${tileNorthLatitude(tc.y + 1, tiles)},$west,${tileNorthLatitude(tc.y, tiles)},$east"
}

/** Northern edge of tile row [y], in degrees: the inverse of the Web Mercator projection. */
private fun tileNorthLatitude(
    y: Int,
    tiles: Double,
): Double = atan(sinh(PI - 2.0 * PI * y / tiles)) * MathConstants.RAD_TO_DEG

/**
 * A provider for this source, with its [ElevationSource.fallback] chained behind it.
 *
 * [zoomLevel] applies to this source only: the fallback keeps its own default, since a zoom chosen
 * for 1 m LiDAR would ask Mapterhorn for tiles it does not publish.
 *
 * @param fetcherFor the fetcher of each tier, given its configuration — so a custom transport (the
 *   CLI's disk cache) decodes each source's bytes with that source's [TileEncoding].
 */
fun ElevationSource.provider(
    zoomLevel: Int? = null,
    fetcherFor: (ElevationProviderConfig) -> suspend (String) -> RawTile = ::defaultTileFetcher,
): ElevationProvider {
    val config = config(zoomLevel)
    val primary = ElevationProvider(config, fetcherFor(config))
    val next = fallback ?: return primary
    return primary.withFallback(next.provider(null, fetcherFor))
}
