package io.github.glandais.elevation

/**
 * Which model an elevation was read from, as far as the library can tell.
 *
 * Mapterhorn is a fused product — IGN LiDAR HD subsampled in France, national models elsewhere,
 * Copernicus GLO-30 everywhere else — and publishes no footprint per source, so a Mapterhorn point
 * is classed by **where** it lies: inside [IgnLidarHdCoverage], where the bench measured it
 * (`elevation-bridge-decks`, R30), it is [MAPTERHORN_MEASURED]; elsewhere [MAPTERHORN], which may be
 * a 2 m national model or GLO-30 at 30 m and is unmeasured either way.
 *
 * @property id the wire form, on every door that reports a provenance
 * @property measured whether the corrections' precision was measured on this terrain
 */
enum class TerrainOrigin(
    val id: String,
    val measured: Boolean,
) {
    /** IGN's LiDAR HD terrain model (MNT). */
    IGN_LIDAR_HD("ign-lidar-hd", true),

    /** Mapterhorn inside metropolitan France and Corsica, where it serves LiDAR-derived terrain. */
    MAPTERHORN_MEASURED("mapterhorn-measured", true),

    /** Mapterhorn anywhere else: a national model or GLO-30, indistinguishable from here. */
    MAPTERHORN("mapterhorn", false),

    /** A tile source the catalogue does not know: a hand-built configuration, a WASI host's tiles. */
    OTHER("other", false),

    /** No source had data: the point reads 0 m (or `NaN` on a raw layer). */
    NONE("none", false),
    ;

    companion object {
        /** Every [id], in declaration order. */
        val ids: List<String> = entries.map { it.id }

        /**
         * The origin of a value [config] answered at [latitude], [longitude] (degrees): by its URL
         * template when it is the catalogue's, else by [declared] (see
         * [ElevationProvider.servingSource]), else [OTHER].
         */
        internal fun of(
            config: ElevationProviderConfig,
            declared: ElevationSource?,
            latitude: Double,
            longitude: Double,
        ): TerrainOrigin {
            val template = config.tileUrlTemplate
            val source =
                when {
                    IgnLidarHdLayer.entries.any { it.config(config.zoomLevel).tileUrlTemplate == template } -> ElevationSource.IGN_LIDAR_HD
                    template == ElevationSource.MAPTERHORN.config(config.zoomLevel).tileUrlTemplate -> ElevationSource.MAPTERHORN
                    else -> declared
                }
            return when (source) {
                ElevationSource.IGN_LIDAR_HD -> IGN_LIDAR_HD
                ElevationSource.MAPTERHORN -> if (IgnLidarHdCoverage.contains(latitude, longitude)) MAPTERHORN_MEASURED else MAPTERHORN
                null -> OTHER
            }
        }
    }
}

/**
 * One elevation and where it came from.
 *
 * @property afterError the value was read from a fallback because a tile of a source before it
 *   could not be fetched (not because that source has no data there)
 */
data class TerrainSample(
    val latitude: Double,
    val longitude: Double,
    val elevation: Double,
    val origin: TerrainOrigin,
    val afterError: Boolean,
)
