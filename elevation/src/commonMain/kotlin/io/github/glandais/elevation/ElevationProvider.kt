package io.github.glandais.elevation

/**
 * Public façade for elevation lookups. Wires [TileManager], [ElevationCalculator] and
 * [BatchCalculator] together from a [ElevationProviderConfig].
 *
 * The [fetcher] is injectable for tests; production code uses [fetchAndDecodeTile] by default
 * (HTTP + image decode for the active target).
 */
class ElevationProvider(
    val config: ElevationProviderConfig = ElevationProviderConfig(),
    fetcher: suspend (String) -> RawTile = ::fetchAndDecodeTile,
) {
    init {
        require(config.zoomLevel in 0..15) {
            "Invalid zoom level: ${config.zoomLevel}. Must be an integer between 0 and 15"
        }
        require(config.cacheSize > 0) {
            "Invalid cache size: ${config.cacheSize}. Must be a positive integer"
        }
        require(config.tileSize > 0 && (config.tileSize and (config.tileSize - 1)) == 0) {
            "Invalid tile size: ${config.tileSize}. Must be a positive power of 2"
        }
    }

    private val tileManager = TileManager(config.tileUrlTemplate, config.cacheSize, fetcher)
    private val calculator = ElevationCalculator(tileManager, config.tileSize)
    private val batchCalculator = BatchCalculator(calculator)

    val attribution: Attribution get() = config.attribution

    suspend fun getElevation(
        latitude: Double,
        longitude: Double,
        interpolation: Boolean = true,
    ): Double = calculator.getElevation(LatLon(latitude, longitude), config.zoomLevel, interpolation)

    suspend fun setElevations(
        coordinates: List<Coordinates>,
        interpolation: Boolean = true,
    ): List<CoordinatesElevation> = batchCalculator.setElevations(coordinates, config.zoomLevel, interpolation)

    suspend fun getElevationsAlong(
        path: List<Coordinates>,
        step: Double = ElevationDefaults.STEP_M,
        minDistance: Double = ElevationDefaults.MIN_DISTANCE_M,
        interpolation: Boolean = ElevationDefaults.INTERPOLATION,
        smoothingOptions: SmoothingOptions? = null,
        filterOptions: FilterOptions? = null,
    ): List<CoordinatesElevation> =
        batchCalculator.getElevationsAlong(
            path,
            config.zoomLevel,
            step,
            minDistance,
            interpolation,
            smoothingOptions,
            filterOptions,
        )
}

/**
 * The sampling defaults of [ElevationProvider.getElevationsAlong], in `commonMain` so that every
 * door reads them instead of writing them out again.
 *
 * They were literals in the signature and again in `ElevationJsApi`, which is the shape of drift
 * that once had the façades defending 250 W against the CLI's 280 W: the two spellings agree until
 * somebody changes one. The engine's own catalogues — `PowerModel`, `GpxPowerSource` — work the
 * same way, and `DoorDefaultsTest` exists to fail a reader that spells a default.
 */
object ElevationDefaults {
    /** Densification step along the path, in metres. */
    const val STEP_M: Double = 10.0

    /** Points closer than this to their predecessor are dropped, in metres. */
    const val MIN_DISTANCE_M: Double = 1.0

    /** Bilinear interpolation between DEM samples rather than nearest-neighbour. */
    const val INTERPOLATION: Boolean = true
}
