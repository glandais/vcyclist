package io.github.glandais.api.http

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Prefix of every problem `type`: `urn:vcyclist:problem:<slug>`. */
const val PROBLEM_TYPE_PREFIX: String = "urn:vcyclist:problem:"

/**
 * An RFC 9457 problem document, served as `application/problem+json` for every error.
 *
 * @property unknownKeys JSON pointers (`/options/foo`), multipart parts or query parameters
 *   (`?foo`) the request named and the API does not know — **all** of them, not the first
 * @property errors per-field validation messages
 */
@Serializable
@SerialName("Problem")
data class Problem(
    val type: String,
    val title: String,
    val status: Int,
    val detail: String? = null,
    val instance: String? = null,
    val unknownKeys: List<String>? = null,
    val errors: List<FieldError>? = null,
)

/** One field-level error of a [Problem]; [pointer] is a JSON pointer into the request. */
@Serializable
@SerialName("FieldError")
data class FieldError(
    val pointer: String,
    val message: String,
)

/** Every problem the API can answer, with its slug, status and title. */
enum class ProblemKind(
    val slug: String,
    val status: HttpStatusCode,
    val title: String,
) {
    INVALID_BODY("invalid-body", HttpStatusCode.BadRequest, "Invalid body"),
    UNKNOWN_KEY("unknown-key", HttpStatusCode.BadRequest, "Unknown key"),
    INVALID_OPTION("invalid-option", HttpStatusCode.BadRequest, "Invalid option"),
    INVALID_GPX("invalid-gpx", HttpStatusCode.BadRequest, "Invalid GPX"),
    NOT_FOUND("not-found", HttpStatusCode.NotFound, "Not found"),
    METHOD_NOT_ALLOWED("method-not-allowed", HttpStatusCode.MethodNotAllowed, "Method not allowed"),
    NOT_ACCEPTABLE("not-acceptable", HttpStatusCode.NotAcceptable, "Not acceptable"),
    PAYLOAD_TOO_LARGE("payload-too-large", HttpStatusCode.PayloadTooLarge, "Payload too large"),
    TOO_MANY_POINTS("too-many-points", HttpStatusCode.PayloadTooLarge, "Too many points"),
    UNSUPPORTED_MEDIA_TYPE("unsupported-media-type", HttpStatusCode.UnsupportedMediaType, "Unsupported media type"),
    EMPTY_GPX("empty-gpx", HttpStatusCode.UnprocessableEntity, "Empty GPX"),
    MULTIPLE_TRACKS("multiple-tracks", HttpStatusCode.UnprocessableEntity, "Multiple tracks"),
    MISSING_START_TIME("missing-start-time", HttpStatusCode.UnprocessableEntity, "Missing start time"),
    TRACK_INDEX_OUT_OF_RANGE("track-index-out-of-range", HttpStatusCode.UnprocessableEntity, "Track index out of range"),
    TRACK_NAMES_MISMATCH("track-names-mismatch", HttpStatusCode.UnprocessableEntity, "Track names mismatch"),
    DEM_UNAVAILABLE("dem-unavailable", HttpStatusCode.BadGateway, "Elevation data unavailable"),
    DEM_RATE_LIMITED("dem-rate-limited", HttpStatusCode.ServiceUnavailable, "Elevation data rate limited"),
    BUSY("busy", HttpStatusCode.ServiceUnavailable, "Busy"),
    TIMEOUT("timeout", HttpStatusCode.ServiceUnavailable, "Timeout"),
    MAP_TILES_UNCONFIGURED("map-tiles-unconfigured", HttpStatusCode.ServiceUnavailable, "Map tiles not configured"),
    INTERNAL("internal", HttpStatusCode.InternalServerError, "Internal error"),
    ;

    /** The problem `type` URI. */
    val type: String get() = PROBLEM_TYPE_PREFIX + slug

    /** The [Problem] document for this kind. */
    fun toProblem(
        detail: String?,
        instance: String?,
        unknownKeys: List<String>? = null,
        errors: List<FieldError>? = null,
    ): Problem = Problem(type, title, status.value, detail, instance, unknownKeys, errors)
}

/**
 * A typed failure, translated by `StatusPages` into its [Problem]. Throwing it is the one way a
 * route reports an error.
 *
 * @property retryAfterSeconds sent as `Retry-After` when not null
 */
class ApiProblem(
    val kind: ProblemKind,
    val detail: String?,
    val unknownKeys: List<String>? = null,
    val errors: List<FieldError>? = null,
    val retryAfterSeconds: Long? = null,
    cause: Throwable? = null,
) : RuntimeException(detail ?: kind.title, cause)
