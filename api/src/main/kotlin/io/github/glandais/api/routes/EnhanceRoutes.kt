package io.github.glandais.api.routes

import io.github.glandais.api.apiServices
import io.github.glandais.api.dto.DefaultsDto
import io.github.glandais.api.dto.EnhanceRequest
import io.github.glandais.api.dto.EnhanceResponse
import io.github.glandais.api.dto.LimitsDto
import io.github.glandais.api.dto.TrackResultDto
import io.github.glandais.api.http.ProblemKind
import io.github.glandais.api.http.queryBoolean
import io.github.glandais.api.http.receiveGpxUpload
import io.github.glandais.api.http.requireOnlyQuery
import io.github.glandais.api.mapping.HttpDefaults
import io.github.glandais.api.mapping.finiteOrNull
import io.github.glandais.api.mapping.toDto
import io.github.glandais.api.mapping.toPoints
import io.github.glandais.api.mapping.toSettings
import io.github.glandais.api.mapping.toSummary
import io.github.glandais.api.openapi.gpxQueryParameters
import io.github.glandais.api.openapi.gpxRequestBody
import io.github.glandais.api.openapi.problemResponses
import io.github.glandais.api.service.enhancePath
import io.github.glandais.engine.path.dominantHeadwindAzimuthDeg
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

private const val TAG = "enhance"

/** `?points=` of `/enhance`. */
private const val POINTS = "points"

/** Default of [POINTS]: a summary is what most callers want, and the points can be large. */
private const val POINTS_DEFAULT = false

/** `POST /enhance` — simulate a ride on each path of a GPX — and `GET /defaults`, what it applies. */
fun Route.enhanceRoutes() {
    post("enhance") {
        val services = call.apiServices
        val withPoints = call.queryBoolean(POINTS) ?: POINTS_DEFAULT
        val upload = call.receiveGpxUpload(EnhanceRequest.serializer(), services.config, extraQuery = setOf(POINTS))
        // Mapped before anything is parsed or computed: an invalid option is a 400 that never
        // takes the compute gate.
        val settings = upload.request.toSettings()
        val input = upload.parse(services.computeGate, services.config)
        val tracks =
            services.computeGate.run {
                input.paths.mapIndexed { i, path ->
                    val enhanced = enhancePath(path, settings, services.deps.elevation)
                    TrackResultDto(
                        name = input.names[i],
                        summary = enhanced.toSummary(),
                        dominantHeadwindAzimuth = enhanced.dominantHeadwindAzimuthDeg().finiteOrNull(),
                        points = if (withPoints) enhanced.toPoints() else null,
                    )
                }
            }
        call.respond(EnhanceResponse(tracks = tracks, warnings = input.warnings + settings.warnings))
    }.describe {
        operationId = "enhance"
        summary = "Simulate a ride on a GPX"
        description =
            "Runs the vcyclist pipeline — densify, optional DEM elevations, smoothing, curvature or racing " +
            "line, cornering and braking limits, the time-stepping physics simulation, then the optional " +
            "1 Hz resample, W' balance and simplification — on each selected path, with the rider, bike, " +
            "wind and power strategy of the `request` part. Every absent option takes the default published " +
            "by `GET /api/v1/defaults`. Unknown keys and invalid values are rejected before any computation."
        tag(TAG)
        parameters {
            gpxQueryParameters()
            query(POINTS) {
                description = "Include every point of each enhanced path. Default `$POINTS_DEFAULT`."
                schema = JsonSchema(type = JsonType.BOOLEAN, default = GenericElement(POINTS_DEFAULT))
            }
        }
        gpxRequestBody(
            jsonSchema<EnhanceRequest>(),
            "The simulation settings: pipeline options, cyclist, bike, wind, power strategy and road-width override.",
        )
        responses {
            HttpStatusCode.OK {
                description = "One result per selected path, and the warnings"
                schema = jsonSchema<EnhanceResponse>()
            }
            problemResponses(*GPX_INPUT_PROBLEMS, ProblemKind.DEM_UNAVAILABLE, ProblemKind.DEM_RATE_LIMITED)
        }
    }

    get("defaults") {
        call.requireOnlyQuery()
        val config = call.apiServices.config
        call.respond(
            DefaultsDto(
                enhance = HttpDefaults.enhance.toDto(HttpDefaults.elevationSource, zoom = null),
                cyclist = HttpDefaults.cyclist.toDto(),
                bike = HttpDefaults.bike.toDto(),
                power = HttpDefaults.power.toDto(),
                limits =
                    LimitsDto(
                        maxBodyBytes = config.maxBodyBytes,
                        maxTrackPoints = config.maxTrackPoints,
                        maxElevationPoints = config.maxElevationPoints,
                        requestTimeoutMs = config.requestTimeoutMs,
                        computeConcurrency = config.computeConcurrency,
                    ),
            ),
        )
    }.describe {
        operationId = "getDefaults"
        summary = "Defaults this door applies"
        description =
            "The value every absent option takes, in the request shapes themselves: any member can be sent " +
            "back as is and gives the same computation. Read from the engine, never restated. `demZoom` is " +
            "null: each DEM source uses its own default zoom. `limits` are this server's."
        tag("meta")
        responses {
            HttpStatusCode.OK {
                description = "The defaults"
                schema = jsonSchema<DefaultsDto>()
            }
            problemResponses(ProblemKind.UNKNOWN_KEY)
        }
    }
}
