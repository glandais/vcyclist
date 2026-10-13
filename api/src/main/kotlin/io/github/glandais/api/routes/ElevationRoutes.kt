package io.github.glandais.api.routes

import io.github.glandais.api.ApiConfig
import io.github.glandais.api.apiServices
import io.github.glandais.api.dto.ElevationAlongRequest
import io.github.glandais.api.dto.ElevationPointDto
import io.github.glandais.api.dto.ElevationPointResponse
import io.github.glandais.api.dto.ElevationPointsRequest
import io.github.glandais.api.dto.ElevationPointsResponse
import io.github.glandais.api.dto.ElevationSourceDto
import io.github.glandais.api.http.ApiProblem
import io.github.glandais.api.http.ProblemKind
import io.github.glandais.api.http.queryBoolean
import io.github.glandais.api.http.queryDouble
import io.github.glandais.api.http.queryInt
import io.github.glandais.api.http.queryString
import io.github.glandais.api.http.receiveJson
import io.github.glandais.api.http.requireOnlyQuery
import io.github.glandais.api.http.requireQuery
import io.github.glandais.api.mapping.attributions
import io.github.glandais.api.mapping.elevationSource
import io.github.glandais.api.mapping.elevationZoom
import io.github.glandais.api.mapping.latLon
import io.github.glandais.api.mapping.toDto
import io.github.glandais.api.mapping.toFilterOptions
import io.github.glandais.api.mapping.toLatLons
import io.github.glandais.api.mapping.toSmoothingOptions
import io.github.glandais.api.openapi.problemResponses
import io.github.glandais.elevation.CoordinatesElevation
import io.github.glandais.elevation.Distance
import io.github.glandais.elevation.ElevationDefaults
import io.github.glandais.elevation.ElevationSource
import io.github.glandais.elevation.LatLon
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.GenericElement
import io.ktor.openapi.JsonSchema
import io.ktor.openapi.JsonType
import io.ktor.openapi.jsonSchema
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post

private const val TAG = "elevation"

/** Query parameters of `GET /elevation/point`. */
private object PointQuery {
    const val LAT = "lat"
    const val LON = "lon"
    const val SOURCE = "source"
    const val ZOOM_LEVEL = "zoomLevel"
    const val INTERPOLATION = "interpolation"
}

/** The problems a DEM lookup can answer, besides the request's own. */
private val DEM_PROBLEMS: Array<ProblemKind> =
    arrayOf(ProblemKind.DEM_UNAVAILABLE, ProblemKind.DEM_RATE_LIMITED, ProblemKind.TIMEOUT)

/** The problems of a JSON-bodied elevation operation. */
private val JSON_BODY_PROBLEMS: Array<ProblemKind> =
    arrayOf(
        ProblemKind.INVALID_BODY,
        ProblemKind.UNKNOWN_KEY,
        ProblemKind.INVALID_OPTION,
        ProblemKind.PAYLOAD_TOO_LARGE,
        ProblemKind.TOO_MANY_POINTS,
        ProblemKind.UNSUPPORTED_MEDIA_TYPE,
    )

/**
 * `GET /elevation/sources`, `GET /elevation/point`, `POST /elevation/points`,
 * `POST /elevation/along` — the DEM, queried directly.
 *
 * Every lookup goes through the server's [io.github.glandais.api.service.ElevationGateway] — one
 * provider per `(source, zoom)`, its tile cache shared — and under the compute gate, so it is
 * bounded in concurrency and answered within the request timeout. The client names a source by
 * its id and never a tile server: no `tileUrlTemplate`, `tileSize` or `cacheSize` key exists here,
 * so the strict decoder refuses them as unknown keys (a server fetching a client's URL is an SSRF).
 * Every answer carries the attribution its data requires, the fallback's included.
 */
fun Route.elevationRoutes() {
    get("elevation/sources") {
        call.requireOnlyQuery()
        call.respond(ElevationSource.entries.map { it.toDto() })
    }.describe {
        operationId = "listElevationSources"
        summary = "DEM sources"
        description =
            "The DEM sources this server queries, by the id `source` and `demSource` take, with their " +
            "default zoom, their fallback where they have no data, and the attribution their data requires."
        tag(TAG)
        responses {
            HttpStatusCode.OK {
                description = "The sources"
                schema = jsonSchema<List<ElevationSourceDto>>()
            }
            problemResponses(ProblemKind.UNKNOWN_KEY)
        }
    }

    get("elevation/point") {
        val services = call.apiServices
        call.requireOnlyQuery(PointQuery.LAT, PointQuery.LON, PointQuery.SOURCE, PointQuery.ZOOM_LEVEL, PointQuery.INTERPOLATION)
        val latitude = call.requireQuery(PointQuery.LAT) { queryDouble(it) }
        val longitude = call.requireQuery(PointQuery.LON) { queryDouble(it) }
        val point = latLon(latitude, longitude, "?${PointQuery.LAT}", "?${PointQuery.LON}")
        val source = elevationSource(call.queryString(PointQuery.SOURCE), "?${PointQuery.SOURCE}")
        val zoom = elevationZoom(call.queryInt(PointQuery.ZOOM_LEVEL), "?${PointQuery.ZOOM_LEVEL}")
        val interpolation = call.queryBoolean(PointQuery.INTERPOLATION) ?: ElevationDefaults.INTERPOLATION
        val elevation =
            services.computeGate.run {
                services.deps.elevation.withProvider(source, zoom) {
                    it.getElevation(point.latitude, point.longitude, interpolation)
                }
            }
        call.respond(
            ElevationPointResponse(
                latitude = point.latitude,
                longitude = point.longitude,
                elevation = elevation,
                source = source.id,
                zoomLevel = zoom ?: source.defaultZoomLevel,
                attribution = source.attributions(zoom),
            ),
        )
    }.describe {
        operationId = "getElevation"
        summary = "DEM elevation of one point"
        description =
            "The elevation of one position, read from the DEM. Every absent parameter takes the default " +
            "published by `GET /api/v1/defaults` (`elevation`); the zoom is the source's own."
        tag(TAG)
        parameters {
            query(PointQuery.LAT) {
                description = "Latitude, degrees, within the Web Mercator band (about ±85.05)."
                required = true
                schema = JsonSchema(type = JsonType.NUMBER)
            }
            query(PointQuery.LON) {
                description = "Longitude, degrees, -180..180."
                required = true
                schema = JsonSchema(type = JsonType.NUMBER)
            }
            query(PointQuery.SOURCE) {
                description = "DEM source, one of `GET /api/v1/catalog` `elevationSources`. Default `${ElevationSource.DEFAULT.id}`."
                schema = JsonSchema(type = JsonType.STRING, enum = ElevationSource.ids.map { GenericElement(it) })
            }
            query(PointQuery.ZOOM_LEVEL) {
                description = "DEM zoom level, 0..15. Absent: the source's own default zoom."
                schema = JsonSchema(type = JsonType.INTEGER, minimum = 0.0, maximum = 15.0)
            }
            query(PointQuery.INTERPOLATION) {
                description = "Bilinear interpolation rather than nearest sample. Default `${ElevationDefaults.INTERPOLATION}`."
                schema = JsonSchema(type = JsonType.BOOLEAN, default = GenericElement(ElevationDefaults.INTERPOLATION))
            }
        }
        responses {
            HttpStatusCode.OK {
                description = "The elevation, with its attribution"
                schema = jsonSchema<ElevationPointResponse>()
            }
            problemResponses(ProblemKind.UNKNOWN_KEY, ProblemKind.INVALID_OPTION, *DEM_PROBLEMS)
        }
    }

    post("elevation/points") {
        val services = call.apiServices
        call.requireOnlyQuery()
        val request = call.receiveJson(ElevationPointsRequest.serializer())
        requirePointCount(request.points.size, services.config)
        val points = request.points.toLatLons()
        val source = elevationSource(request.source, "/source")
        val zoom = elevationZoom(request.zoomLevel, "/zoomLevel")
        val interpolation = request.interpolation ?: ElevationDefaults.INTERPOLATION
        val result =
            services.computeGate.run {
                services.deps.elevation.withProvider(source, zoom) { it.setElevations(points, interpolation) }
            }
        call.respond(result.toResponse(source, zoom))
    }.describe {
        operationId = "getElevations"
        summary = "DEM elevation of several points"
        description =
            "The elevation of each position, in order. At most `limits.maxElevationPoints` points " +
            "(`GET /api/v1/defaults`). Every absent field takes the default published there (`elevation`)."
        tag(TAG)
        requestBody {
            required = true
            ContentType.Application.Json {
                schema = jsonSchema<ElevationPointsRequest>()
            }
        }
        responses {
            HttpStatusCode.OK {
                description = "The elevations, with their attribution"
                schema = jsonSchema<ElevationPointsResponse>()
            }
            problemResponses(*JSON_BODY_PROBLEMS, *DEM_PROBLEMS)
        }
    }

    post("elevation/along") {
        val services = call.apiServices
        call.requireOnlyQuery()
        val request = call.receiveJson(ElevationAlongRequest.serializer())
        requirePointCount(request.points.size, services.config)
        val points = request.points.toLatLons()
        val source = elevationSource(request.source, "/source")
        val zoom = elevationZoom(request.zoomLevel, "/zoomLevel")
        val step = request.step ?: ElevationDefaults.STEP_M
        val minDistance = request.minDistance ?: ElevationDefaults.MIN_DISTANCE_M
        val interpolation = request.interpolation ?: ElevationDefaults.INTERPOLATION
        val smoothing = request.smoothingOptions.toSmoothingOptions()
        val filter = request.filterOptions.toFilterOptions()
        // The densified polyline is what reaches the DEM: bound IT, or a two-point request spanning
        // a continent at a 2 m step would cost a million lookups. The step's own domain (> 1 m) and
        // the two-point minimum are the library's `require`s, answered `invalid-option`.
        if (step > 0.0) requirePointCount(estimatedSamples(points, step), services.config, "densified at step $step m")
        val result =
            services.computeGate.run {
                services.deps.elevation.withProvider(source, zoom) {
                    it.getElevationsAlong(points, step, minDistance, interpolation, smoothing, filter)
                }
            }
        call.respond(result.toResponse(source, zoom))
    }.describe {
        operationId = "getElevationsAlong"
        summary = "DEM elevation profile along a polyline"
        description =
            "Densifies the polyline every `step` metres, reads the DEM at each point, then optionally " +
            "smooths and simplifies the profile. The polyline needs at least 2 points, and the densified " +
            "one at most `limits.maxElevationPoints` (`GET /api/v1/defaults`). Every absent field takes the " +
            "default published there (`elevation`). No tile server can be named: the server chooses its sources."
        tag(TAG)
        requestBody {
            required = true
            ContentType.Application.Json {
                schema = jsonSchema<ElevationAlongRequest>()
            }
        }
        responses {
            HttpStatusCode.OK {
                description = "The profile, with its attribution"
                schema = jsonSchema<ElevationPointsResponse>()
            }
            problemResponses(*JSON_BODY_PROBLEMS, *DEM_PROBLEMS)
        }
    }
}

/** `too-many-points` when [count] exceeds the server's `maxElevationPoints`. */
private fun requirePointCount(
    count: Int,
    config: ApiConfig,
    what: String = "given",
) {
    if (count > config.maxElevationPoints) {
        throw ApiProblem(
            ProblemKind.TOO_MANY_POINTS,
            "$count points $what, more than this server's limit of ${config.maxElevationPoints}",
        )
    }
}

/** An upper bound on the points [points] densifies into at [step] metres. */
private fun estimatedSamples(
    points: List<LatLon>,
    step: Double,
): Int {
    val estimate = Distance.totalPathDistance(points) / step + points.size
    return if (estimate >= Int.MAX_VALUE) Int.MAX_VALUE else estimate.toInt()
}

private fun List<CoordinatesElevation>.toResponse(
    source: ElevationSource,
    zoom: Int?,
): ElevationPointsResponse =
    ElevationPointsResponse(
        points = map { ElevationPointDto(latitude = it.latitude, longitude = it.longitude, elevation = it.elevation) },
        source = source.id,
        zoomLevel = zoom ?: source.defaultZoomLevel,
        attribution = source.attributions(zoom),
    )
