package io.github.glandais.api.routes

import io.github.glandais.api.apiServices
import io.github.glandais.api.dto.ParsedGpxDto
import io.github.glandais.api.dto.ParsedPathDto
import io.github.glandais.api.dto.WaypointDto
import io.github.glandais.api.dto.wire
import io.github.glandais.api.http.ProblemKind
import io.github.glandais.api.http.queryBoolean
import io.github.glandais.api.http.receiveGpxInput
import io.github.glandais.api.mapping.toPoints
import io.github.glandais.api.mapping.toSummary
import io.github.glandais.api.openapi.gpxQueryParameters
import io.github.glandais.api.openapi.gpxRequestBody
import io.github.glandais.api.openapi.problemResponses
import io.github.glandais.engine.gpx.startTime
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.GenericElement
import io.ktor.openapi.JsonSchema
import io.ktor.openapi.JsonType
import io.ktor.openapi.jsonSchema
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post

private const val TAG = "gpx"

/** `?points=` of `/gpx/parse`. */
private const val POINTS = "points"

/** Default of [POINTS]: a summary is what most callers want, and the points can be large. */
private const val POINTS_DEFAULT = false

/** `POST /gpx/parse` — read a GPX without simulating anything. */
fun Route.gpxRoutes() {
    post("gpx/parse") {
        val services = call.apiServices
        // Read before the body, like every other query value: a malformed one never costs a parse.
        val withPoints = call.queryBoolean(POINTS) ?: POINTS_DEFAULT
        val input = call.receiveGpxInput<Nothing>(null, services.config, extraQuery = setOf(POINTS))
        val answer =
            services.computeGate.run {
                ParsedGpxDto(
                    name = input.document.name,
                    startTimeEpochMs = input.document.startTime?.toEpochMilliseconds(),
                    waypoints = input.document.waypoints.map(WaypointDto::of),
                    paths =
                        input.paths.indices.map { i ->
                            val path = input.paths[i]
                            ParsedPathDto(
                                name = input.names[i],
                                kind = input.kinds[i].wire,
                                summary = path.toSummary(),
                                points = if (withPoints) path.toPoints() else null,
                            )
                        },
                    warnings = input.warnings,
                )
            }
        call.respond(answer)
    }.describe {
        operationId = "parseGpx"
        summary = "Parse a GPX"
        description =
            "Reads a GPX document and returns its waypoints and, for each selected path, its name, its " +
            "container and its statistics — optionally every point. Simulates nothing: distances and " +
            "elevations are those of the file."
        tag(TAG)
        parameters {
            gpxQueryParameters()
            query(POINTS) {
                description = "Include every point of each path. Default `$POINTS_DEFAULT`."
                schema = JsonSchema(type = JsonType.BOOLEAN, default = GenericElement(POINTS_DEFAULT))
            }
        }
        gpxRequestBody()
        responses {
            HttpStatusCode.OK {
                description = "The parsed document"
                schema = jsonSchema<ParsedGpxDto>()
            }
            problemResponses(*GPX_INPUT_PROBLEMS)
        }
    }
}

/** The problems [io.github.glandais.api.http.receiveGpxInput] can raise, for `problemResponses`. */
val GPX_INPUT_PROBLEMS: Array<ProblemKind> =
    arrayOf(
        ProblemKind.INVALID_BODY,
        ProblemKind.UNKNOWN_KEY,
        ProblemKind.INVALID_OPTION,
        ProblemKind.INVALID_GPX,
        ProblemKind.PAYLOAD_TOO_LARGE,
        ProblemKind.TOO_MANY_POINTS,
        ProblemKind.UNSUPPORTED_MEDIA_TYPE,
        ProblemKind.EMPTY_GPX,
        ProblemKind.TRACK_INDEX_OUT_OF_RANGE,
        ProblemKind.TIMEOUT,
    )
