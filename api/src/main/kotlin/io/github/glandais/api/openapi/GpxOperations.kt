package io.github.glandais.api.openapi

import io.github.glandais.api.http.GpxBodyTypes
import io.github.glandais.api.http.GpxKindsParam
import io.github.glandais.api.http.GpxParts
import io.github.glandais.api.http.GpxQuery
import io.github.glandais.api.http.GpxSplitParam
import io.github.glandais.api.http.MediaTypes
import io.ktor.http.ContentType
import io.ktor.openapi.Encoding
import io.ktor.openapi.GenericElement
import io.ktor.openapi.JsonSchema
import io.ktor.openapi.JsonType
import io.ktor.openapi.Operation
import io.ktor.openapi.Parameters
import io.ktor.openapi.ReferenceOr

/**
 * The request body of every operation that reads a GPX: `multipart/form-data` (a `gpx` file part,
 * and a `request` JSON part when [requestSchema] is given) or the raw GPX under each of
 * [GpxBodyTypes].
 */
fun Operation.Builder.gpxRequestBody(
    requestSchema: JsonSchema? = null,
    requestDescription: String? = null,
) {
    requestBody {
        required = true
        description =
            "The GPX, as a multipart upload (part `${GpxParts.GPX}`) or as the raw body. " +
            if (requestSchema != null) {
                "Options travel in the multipart part `${GpxParts.REQUEST}`; a raw body takes every default."
            } else {
                "This operation takes no `${GpxParts.REQUEST}` part."
            }
        ContentType.MultiPart.FormData {
            val properties =
                buildMap<String, ReferenceOr<JsonSchema>> {
                    put(
                        GpxParts.GPX,
                        ReferenceOr.value(JsonSchema(type = JsonType.STRING, format = "binary", description = "The GPX document")),
                    )
                    if (requestSchema != null) {
                        put(
                            GpxParts.REQUEST,
                            ReferenceOr.value(requestSchema.copy(description = requestDescription ?: requestSchema.description)),
                        )
                    }
                }
            schema = JsonSchema(type = JsonType.OBJECT, required = listOf(GpxParts.GPX), properties = properties)
            encoding(GpxParts.GPX, Encoding(contentType = MediaTypes.Gpx))
            if (requestSchema != null) {
                encoding(GpxParts.REQUEST, Encoding(contentType = ContentType.Application.Json))
            }
        }
        for (type in GpxBodyTypes) {
            type {
                schema = JsonSchema(type = JsonType.STRING, format = "binary")
            }
        }
    }
}

/** The query parameters every GPX-reading operation accepts: `kinds`, `split`, `trackIndex`, `repair`. */
fun Parameters.Builder.gpxQueryParameters() {
    query(GpxQuery.KINDS) {
        description =
            "Which containers to read: `all` (`<trk>` and `<rte>`), `tracks` (`<trk>` only), `routes` " +
            "(`<rte>` only). Default `${GpxKindsParam.DEFAULT.id}`."
        schema = stringEnum(GpxKindsParam.ids, GpxKindsParam.DEFAULT.id)
    }
    query(GpxQuery.SPLIT) {
        description =
            "`tracks`: one path per `<trk>` / `<rte>`, segments concatenated (the gap between two " +
            "segments counts as distance). `segments`: one path per `<trkseg>`. " +
            "Default `${GpxSplitParam.DEFAULT.id}`."
        schema = stringEnum(GpxSplitParam.ids, GpxSplitParam.DEFAULT.id)
    }
    query(GpxQuery.TRACK_INDEX) {
        description =
            "Keep only the path at this index of the selection (after `kinds` and `split`, empty paths " +
            "dropped). Absent: every path. Out of range: 422 `track-index-out-of-range`."
        schema = JsonSchema(type = JsonType.INTEGER, minimum = 0.0)
    }
    query(GpxQuery.REPAIR) {
        description =
            "When the XML does not parse, repair it (BOM, control characters, bare `&`, split surrogate " +
            "references) and parse again; each repair is reported in `warnings`. " +
            "Default `${GpxQuery.REPAIR_DEFAULT}`."
        schema = JsonSchema(type = JsonType.BOOLEAN, default = GenericElement(GpxQuery.REPAIR_DEFAULT))
    }
}

/** A string schema restricted to [values], with its [default]. */
fun stringEnum(
    values: List<String>,
    default: String? = null,
): JsonSchema =
    JsonSchema(
        type = JsonType.STRING,
        enum = values.map { GenericElement(it) },
        default = default?.let { GenericElement(it) },
    )
