package io.github.glandais.api.http

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.acceptItems

/**
 * The response formats of `/enhance` and `/export`, by their `format` query value.
 *
 * The catalogue publishes [ids]; the format negotiation ([resolveOutputFormat]) resolves the
 * `format` query parameter against [byId] and the `Accept` header against [acceptType].
 *
 * @property acceptType the media type an `Accept` header names to ask for this format
 * @property mediaType the `Content-Type` of the response — not always [acceptType]: the column
 *   document is asked for as `application/vnd.vcyclist.columns+json` and served as plain JSON
 * @property extension the file extension of the `Content-Disposition` filename, `null` for
 *   [SUMMARY], which is not a file
 */
enum class OutputFormat(
    val id: String,
    val acceptType: ContentType,
    val mediaType: ContentType,
    val extension: String?,
) {
    SUMMARY("summary", ContentType.Application.Json, ContentType.Application.Json, null),
    GPX("gpx", MediaTypes.Gpx, MediaTypes.Gpx, "gpx"),
    FIT("fit", MediaTypes.Fit, MediaTypes.Fit, "fit"),
    CSV("csv", MediaTypes.Csv, MediaTypes.Csv.withCharset(Charsets.UTF_8), "csv"),
    JSON("json", MediaTypes.Columns, ContentType.Application.Json, "json"),
    ;

    /** `true` for the formats served as a downloadable file, with a `Content-Disposition`. */
    val isFile: Boolean get() = extension != null

    companion object {
        val ids: List<String> get() = entries.map { it.id }

        fun byId(id: String): OutputFormat =
            entries.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("Unknown format '$id': expected one of ${ids.joinToString(", ")}")

        fun byIdOrNull(id: String): OutputFormat? = entries.firstOrNull { it.id == id }
    }
}

/** The `format` query parameter of `/enhance` and `/export`. */
const val FORMAT_QUERY: String = "format"

/**
 * Resolves the response format: the `format` query parameter when present (unknown, or not in
 * [allowed] → `invalid-option`); otherwise the `Accept` header, by decreasing quality — `*` / `*`
 * or a range that covers [default] gives [default], a range covering another allowed format gives
 * the first of them in [allowed]'s order, and a header that covers none is `not-acceptable`;
 * otherwise (no `Accept`) [default].
 *
 * @throws ApiProblem `invalid-option`, `not-acceptable`
 */
fun ApplicationCall.resolveOutputFormat(
    allowed: List<OutputFormat>,
    default: OutputFormat,
): OutputFormat {
    require(default in allowed) { "default $default is not allowed" }
    val requested = queryString(FORMAT_QUERY)
    if (requested != null) {
        val format =
            OutputFormat.byIdOrNull(requested)
                ?: throw ApiProblem(
                    ProblemKind.INVALID_OPTION,
                    "Query parameter '$FORMAT_QUERY' has an unknown value '$requested'; expected one of " +
                        allowed.joinToString(", ") { it.id },
                )
        if (format !in allowed) {
            throw ApiProblem(
                ProblemKind.INVALID_OPTION,
                "Query parameter '$FORMAT_QUERY': '$requested' is not served by this operation; expected one of " +
                    allowed.joinToString(", ") { it.id },
            )
        }
        return format
    }
    if (request.headers[HttpHeaders.Accept].isNullOrBlank()) return default
    val items =
        try {
            request.acceptItems()
        } catch (e: IllegalArgumentException) {
            throw ApiProblem(ProblemKind.NOT_ACCEPTABLE, "Malformed Accept header: ${e.message}", cause = e)
        }
    for (item in items) {
        if (item.quality <= 0.0) continue
        val range =
            try {
                ContentType.parse(item.value)
            } catch (_: Exception) {
                continue
            }
        if (default.acceptType.match(range)) return default
        allowed.firstOrNull { it.acceptType.match(range) }?.let { return it }
    }
    throw ApiProblem(
        ProblemKind.NOT_ACCEPTABLE,
        "Accept '${request.headers[HttpHeaders.Accept]}' names no format this operation serves: " +
            allowed.joinToString(", ") { "${it.acceptType} (format=${it.id})" },
    )
}
