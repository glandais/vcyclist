package io.github.glandais.api.service

import io.github.glandais.api.ApiConfig
import io.github.glandais.api.http.ApiProblem
import io.github.glandais.api.http.ProblemKind
import io.github.glandais.api.mapping.MapFraming
import io.github.glandais.api.mapping.TerrainMapPlan
import io.github.glandais.api.mapping.TileMapPlan
import io.github.glandais.api.mapping.invalidOption
import io.github.glandais.elevation.ElevationProvider
import io.github.glandais.engine.path.Path
import io.github.glandais.map.MapImage
import io.github.glandais.map.MissingTilePolicy
import io.github.glandais.map.SrtmMapProducer
import io.github.glandais.map.TileFetcher
import io.github.glandais.map.TileMapProducer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/*
 * The two map renderers of `:map`, behind the HTTP door. Both write a PNG **file**: it is rendered
 * into a temporary file under `cacheDir`, read back and deleted, whatever happens. Both block —
 * the tile renderer on its HTTP fetches, the terrain renderer in its own `runBlocking` around the
 * DEM batch — so they run on `Dispatchers.IO`, inside the compute gate's scope.
 */

/** A rendered PNG; [missingTiles] of [tileCount] could not be obtained (always 0 of 0 for terrain). */
class RenderedMap(
    val png: ByteArray,
    val missingTiles: Int,
    val tileCount: Int,
)

/** The tile cache of `/map/tiles`, under the server's scratch directory. */
fun ApiConfig.tileCacheDir(): File = File(cacheDir, "tiles")

/**
 * The server's tile URL, or 503 `map-tiles-unconfigured`: the client never names one, so without
 * `VCYCLIST_API_MAP_TILE_URL` there is no tile map at all.
 */
fun ApiConfig.requireTileUrl(): String =
    mapTileUrlTemplate
        ?: throw ApiProblem(
            ProblemKind.MAP_TILES_UNCONFIGURED,
            "This server has no tile source (VCYCLIST_API_MAP_TILE_URL); POST /api/v1/map/terrain needs none",
        )

/**
 * A zoom framing's image size follows from the tracks, so it can only be bounded once they are
 * parsed: framed here (bounds only, no pixel allocated) and refused past [ApiConfig.maxMapPixels].
 * The other two modes were bounded by the request mapping.
 */
fun checkTileFrame(
    plan: TileMapPlan,
    paths: List<Path>,
    config: ApiConfig,
) {
    val framing = plan.framing as? MapFraming.Zoom ?: return
    val frame = MapImage.ofZoom(paths, plan.margin, framing.zoom)
    if (frame.width > config.maxMapPixels || frame.height > config.maxMapPixels) {
        throw invalidOption(
            "/zoom",
            "zoom ${framing.zoom} frames these tracks in ${frame.width}x${frame.height} pixels, above the server's " +
                "${config.maxMapPixels}; choose a lower zoom, or maxSize",
        )
    }
}

/**
 * DEM tiles a terrain render of [paths] would read, estimated from its frame: the frame's extent
 * in tiles of the DEM's zoom (slippy-map tiles: `2^zoom` across, whatever their pixel size), and
 * never more than the samples the renderer takes. Without this bound, a large margin at a deep
 * DEM zoom would mean tens of thousands of tile downloads for one request.
 */
internal fun estimatedDemTiles(
    plan: TerrainMapPlan,
    paths: List<Path>,
): Long {
    val frame = MapImage.ofMaxSize(paths, plan.margin, plan.maxSize)
    val demZoom = plan.zoom ?: plan.source.defaultZoomLevel
    val scale = Math.scalb(1.0, demZoom - frame.zoom) / frame.mapSpace.tileSize
    val cols = ceil(max(frame.width, 1) * scale).toLong() + 1
    val rows = ceil(max(frame.height, 1) * scale).toLong() + 1
    val pixels = max(frame.width, 1).toLong() * max(frame.height, 1)
    return min(cols * rows, min(pixels, SrtmMapProducer.DEFAULT_MAX_SAMPLES.toLong()))
}

/** Refuses a terrain frame that would read more than [ApiConfig.maxMapDemTiles] DEM tiles. */
fun checkTerrainFrame(
    plan: TerrainMapPlan,
    paths: List<Path>,
    config: ApiConfig,
) {
    val tiles = estimatedDemTiles(plan, paths)
    if (tiles > config.maxMapDemTiles) {
        throw invalidOption(
            "/zoomLevel",
            "this frame covers about $tiles DEM tiles at zoom ${plan.zoom ?: plan.source.defaultZoomLevel}, above " +
                "the server's ${config.maxMapDemTiles}; lower zoomLevel, margin or maxSize",
        )
    }
}

/** Renders [plan] over the server's tiles, missing tiles painted grey and counted, never fatal. */
suspend fun renderTileMap(
    config: ApiConfig,
    fetcher: TileFetcher,
    paths: List<Path>,
    plan: TileMapPlan,
): RenderedMap {
    val url = config.requireTileUrl()
    return withContext(Dispatchers.IO) {
        val tiles = config.tileCacheDir().apply { mkdirs() }
        val producer = TileMapProducer(tiles, fetcher, MissingTilePolicy.SKIP)
        renderToBytes(config) { file ->
            val framing = plan.framing
            producer.createTileMap(
                file = file,
                paths = paths,
                urlPattern = url,
                margin = plan.margin,
                maxSize = (framing as? MapFraming.MaxSize)?.pixels,
                width = (framing as? MapFraming.Size)?.width,
                height = (framing as? MapFraming.Size)?.height,
                zoom = (framing as? MapFraming.Zoom)?.zoom,
                colors = plan.colors,
            )
        }
    }
}

/**
 * Renders [plan]'s terrain from [dem]. Call it under `ComputeGate.run`. `SrtmMapProducer` bridges
 * to the provider with its own `runBlocking`, which parks its thread while the tiles are fetched on `Dispatchers.IO`, which is why the
 * render itself runs there and not on the gate's `Dispatchers.Default`.
 */
suspend fun renderTerrainMap(
    config: ApiConfig,
    dem: ElevationProvider,
    paths: List<Path>,
    plan: TerrainMapPlan,
): RenderedMap =
    withContext(Dispatchers.IO) {
        renderToBytes(config) { file ->
            SrtmMapProducer(dem).createSrtmMap(file, paths, maxSize = plan.maxSize, margin = plan.margin)
        }
    }

/** Runs [render] into a fresh temporary PNG under `cacheDir`, returns its bytes, deletes it. */
private fun renderToBytes(
    config: ApiConfig,
    render: (File) -> MapImage,
): RenderedMap {
    Files.createDirectories(config.cacheDir.toPath())
    val file = Files.createTempFile(config.cacheDir.toPath(), "map-", ".png")
    try {
        val map = render(file.toFile())
        return RenderedMap(Files.readAllBytes(file), map.missingTileCount, map.tileCount)
    } finally {
        Files.deleteIfExists(file)
    }
}
