package io.github.glandais.api.dto

import io.ktor.openapi.JsonSchema
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * The DEM routes. Their keys are those of the JS elevation façade (`ElevationJsApi`), minus the
 * three that name a tile server — `tileUrlTemplate`, `tileSize`, `cacheSize`: a server that fetches
 * whatever URL the client hands it is an SSRF. The server chooses its sources; the client names one
 * by its id.
 */

/** A position, in degrees. */
@Serializable
@SerialName("LatLon")
data class LatLonDto(
    @JsonSchema.Description("Latitude, degrees, within the Web Mercator band (about ±85.05).")
    val latitude: Double,
    @JsonSchema.Description("Longitude, degrees, -180..180.")
    val longitude: Double,
)

/** What a DEM source requires to be displayed next to its data. */
@Serializable
@SerialName("Attribution")
data class AttributionDto(
    val text: String,
    val url: String?,
)

/** One DEM source the server knows. */
@Serializable
@SerialName("ElevationSource")
data class ElevationSourceDto(
    @JsonSchema.Description("The wire id, for `source` and `demSource`.")
    val id: String,
    @JsonSchema.Description("The zoom used when the request names none.")
    val defaultZoomLevel: Int,
    @JsonSchema.Description("The source asked where this one has no data, `null` for none.")
    val fallback: String?,
    @JsonSchema.Description("This source's attribution, then its fallback's.")
    val attribution: List<AttributionDto>,
)

/** `GET /elevation/point`. */
@Serializable
@SerialName("ElevationPointResponse")
data class ElevationPointResponse(
    val latitude: Double,
    val longitude: Double,
    @JsonSchema.Description("Metres above sea level.")
    val elevation: Double,
    @JsonSchema.Description("The DEM source that was asked.")
    val source: String,
    @JsonSchema.Description("The zoom level that was used.")
    val zoomLevel: Int,
    @JsonSchema.Description("What must be displayed with this elevation: the source's, then its fallback's.")
    val attribution: List<AttributionDto>,
)

/** `POST /elevation/points`: the DEM elevation of each point. */
@Serializable
@SerialName("ElevationPointsRequest")
data class ElevationPointsRequest(
    @JsonSchema.Description("The points, at most `GET /api/v1/defaults` `limits.maxElevationPoints`.")
    val points: List<LatLonDto>,
    @JsonSchema.Description("DEM source, one of `GET /api/v1/catalog` `elevationSources`. $SEE_DEFAULTS")
    val source: String? = null,
    @JsonSchema.Description("DEM zoom level, 0..15. Absent or null: the source's own default zoom.")
    val zoomLevel: Int? = null,
    @JsonSchema.Description("Bilinear interpolation rather than nearest sample. $SEE_DEFAULTS")
    val interpolation: Boolean? = null,
)

/** Moving-average smoothing of an elevation profile. */
@Serializable
@SerialName("SmoothingOptions")
data class SmoothingOptionsDto(
    @JsonSchema.Description("Smooth the profile. $SEE_DEFAULTS")
    val enabled: Boolean? = null,
    @JsonSchema.Description("Smoothing window, metres. $SEE_DEFAULTS")
    val windowSize: Double? = null,
)

/** Douglas-Peucker 3D simplification of an elevation profile. */
@Serializable
@SerialName("FilterOptions")
data class FilterOptionsDto(
    @JsonSchema.Description("Simplify the profile. $SEE_DEFAULTS")
    val enabled: Boolean? = null,
    @JsonSchema.Description("Simplification tolerance, metres. $SEE_DEFAULTS")
    val tolerance: Double? = null,
    @JsonSchema.Description("Elevation exaggeration of the simplification. $SEE_DEFAULTS")
    val zExaggeration: Double? = null,
)

/** `POST /elevation/along`: densify a polyline, then sample the DEM along it. */
@Serializable
@SerialName("ElevationAlongRequest")
data class ElevationAlongRequest(
    @JsonSchema.Description("The polyline, at least 2 points.")
    val points: List<LatLonDto>,
    @JsonSchema.Description("DEM source, one of `GET /api/v1/catalog` `elevationSources`. $SEE_DEFAULTS")
    val source: String? = null,
    @JsonSchema.Description("DEM zoom level, 0..15. Absent or null: the source's own default zoom.")
    val zoomLevel: Int? = null,
    @JsonSchema.Description("Densification step, metres, above 1. $SEE_DEFAULTS")
    val step: Double? = null,
    @JsonSchema.Description("Points closer than this to the last kept one are dropped, metres. $SEE_DEFAULTS")
    val minDistance: Double? = null,
    @JsonSchema.Description("Bilinear interpolation rather than nearest sample. $SEE_DEFAULTS")
    val interpolation: Boolean? = null,
    val smoothingOptions: SmoothingOptionsDto? = null,
    val filterOptions: FilterOptionsDto? = null,
)

/** A point with its DEM elevation. */
@Serializable
@SerialName("ElevationPoint")
data class ElevationPointDto(
    val latitude: Double,
    val longitude: Double,
    @JsonSchema.Description("Metres above sea level.")
    val elevation: Double,
)

/** `POST /elevation/points` and `POST /elevation/along`. */
@Serializable
@SerialName("ElevationPointsResponse")
data class ElevationPointsResponse(
    val points: List<ElevationPointDto>,
    @JsonSchema.Description("The DEM source that was asked.")
    val source: String,
    @JsonSchema.Description("The zoom level that was used.")
    val zoomLevel: Int,
    @JsonSchema.Description("What must be displayed with these elevations: the source's, then its fallback's.")
    val attribution: List<AttributionDto>,
)

/**
 * `GET /defaults` `elevation`: what the DEM routes apply when a field is absent. `zoomLevel` has no
 * single default — each source has its own, listed by `GET /elevation/sources`.
 */
@Serializable
@SerialName("ElevationDefaults")
data class ElevationDefaultsDto(
    val source: String,
    val step: Double,
    val minDistance: Double,
    val interpolation: Boolean,
    val smoothingOptions: SmoothingOptionsDto?,
    val filterOptions: FilterOptionsDto?,
)
