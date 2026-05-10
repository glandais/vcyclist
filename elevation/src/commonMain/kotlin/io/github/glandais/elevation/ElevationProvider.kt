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
        step: Double = 10.0,
        minDistance: Double = 1.0,
        interpolation: Boolean = true,
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
