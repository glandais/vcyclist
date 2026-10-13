package io.github.glandais.api.openapi

import io.github.glandais.api.http.FORMAT_QUERY
import io.github.glandais.api.http.OutputFormat
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.JsonSchema
import io.ktor.openapi.JsonType
import io.ktor.openapi.Parameters
import io.ktor.openapi.ReferenceOr
import io.ktor.openapi.Responses

/** What each format is, for the `format` parameter's description. */
private fun OutputFormat.blurb(): String =
    when (this) {
        OutputFormat.SUMMARY -> "a JSON summary per path"
        OutputFormat.GPX -> "a GPX 1.1 document, one `<trk>` per path (`output.gpx`)"
        OutputFormat.FIT -> "a FIT course, one lap per path (`output.fit`, `startTimeEpochMs` required)"
        OutputFormat.CSV -> "one row per point of the single path (`output.csv`)"
        OutputFormat.JSON -> "the column document of the single path, one array per field (`output.json`)"
    }

/**
 * The `format` query parameter: its values are [allowed], listed from [OutputFormat] — never
 * spelled here — with their `Accept` equivalent.
 */
fun Parameters.Builder.formatParameter(
    allowed: List<OutputFormat>,
    default: OutputFormat,
) {
    query(FORMAT_QUERY) {
        description =
            "Response format; wins over `Accept`. " +
            allowed.joinToString("; ") { "`${it.id}` (`Accept: ${it.acceptType}`): ${it.blurb()}" } +
            ". Absent: from `Accept`, then `${default.id}`. An `Accept` naming none of these is 406. " +
            "`csv` and `json` carry one path: with several, 422 `multiple-tracks` (use `trackIndex`)."
        schema = stringEnum(allowed.map { it.id }, default.id)
    }
}

/** The column document of `JsonWriter`, for `format=json`. */
private val columnsSchema: JsonSchema =
    JsonSchema(
        type = JsonType.OBJECT,
        description =
            "Column document (`format=json`): `fields` maps each selected field's `prop` to one array of " +
                "numbers (null where absent), and `meta` carries aggregates and units unless " +
                "`output.json.includeMeta` is false.",
    )

/**
 * The success response of an export: one content entry per [allowed] format. `summary` and
 * `json` share `application/json`, so with [summarySchema] that entry is a `oneOf`.
 */
fun Responses.Builder.exportResponse(
    allowed: List<OutputFormat>,
    summarySchema: JsonSchema? = null,
) {
    HttpStatusCode.OK {
        description =
            "The rendered result. Files (" + allowed.filter { it.isFile }.joinToString(", ") { it.id } +
            ") come with `Content-Disposition: attachment`."
        headers {
            header(HttpHeaders.ContentDisposition) {
                description = "`attachment; filename=\"<name>.<ext>\"` for a file format; absent for `summary`."
                schema = JsonSchema(type = JsonType.STRING)
            }
        }
        for (format in allowed) {
            when (format) {
                OutputFormat.SUMMARY -> Unit

                OutputFormat.JSON ->
                    (format.mediaType.withoutParameters()) {
                        schema =
                            if (summarySchema != null && OutputFormat.SUMMARY in allowed) {
                                JsonSchema(
                                    description = "`summary` (the default) or `json` (the column document)",
                                    oneOf = listOf(ReferenceOr.value(summarySchema), ReferenceOr.value(columnsSchema)),
                                )
                            } else {
                                columnsSchema
                            }
                    }

                OutputFormat.FIT ->
                    (format.mediaType.withoutParameters()) {
                        schema = JsonSchema(type = JsonType.STRING, format = "binary")
                    }

                OutputFormat.GPX, OutputFormat.CSV ->
                    (format.mediaType.withoutParameters()) {
                        schema = JsonSchema(type = JsonType.STRING)
                    }
            }
        }
        if (OutputFormat.SUMMARY in allowed && OutputFormat.JSON !in allowed && summarySchema != null) {
            OutputFormat.SUMMARY.mediaType {
                schema = summarySchema
            }
        }
    }
}
