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
     *
     * A fused product: Copernicus GLO-30 worldwide, national models where they exist. In France
     * that is IGN LiDAR HD itself, subsampled to the zoom asked for (served up to z17, which
     * [ElevationProvider] refuses), so its default z12 is not the native resolution there — see
     * `docs/guides/elevation.md`, *What the DEM costs*, and ledger row R30.
     */
    MAPTERHORN("mapterhorn"),

    /**
     * IGN's **LiDAR HD terrain model** (MNT), metropolitan France and Corsica, gridded at 1 m and
     * vertically accurate to ~10 cm, served as BIL float32 by the Géoplateforme raster WMS.
     *
     * The terrain model, not the surface one (MNS): the MNS is the top of whatever stands on the
     * ground, trees and roofs included, and a cyclist rides under the canopy, not over it. A terrain
     * model has **no bridges** — a viaduct samples the valley floor below it — so at its default
     * zoom (15) this source's [provider] also carries IGN's MNS as its
     * [ElevationProvider.surfaceModel]: the enhancer's bridge-deck step (pipeline step 3b) detects
     * each deck on it and rides the deck's LiDAR profile instead of the valley. At any other zoom,
     * and on WASI, whose host serves one layer only, the viaducts stay on the valley floor. Nor does
     * it see into tunnels: the tunnel step (3c) replaces the mountain with the line between the
     * portals, on every source and needing no surface model. See `docs/guides/elevation.md`,
     * `docs/tasks/elevation-bridge-decks.md` (E3, E4) and ledger rows R31 (decks), R32 (tunnels)
     * and R33 (the reported D+ they feed).
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
                IGN_LIDAR_HD -> IgnLidarHdLayer.MNT.defaultZoomLevel
            }

    /** The provider configuration for this source, at [zoomLevel] or the source's default. */
    fun config(zoomLevel: Int?): ElevationProviderConfig =
        when (this) {
            MAPTERHORN -> ElevationProviderConfig(zoomLevel = zoomLevel ?: defaultZoomLevel)
            // One spelling of the IGN request, rate and attribution: the raw MNT layer is the same
            // raster, and this source only adds the Mapterhorn fallback on top of it.
            IGN_LIDAR_HD -> IgnLidarHdLayer.MNT.config(zoomLevel ?: defaultZoomLevel)
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
 * [ElevationSource.IGN_LIDAR_HD] at its default zoom also carries IGN's MNS as its
 * [ElevationProvider.surfaceModel], fetched through the same [fetcherFor] (so a disk cache covers it
 * too): one more tile per terrain tile, read only by the enhancer's bridge-deck step.
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
    val withFallback = fallback?.let { primary.withFallback(it.provider(null, fetcherFor)) } ?: primary
    // Decks only at the zoom the detector was calibrated at: at z14 it found 13 of 21 bridges and
    // 19 stations off them (`elevation-bridge-decks.md`, E2, variant c).
    return if (this == ElevationSource.IGN_LIDAR_HD && config.zoomLevel == IgnLidarHdLayer.MNS.defaultZoomLevel) {
        withFallback.withSurfaceModel(IgnLidarHdLayer.MNS.provider(null, fetcherFor))
    } else {
        withFallback
    }
}
