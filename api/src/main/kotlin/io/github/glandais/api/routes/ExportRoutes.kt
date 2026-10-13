package io.github.glandais.api.routes

import io.github.glandais.api.apiServices
import io.github.glandais.api.dto.ExportRequest
import io.github.glandais.api.http.FORMAT_QUERY
import io.github.glandais.api.http.OutputFormat
import io.github.glandais.api.http.ProblemKind
import io.github.glandais.api.http.receiveGpxUpload
import io.github.glandais.api.http.resolveOutputFormat
import io.github.glandais.api.mapping.toPlan
import io.github.glandais.api.openapi.exportResponse
import io.github.glandais.api.openapi.formatParameter
import io.github.glandais.api.openapi.gpxQueryParameters
import io.github.glandais.api.openapi.gpxRequestBody
import io.github.glandais.api.openapi.problemResponses
import io.github.glandais.api.service.ExportedBody
import io.github.glandais.api.service.checkPathCount
import io.github.glandais.api.service.export
import io.ktor.http.ContentDisposition
import io.ktor.http.HttpHeaders
import io.ktor.openapi.jsonSchema
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post

/** The formats `/export` answers in: every file format, `summary` excepted (there is no ride). */
private val EXPORT_FORMATS: List<OutputFormat> = OutputFormat.entries.filter { it.isFile }

/** Default of `/export`: GPX in, GPX out. */
private val EXPORT_DEFAULT: OutputFormat = OutputFormat.GPX

/** The problems an export adds to those of the GPX input, for `problemResponses`. */
val EXPORT_PROBLEMS: Array<ProblemKind> =
    arrayOf(
        ProblemKind.NOT_ACCEPTABLE,
        ProblemKind.MULTIPLE_TRACKS,
        ProblemKind.MISSING_START_TIME,
        ProblemKind.TRACK_NAMES_MISMATCH,
        ProblemKind.NOT_ENCODABLE,
    )

/** Answers [body] as a file: its type, and `Content-Disposition: attachment; filename=…`. */
suspend fun ApplicationCall.respondExport(body: ExportedBody) {
    response.header(
        HttpHeaders.ContentDisposition,
        ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, body.filename).toString(),
    )
    respondBytes(body.bytes, body.contentType)
}

/** `POST /export` — convert a GPX to GPX, FIT, CSV or column JSON, simulating nothing. */
fun Route.exportRoutes() {
    post("export") {
        val services = call.apiServices
        val upload = call.receiveGpxUpload(ExportRequest.serializer(), services.config, extraQuery = setOf(FORMAT_QUERY))
        val format = call.resolveOutputFormat(EXPORT_FORMATS, EXPORT_DEFAULT)
        val plan = upload.request?.output.toPlan(format)
        val input = upload.parse(services.computeGate, services.config)
        checkPathCount(plan, input.paths.size)
        val body =
            services.computeGate.run {
                export(plan, input.paths, inputNames = input.names, document = input.document)
            }
        call.respondExport(body)
    }.describe {
        operationId = "export"
        summary = "Convert a GPX"
        description =
            "Writes the selected paths of a GPX as GPX, FIT, CSV or column JSON, as they are in the file — no " +
            "densification, no DEM, no simulation. GPX output keeps the input's track names and waypoints " +
            "unless `output.gpx` says otherwise. Format from `format`, else `Accept`, else " +
            "`${EXPORT_DEFAULT.id}`."
        tag("export")
        parameters {
            gpxQueryParameters()
            formatParameter(EXPORT_FORMATS, EXPORT_DEFAULT)
        }
        gpxRequestBody(jsonSchema<ExportRequest>(), "The writer settings of the chosen format.")
        responses {
            exportResponse(EXPORT_FORMATS)
            problemResponses(*GPX_INPUT_PROBLEMS, *EXPORT_PROBLEMS)
        }
    }
}
