package io.github.glandais.api.mapping

import io.github.glandais.api.dto.MapDefaultsDto
import io.github.glandais.api.dto.TerrainMapRequest
import io.github.glandais.api.dto.TileMapRequest
import io.github.glandais.api.http.ApiProblem
import io.github.glandais.api.http.FieldError
import io.github.glandais.api.http.ProblemKind
import io.github.glandais.elevation.ElevationSource
import io.github.glandais.map.MapSpace
import io.github.glandais.map.TileMapProducer
import java.awt.Color

/*
 * The map routes: request DTOs → render plans, every fallback read off [HttpDefaults]
 * (`MAP_MARGIN`, `MAP_MAX_SIZE`) or `TileMapProducer.DEFAULT_TRACK_COLOR`. Everything here is
 * checked before the GPX is parsed; what depends on the tracks (a zoom framing's size, a terrain
 * frame's DEM tiles) is checked in `MapService`, after the parse and before any render.
 */

/** How a tile map is framed — exactly one of `TileMapProducer.createTileMap`'s three modes. */
sealed interface MapFraming {
    /** The deepest zoom at which neither dimension exceeds [pixels]. */
    data class MaxSize(
        val pixels: Int,
    ) : MapFraming

    /** An image of exactly [width] × [height]. */
    data class Size(
        val width: Int,
        val height: Int,
    ) : MapFraming

    /** An explicit tile [zoom]; the size follows from the tracks. */
    data class Zoom(
        val zoom: Int,
    ) : MapFraming
}

/** A validated `POST /map/tiles` request. */
data class TileMapPlan(
    val margin: Double,
    val framing: MapFraming,
    val colors: List<Color>,
)

/** A validated `POST /map/terrain` request; [zoom] `null` is the source's default. */
data class TerrainMapPlan(
    val margin: Double,
    val maxSize: Int,
    val source: ElevationSource,
    val zoom: Int?,
)

private val HEX_COLOR = Regex("^#[0-9a-fA-F]{6}$")

/** [margin], or [HttpDefaults.MAP_MARGIN]: finite and ≥ 0. */
private fun mapMargin(margin: Double?): Double {
    if (margin == null) return HttpDefaults.MAP_MARGIN
    if (!margin.isFinite() || margin < 0.0) throw invalidOption("/margin", "must be finite and >= 0, was $margin")
    return margin
}

/** A pixel size in `1..maxPixels` (the server's `maxMapPixels`). */
private fun pixels(
    value: Int,
    pointer: String,
    maxPixels: Int,
): Int {
    if (value !in 1..maxPixels) throw invalidOption(pointer, "must be in 1..$maxPixels pixels, was $value")
    return value
}

/** A `#rrggbb` colour. */
private fun color(
    value: String,
    pointer: String,
): Color {
    if (!HEX_COLOR.matches(value)) throw invalidOption(pointer, "expected #rrggbb, was '$value'")
    return Color(value.substring(1).toInt(16))
}

/**
 * The tile-map plan of this request. At most one framing mode (the precedence of `ExportCommand`
 * is not copied: naming two is a contradiction, not a priority), [HttpDefaults.MAP_MAX_SIZE]
 * when none; `width` and `height` go together.
 */
fun TileMapRequest?.toTileMapPlan(maxPixels: Int): TileMapPlan {
    val r = this ?: TileMapRequest()
    val given =
        listOfNotNull(
            r.maxSize?.let { "/maxSize" },
            if (r.width != null || r.height != null) "/width" else null,
            r.zoom?.let { "/zoom" },
        )
    if (given.size > 1) {
        val message = "choose one framing mode: maxSize, width+height, or zoom"
        throw ApiProblem(
            ProblemKind.INVALID_OPTION,
            "${given.joinToString(", ")}: $message",
            errors = given.map { FieldError(it, message) },
        )
    }
    val framing =
        when {
            r.zoom != null -> {
                if (r.zoom !in 0..MapSpace.MAX_ZOOM) {
                    throw invalidOption("/zoom", "must be in 0..${MapSpace.MAX_ZOOM}, was ${r.zoom}")
                }
                MapFraming.Zoom(r.zoom)
            }

            r.width != null || r.height != null -> {
                val width = r.width ?: throw invalidOption("/width", "required alongside height")
                val height = r.height ?: throw invalidOption("/height", "required alongside width")
                MapFraming.Size(pixels(width, "/width", maxPixels), pixels(height, "/height", maxPixels))
            }

            else -> MapFraming.MaxSize(pixels(r.maxSize ?: HttpDefaults.MAP_MAX_SIZE, "/maxSize", maxPixels))
        }
    val colors =
        when (val given = r.colors) {
            null -> listOf(TileMapProducer.DEFAULT_TRACK_COLOR)
            else -> {
                if (given.isEmpty()) throw invalidOption("/colors", "at least one colour, or omit the key")
                given.mapIndexed { i, c -> color(c, "/colors/$i") }
            }
        }
    return TileMapPlan(margin = mapMargin(r.margin), framing = framing, colors = colors)
}

/** The terrain-map plan of this request; source and zoom through the DEM routes' readers. */
fun TerrainMapRequest?.toTerrainMapPlan(maxPixels: Int): TerrainMapPlan {
    val r = this ?: TerrainMapRequest()
    return TerrainMapPlan(
        margin = mapMargin(r.margin),
        maxSize = pixels(r.maxSize ?: HttpDefaults.MAP_MAX_SIZE, "/maxSize", maxPixels),
        source = elevationSource(r.source, "/source"),
        zoom = elevationZoom(r.zoomLevel, "/zoomLevel"),
    )
}

/** `GET /defaults` `map`. */
fun mapDefaultsDto(): MapDefaultsDto = MapDefaultsDto(margin = HttpDefaults.MAP_MARGIN, maxSize = HttpDefaults.MAP_MAX_SIZE)
