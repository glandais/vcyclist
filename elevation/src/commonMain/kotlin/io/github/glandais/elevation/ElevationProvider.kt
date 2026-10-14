package io.github.glandais.elevation

/**
 * Public façade for elevation lookups. Wires [TileManager], [ElevationCalculator] and
 * [BatchCalculator] together from a [ElevationProviderConfig].
 *
 * The [fetcher] is injectable for tests and custom transports; by default it is
 * [defaultTileFetcher] for the configuration's [TileEncoding] (HTTP + decode for the active
 * target).
 *
 * Where the tiles have no data, a provider built [withFallback] asks another one; without, the
 * point reads 0 m. See [ElevationCalculator]. The one exception is a raw-layer provider built by
 * [IgnLidarHdLayer.provider], which answers `NaN` there.
 *
 * A provider with a fallback also reads from it the points of a tile it **cannot fetch** — an HTTP
 * error after the pacer's retries, a network failure, a body that does not decode — instead of
 * failing: such a tile is answered as no data for a minute, then asked again, and recorded in the
 * caller's [TileFailureLog] when there is one. Without a fallback the error propagates, as it
 * always did. A cancellation always propagates.
 */
class ElevationProvider private constructor(
    val config: ElevationProviderConfig,
    private val fetcher: suspend (String) -> RawTile,
    /** The provider no-data points are asked of, or `null`. */
    val fallback: ElevationProvider?,
    /** With no [fallback]: answer `NaN` on no-data instead of 0 m. Raw IGN layers only. */
    private val keepNoData: Boolean,
    /**
     * A raw surface model (MNS) aligned with this terrain, or `null`. Read by one consumer only:
     * the enhancer's bridge-deck step, which replaces each deck it detects by its LiDAR profile
     * (`docs/archive/tasks/elevation-bridge-decks.md`, E3). `null` means no decks: the terrain is ridden as
     * is, viaducts included.
     *
     * [ElevationSource.IGN_LIDAR_HD]'s [provider] carries IGN's MNS at its default zoom (15), the
     * only zoom the detector was calibrated at; every other provider — Mapterhorn, IGN at another
     * zoom, any hand-built one, hence the WASI host's — carries none. Attach or detach one with
     * [withSurfaceModel]. Never consulted for an elevation lookup: [getElevation] and
     * [setElevations] read the terrain only.
     */
    val surfaceModel: ElevationProvider?,
    /**
     * The catalogued source these tiles are, when their URL template does not say it — a WASI
     * host serving them through `fetch_tile`. Read only to tell a [TerrainOrigin]. See [servingSource].
     */
    val declaredSource: ElevationSource?,
) {
    constructor(
        config: ElevationProviderConfig = ElevationProviderConfig(),
        fetcher: suspend (String) -> RawTile = defaultTileFetcher(config),
    ) : this(config, fetcher, null, false, null, null)

    /** A provider whose no-data points read `NaN`: see [IgnLidarHdLayer.provider], its only caller. */
    internal constructor(
        config: ElevationProviderConfig,
        fetcher: suspend (String) -> RawTile,
        keepNoData: Boolean,
    ) : this(config, fetcher, null, keepNoData, null, null)

    init {
        require(ElevationFunctions.isValidZoomLevel(config.zoomLevel)) {
            "Invalid zoom level: ${config.zoomLevel}. Must be an integer between " +
                "${ElevationFunctions.MIN_ZOOM_LEVEL} and ${ElevationFunctions.MAX_ZOOM_LEVEL}"
        }
        require(config.cacheSize > 0) {
            "Invalid cache size: ${config.cacheSize}. Must be a positive integer"
        }
        require(config.tileSize > 0 && (config.tileSize and (config.tileSize - 1)) == 0) {
            "Invalid tile size: ${config.tileSize}. Must be a positive power of 2"
        }
        require(config.maxRequestsPerSecond >= 0) {
            "Invalid maxRequestsPerSecond: ${config.maxRequestsPerSecond}. Must be 0 (no limit) or positive"
        }
    }

    private val tileManager =
        TileManager(config.tileUrlTemplate, config.cacheSize, fetcher).apply {
            // With a fallback, a tile that cannot be read is no data, which the fallback reads.
            if (fallback != null) unreadable = UnreadableTiles(config.tileUrlTemplate, config.tileSize)
        }
    private val calculator =
        ElevationCalculator(
            tileManager,
            config.tileSize,
            when {
                fallback != null -> { coords, interpolation -> fallback.getElevation(coords.latitude, coords.longitude, interpolation) }
                keepNoData -> { _, _ -> Double.NaN }
                else -> null
            },
        )
    private val batchCalculator = BatchCalculator(calculator)

    /**
     * This provider, with [other] answering wherever its own tiles have no data.
     *
     * A new provider with an empty cache, not a mutation: a provider is shared across coroutines,
     * and growing a fallback under a running batch would change answers half-way through it.
     *
     * @throws IllegalArgumentException on a raw-layer provider ([IgnLidarHdLayer.provider]): its
     *   no-data must stay `NaN`, and a fallback under an MNS would invent a 0 m MNH at every coverage
     *   border.
     */
    fun withFallback(other: ElevationProvider): ElevationProvider {
        require(!keepNoData) { "a raw IGN LiDAR HD layer takes no fallback: its no-data must stay NaN" }
        return ElevationProvider(config, fetcher, other, false, surfaceModel, declaredSource)
    }

    /**
     * This provider, carrying [surface] as its [surfaceModel] — or none, when [surface] is `null`
     * (the bench's "without decks" column, or a caller that wants the bare terrain).
     *
     * A new provider with an empty cache, like [withFallback]; the fallback is kept.
     *
     * @throws IllegalArgumentException when [surface] is not a raw layer ([IgnLidarHdLayer.provider]):
     *   the deck step reads its no-data as `NaN`, and a surface with a fallback would invent decks
     *   at every coverage border; or when this provider is itself a raw layer, which is a surface,
     *   not a terrain that carries one.
     */
    fun withSurfaceModel(surface: ElevationProvider?): ElevationProvider {
        require(surface == null || surface.keepNoData) { "a surface model must keep its no-data as NaN" }
        require(!keepNoData) { "a raw IGN LiDAR HD layer carries no surface model" }
        return ElevationProvider(config, fetcher, fallback, false, surface, declaredSource)
    }

    /**
     * This provider, saying its tiles are [source]'s although its URL template is not the catalogue's
     * — a WASI host's `host://…`, a mirror of Mapterhorn. Only the [TerrainOrigin] of each value
     * changes: the tunnel step's `measured-terrain` scope trusts a declared IGN or Mapterhorn as it
     * trusts the catalogue's own. `null` takes the declaration back. A new provider with an empty
     * cache, like [withFallback].
     */
    fun servingSource(source: ElevationSource?): ElevationProvider =
        ElevationProvider(config, fetcher, fallback, keepNoData, surfaceModel, source)

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

    /**
     * Like [setElevations], with where each value came from: this provider's own tiles, or which of
     * its fallbacks, or none (0 m, or `NaN` on a raw layer). See [TerrainOrigin] for what can be
     * told about a Mapterhorn point. The values are the ones [setElevations] returns.
     */
    suspend fun setElevationsWithOrigin(
        coordinates: List<Coordinates>,
        interpolation: Boolean = true,
    ): List<TerrainSample> = batchCalculator.mapByTile(coordinates, config.zoomLevel) { sample(it, interpolation) }

    /** One point through the chain: this provider's tiles, then the fallback's, recursively. */
    private suspend fun sample(
        coords: Coordinates,
        interpolation: Boolean,
    ): TerrainSample {
        val flag = ElevationCalculator.UnreadableFlag()
        val own = calculator.readOwn(coords, config.zoomLevel, interpolation, flag)
        if (!own.isNaN()) {
            val origin = TerrainOrigin.of(config, declaredSource, coords.latitude, coords.longitude)
            return TerrainSample(coords.latitude, coords.longitude, own, origin, false)
        }
        val next = fallback?.sample(coords, interpolation)
        if (next != null) return if (flag.met) next.copy(afterError = true) else next
        return TerrainSample(coords.latitude, coords.longitude, if (keepNoData) Double.NaN else 0.0, TerrainOrigin.NONE, flag.met)
    }

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
 * The fetcher an [ElevationProvider] uses when given none: [fetchAndDecodeTile] for Terrarium
 * images, [fetchTileBytes] then [decodeBil32Tile] for BIL — the latter through
 * [pacedTileBytesFetcher], at the configuration's [ElevationProviderConfig.maxRequestsPerSecond].
 *
 * Terrarium keeps its one-hop path unless the configuration asks for pacing: Mapterhorn has never
 * rate-limited this library, and pacing would cost it the shortcut `fetchAndDecodeTile` exists for.
 */
fun defaultTileFetcher(config: ElevationProviderConfig): suspend (String) -> RawTile {
    if (config.encoding == TileEncoding.TERRARIUM && config.maxRequestsPerSecond == 0) return ::fetchAndDecodeTile
    val bytes = pacedTileBytesFetcher(config.maxRequestsPerSecond)
    return when (config.encoding) {
        TileEncoding.TERRARIUM -> { url -> decodeTileBytes(bytes(url), url) }
        TileEncoding.BIL32 -> { url -> decodeBil32Tile(bytes(url), config.tileSize, url) }
    }
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

    /** Points closer than this to the last kept point are dropped, in metres. */
    const val MIN_DISTANCE_M: Double = 1.0

    /** Bilinear interpolation between DEM samples rather than nearest-neighbour. */
    const val INTERPOLATION: Boolean = true
}
