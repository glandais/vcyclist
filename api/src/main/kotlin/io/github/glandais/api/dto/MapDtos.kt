package io.github.glandais.api.dto

import io.github.glandais.elevation.ElevationFunctions
import io.ktor.openapi.JsonSchema
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The `request` part of `POST /map/tiles`. At most one framing mode — [maxSize]; or [width] and
 * [height] together; or [zoom] — as `ExportCommand` and `TileMapProducer` require; none at all
 * frames by the published `maxSize`. The tile server is the **server's**
 * (`VCYCLIST_API_MAP_TILE_URL`): no key names one, so a server never fetches a client's URL.
 */
@Serializable
@SerialName("TileMapRequest")
data class TileMapRequest(
    @JsonSchema.Description("Padding around the tracks, a ratio of their extent (0.1 = 10 %), finite and >= 0. $SEE_DEFAULTS")
    val margin: Double? = null,
    @JsonSchema.Description(
        "Framing: the deepest zoom at which neither dimension exceeds this many pixels. " +
            "Exclusive with width/height and zoom. At most the server's `limits.maxMapPixels`. $SEE_DEFAULTS",
    )
    val maxSize: Int? = null,
    @JsonSchema.Description("Framing: exact image width in pixels, with `height`. At most `limits.maxMapPixels`.")
    val width: Int? = null,
    @JsonSchema.Description("Framing: exact image height in pixels, with `width`. At most `limits.maxMapPixels`.")
    val height: Int? = null,
    @JsonSchema.Description(
        "Framing: an explicit tile zoom level; the size follows from the tracks and must stay within " +
            "`limits.maxMapPixels`.",
    )
    val zoom: Int? = null,
    @JsonSchema.Description("One colour per track, `#rrggbb`, cycled when there are more tracks. Absent: red.")
    val colors: List<String>? = null,
)

/**
 * The `request` part of `POST /map/terrain`: a hypsometric map coloured from the DEM, framed by
 * [maxSize] only, as `SrtmMapProducer` is.
 */
@Serializable
@SerialName("TerrainMapRequest")
data class TerrainMapRequest(
    @JsonSchema.Description("Largest image dimension, in pixels; at most `limits.maxMapPixels`. $SEE_DEFAULTS")
    val maxSize: Int? = null,
    @JsonSchema.Description("Padding around the tracks, a ratio of their extent, finite and >= 0. $SEE_DEFAULTS")
    val margin: Double? = null,
    @JsonSchema.Description("DEM source id (`GET /api/v1/elevation/sources`). $SEE_DEFAULTS")
    val source: String? = null,
    @JsonSchema.Description(
        "DEM zoom level, ${ElevationFunctions.MIN_ZOOM_LEVEL}..${ElevationFunctions.MAX_ZOOM_LEVEL}. " +
            "Absent: the source's own default. A frame covering more DEM tiles " +
            "than `limits.maxMapDemTiles` at this zoom is refused.",
    )
    val zoomLevel: Int? = null,
)

/**
 * `GET /defaults` `map`: what both map operations apply when a field is absent.
 *
 * @property maxSize the framing when the request names none
 */
@Serializable
@SerialName("MapDefaults")
data class MapDefaultsDto(
    val margin: Double,
    val maxSize: Int,
)
