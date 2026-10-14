package io.github.glandais.engine.path

/**
 * What one elevation correction of the enhancer (bridge decks, tunnels) did to a path. The wire
 * form is [id]: the JS, WASI and HTTP doors all spell a status with it, and nowhere else.
 */
enum class ElevationCorrectionStatus(
    val id: String,
) {
    /** The correction was switched off, or the elevations were not corrected from a DEM at all. */
    NOT_RUN("not-run"),

    /**
     * Bridge decks only: allowed, but the elevation provider carries no surface model — every
     * source but IGN LiDAR HD at its default zoom, and every WASI host.
     */
    UNAVAILABLE("unavailable"),

    /** Bridge decks only: every surface sample is no-data — the route is outside the LiDAR's coverage. */
    NO_COVERAGE("no-coverage"),

    /** The correction ran and found nothing to correct. */
    NONE_FOUND("none-found"),

    /** At least one stretch was corrected. */
    APPLIED("applied"),

    /** A tile could not be read: the path keeps the elevations it had before the correction. */
    FETCH_FAILED("fetch-failed"),
    ;

    companion object {
        /** Every [id], in declaration order. */
        val ids: List<String> = entries.map { it.id }

        /** The status spelled [id]; fails with the list of known ids otherwise. */
        fun byId(id: String): ElevationCorrectionStatus =
            entries.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("unknown elevation correction status '$id' (expected one of $ids)")
    }
}

/**
 * The report of the enhancer's elevation corrections (steps 3b and 3c), stamped on the path it
 * returns as [Path.elevationCorrections].
 *
 * @property bridgeDecks what the bridge-deck step did
 * @property bridgeDecksApplied the deck regions written back
 * @property bridgeDecksDropped deck regions detected but left on the valley floor (fewer than two
 *   surface anchors)
 * @property tunnels what the tunnel step did
 * @property tunnelsApplied the tunnels flattened between their portals
 * @property tunnelsDropped tunnels detected but refused because a bridge deck lies between their
 *   portals (the terrain model kept the bridge's pylons) — the intended behaviour, counted only
 * @property warnings one human-readable sentence per outcome that is knowingly wrong or depends on
 *   the network; empty in the normal case. The doors relay these strings, prefixed with the file or
 *   track, and never reword them.
 * @property terrain where the corrected elevations were read from, `null` when they were not
 *   corrected from a DEM
 * @property tunnelsOutOfScope tunnels detected but left as read because the terrain around them is
 *   not a measured model (the `measured-terrain` tunnel scope); each is located in [warnings]
 */
data class ElevationCorrections(
    val bridgeDecks: ElevationCorrectionStatus,
    val bridgeDecksApplied: Int,
    val bridgeDecksDropped: Int,
    val tunnels: ElevationCorrectionStatus,
    val tunnelsApplied: Int,
    val tunnelsDropped: Int,
    val warnings: List<String>,
    val terrain: TerrainProvenance? = null,
    val tunnelsOutOfScope: Int = 0,
)

/**
 * The length of the route read from each terrain model, in kilometres — see
 * `io.github.glandais.elevation.TerrainOrigin`. Measured on the DEM read's own 30 m grid, each point
 * standing for half of each segment around it, so the figures add up to the route's length and do
 * not move with later resampling.
 *
 * @property ignLidarHdKm read from IGN LiDAR HD
 * @property mapterhornMeasuredKm read from Mapterhorn in France, where it is LiDAR-derived and measured
 * @property mapterhornKm read from Mapterhorn elsewhere: a national model or GLO-30, unmeasured
 * @property otherKm read from a tile source the catalogue does not know (a WASI host, a custom one)
 * @property noDataKm no source had data: read as 0 m
 * @property fallbackAfterErrorKm the part of the above read from a fallback because a tile could not
 *   be fetched, not because it had no data
 * @property failedTiles distinct tiles that could not be fetched during the run
 */
data class TerrainProvenance(
    val ignLidarHdKm: Double,
    val mapterhornMeasuredKm: Double,
    val mapterhornKm: Double,
    val otherKm: Double,
    val noDataKm: Double,
    val fallbackAfterErrorKm: Double,
    val failedTiles: Int,
)
