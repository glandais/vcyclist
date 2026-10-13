package io.github.glandais.api.routes

import io.github.glandais.api.apiServices
import io.github.glandais.api.dto.ClimbRequest
import io.github.glandais.api.dto.ClimbsResponse
import io.github.glandais.api.dto.HeadwindResponse
import io.github.glandais.api.dto.RacingLineRequest
import io.github.glandais.api.dto.RacingLineResponse
import io.github.glandais.api.http.receiveGpxInput
import io.github.glandais.api.http.receiveGpxUpload
import io.github.glandais.api.mapping.finiteOrNull
import io.github.glandais.api.mapping.toClimbOptions
import io.github.glandais.api.mapping.toDto
import io.github.glandais.api.mapping.toRacingLineOptions
import io.github.glandais.api.openapi.gpxQueryParameters
import io.github.glandais.api.openapi.gpxRequestBody
import io.github.glandais.api.openapi.problemResponses
import io.github.glandais.engine.climb.ClimbDetector
import io.github.glandais.engine.path.dominantHeadwindAzimuthDeg
import io.github.glandais.engine.trajectory.RacingLine
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post

private const val TAG = "analysis"

/**
 * `POST /analysis/climbs`, `/analysis/racing-line`, `/analysis/headwind` — read-only analyses of
 * the uploaded paths as they are: nothing is simulated, no coordinate moves.
 */
fun Route.analysisRoutes() {
    post("analysis/climbs") {
        val services = call.apiServices
        val upload = call.receiveGpxUpload(ClimbRequest.serializer(), services.config)
        // Mapped before the parse: an out-of-domain option is a 400 that never takes the gate.
        val options = upload.request?.options.toClimbOptions()
        val input = upload.parse(services.computeGate, services.config)
        val tracks =
            services.computeGate.run {
                input.paths.map { path -> ClimbDetector.detect(path, options).map { it.toDto() } }
            }
        call.respond(ClimbsResponse(tracks = tracks, warnings = input.warnings))
    }.describe {
        operationId = "detectClimbs"
        summary = "Detect the climbs of a GPX"
        description =
            "Finds the climbs of each selected path from its own elevations, and splits each into " +
            "homogeneous-grade parts. Grades are ratios (`0.08` is 8 %); distances are absolute along the " +
            "path. Every absent option takes the default published by `GET /api/v1/defaults` (`climb`)."
        tag(TAG)
        parameters { gpxQueryParameters() }
        gpxRequestBody(jsonSchema<ClimbRequest>(), "The detector options.")
        responses {
            HttpStatusCode.OK {
                description = "The climbs of each path, in path order"
                schema = jsonSchema<ClimbsResponse>()
            }
            problemResponses(*GPX_INPUT_PROBLEMS)
        }
    }

    post("analysis/racing-line") {
        val services = call.apiServices
        val upload = call.receiveGpxUpload(RacingLineRequest.serializer(), services.config)
        val options = upload.request?.options.toRacingLineOptions()
        val input = upload.parse(services.computeGate, services.config)
        val tracks =
            services.computeGate.run {
                input.paths.map { path -> RacingLine.analyze(path, options)?.toDto() }
            }
        call.respond(RacingLineResponse(tracks = tracks, warnings = input.warnings))
    }.describe {
        operationId = "analyzeRacingLine"
        summary = "Analyse the racing line of a GPX"
        description =
            "Reports what the racing line would do to each selected path — corners, corridor, curvature " +
            "and the solved lateral offset — without moving any coordinate. Of the `options` object, only " +
            "`racingLineEnabled`, `racingLineCorridor` and `racingLineRoadWidthM` are read; any other key is " +
            "400 `invalid-option`, and so is `racingLineEnabled: false`. A path too short or degenerate to " +
            "analyse gets `null`. Per-station values that were not computed are `null`."
        tag(TAG)
        parameters { gpxQueryParameters() }
        gpxRequestBody(jsonSchema<RacingLineRequest>(), "The racing-line options, within the pipeline options object.")
        responses {
            HttpStatusCode.OK {
                description = "One report per path, in path order"
                schema = jsonSchema<RacingLineResponse>()
            }
            problemResponses(*GPX_INPUT_PROBLEMS)
        }
    }

    post("analysis/headwind") {
        val services = call.apiServices
        val input = call.receiveGpxInput<Nothing>(null, services.config)
        val answer =
            services.computeGate.run {
                HeadwindResponse(
                    overall = input.paths.dominantHeadwindAzimuthDeg().finiteOrNull(),
                    tracks = input.paths.map { it.dominantHeadwindAzimuthDeg().finiteOrNull() },
                    warnings = input.warnings,
                )
            }
        call.respond(answer)
    }.describe {
        operationId = "dominantHeadwind"
        summary = "Dominant headwind direction of a GPX"
        description =
            "The compass azimuth, in degrees (0 = north, 90 = east), the wind must blow **toward** to be a " +
            "headwind on most of the course — the `wind.windDirection` convention of `/enhance`. Over every " +
            "selected path together (`overall`) and for each. `null` with fewer than 4 points or a course " +
            "whose mean direction cancels out. Takes no `request` part."
        tag(TAG)
        parameters { gpxQueryParameters() }
        gpxRequestBody()
        responses {
            HttpStatusCode.OK {
                description = "The azimuths"
                schema = jsonSchema<HeadwindResponse>()
            }
            problemResponses(*GPX_INPUT_PROBLEMS)
        }
    }
}
