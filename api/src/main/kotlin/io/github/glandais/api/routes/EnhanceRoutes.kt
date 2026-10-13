package io.github.glandais.api.routes

import io.github.glandais.api.apiServices
import io.github.glandais.api.dto.DefaultsDto
import io.github.glandais.api.dto.EnhanceRequest
import io.github.glandais.api.dto.EnhanceResponse
import io.github.glandais.api.dto.LimitsDto
import io.github.glandais.api.dto.TrackResultDto
import io.github.glandais.api.http.ApiProblem
import io.github.glandais.api.http.FORMAT_QUERY
import io.github.glandais.api.http.OutputFormat
import io.github.glandais.api.http.ProblemKind
import io.github.glandais.api.http.queryBoolean
import io.github.glandais.api.http.receiveGpxUpload
import io.github.glandais.api.http.requireOnlyQuery
import io.github.glandais.api.http.resolveOutputFormat
import io.github.glandais.api.mapping.HttpDefaults
import io.github.glandais.api.mapping.OutputPlan
import io.github.glandais.api.mapping.elevationDefaultsDto
import io.github.glandais.api.mapping.finiteOrNull
import io.github.glandais.api.mapping.fitDefaultsDto
import io.github.glandais.api.mapping.gpxWriteDefaultsDto
import io.github.glandais.api.mapping.mapDefaultsDto
import io.github.glandais.api.mapping.toDto
import io.github.glandais.api.mapping.toPlan
import io.github.glandais.api.mapping.toPoints
import io.github.glandais.api.mapping.toSettings
import io.github.glandais.api.mapping.toSummary
import io.github.glandais.api.openapi.exportResponse
import io.github.glandais.api.openapi.formatParameter
import io.github.glandais.api.openapi.gpxQueryParameters
import io.github.glandais.api.openapi.gpxRequestBody
import io.github.glandais.api.openapi.problemResponses
import io.github.glandais.api.service.checkPathCount
import io.github.glandais.api.service.enhancePath
import io.github.glandais.api.service.export
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

/** The formats `/enhance` answers in; the first is the default. */
private val ENHANCE_FORMATS: List<OutputFormat> = OutputFormat.entries

/** `?points=` of `/enhance`. */
private const val POINTS = "points"

/** Default of [POINTS]: a summary is what most callers want, and the points can be large. */
private const val POINTS_DEFAULT = false

/** `POST /enhance` — simulate a ride on each path of a GPX — and `GET /defaults`, what it applies. */
fun Route.enhanceRoutes() {
    post("enhance") {
        val services = call.apiServices
        val withPoints = call.queryBoolean(POINTS) ?: POINTS_DEFAULT
        val upload =
            call.receiveGpxUpload(EnhanceRequest.serializer(), services.config, extraQuery = setOf(POINTS, FORMAT_QUERY))
        // Everything below until `parse` is mapping, not computation: an invalid option, an
        // unservable Accept or a missing FIT start time is a 4xx that never takes the compute gate.
        val format = call.resolveOutputFormat(ENHANCE_FORMATS, OutputFormat.SUMMARY)
        if (withPoints && format != OutputFormat.SUMMARY) {
            throw ApiProblem(
                ProblemKind.INVALID_OPTION,
                "Query parameter '$POINTS' only applies to format=${OutputFormat.SUMMARY.id}, not ${format.id}",
            )
        }
        val settings = upload.request.toSettings()
        val plan = upload.request?.output.toPlan(format)
        val input = upload.parse(services.computeGate, services.config)
        // The path count is known once parsed, before the simulation: a CSV of three tracks is a
        // 422 that costs a parse, not three rides.
        checkPathCount(plan, input.paths.size)
        if (plan !is OutputPlan.Summary) {
            val body =
                services.computeGate.run {
                    val enhanced = input.paths.map { enhancePath(it, settings, services.deps.elevation) }
                    export(plan, enhanced, inputNames = null, document = input.document)
                }
            call.respondExport(body)
            return@post
        }
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
            "by `GET /api/v1/defaults`. Unknown keys and invalid values are rejected before any computation. " +
            "The answer is a JSON summary, or the enhanced paths as GPX, FIT, CSV or column JSON (`format`, or " +
            "`Accept`); a file format carries no `warnings`."
        tag(TAG)
        parameters {
            gpxQueryParameters()
            formatParameter(ENHANCE_FORMATS, OutputFormat.SUMMARY)
            query(POINTS) {
                description =
                    "Include every point of each enhanced path (`format=summary` only). Default `$POINTS_DEFAULT`."
                schema = JsonSchema(type = JsonType.BOOLEAN, default = GenericElement(POINTS_DEFAULT))
            }
        }
        gpxRequestBody(
            jsonSchema<EnhanceRequest>(),
            "The simulation settings: pipeline options, cyclist, bike, wind, power strategy, road-width override " +
                "and the writer settings of the chosen format.",
        )
        val summarySchema = jsonSchema<EnhanceResponse>()
        responses {
            exportResponse(ENHANCE_FORMATS, summarySchema)
            problemResponses(
                *GPX_INPUT_PROBLEMS,
                *EXPORT_PROBLEMS,
                ProblemKind.DEM_UNAVAILABLE,
                ProblemKind.DEM_RATE_LIMITED,
            )
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
                csv = HttpDefaults.csv.toDto(),
                json = HttpDefaults.json.toDto(),
                gpx = gpxWriteDefaultsDto(),
                fit = fitDefaultsDto(),
                climb = HttpDefaults.climb.toDto(),
                elevation = elevationDefaultsDto(),
                map = mapDefaultsDto(),
                limits =
                    LimitsDto(
                        maxBodyBytes = config.maxBodyBytes,
                        maxTrackPoints = config.maxTrackPoints,
                        maxElevationPoints = config.maxElevationPoints,
                        requestTimeoutMs = config.requestTimeoutMs,
                        computeConcurrency = config.computeConcurrency,
                        maxMapPixels = config.maxMapPixels,
                        maxMapDemTiles = config.maxMapDemTiles,
                    ),
            ),
        )
    }.describe {
        operationId = "getDefaults"
        summary = "Defaults this door applies"
        description =
            "The value every absent option takes, in the request shapes themselves: any member can be sent " +
            "back as is and gives the same computation. Read from the engine, never restated. `demZoom` is " +
            "null: each DEM source uses its own default zoom. `gpx.trackName` is the name `/enhance` gives; " +
            "`/export` keeps the input's names. `fit.startTimeEpochMs` has no default: it is required. " +
            "`climb` is what `/analysis/climbs` applies, `elevation` what the `/elevation` operations apply; " +
            "their zoom level is each source's own (`GET /api/v1/elevation/sources`). " +
            "`map` is what the `/map` operations apply when no framing is given. " +
            "`limits` are this server's."
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
