package io.github.glandais.api.http

import io.github.glandais.api.apiServices
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.log
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import kotlinx.serialization.SerializationException
import java.io.IOException

/** Writes [problem] as `application/problem+json`, with its own status. */
suspend fun ApplicationCall.respondProblem(
    problem: Problem,
    retryAfterSeconds: Long? = null,
) {
    if (retryAfterSeconds != null) {
        response.header(HttpHeaders.RetryAfter, retryAfterSeconds.toString())
    }
    respondText(
        text = ApiJson.encodeToString(Problem.serializer(), problem),
        contentType = MediaTypes.ProblemJson,
        status = HttpStatusCode.fromValue(problem.status),
    )
}

/** Writes [kind] with [detail], the request path as `instance`. */
suspend fun ApplicationCall.respondProblem(
    kind: ProblemKind,
    detail: String?,
    unknownKeys: List<String>? = null,
    errors: List<FieldError>? = null,
    retryAfterSeconds: Long? = null,
) = respondProblem(kind.toProblem(detail, request.path(), unknownKeys, errors), retryAfterSeconds)

/**
 * The single translation of failures into problem documents.
 *
 * - [ApiProblem] → its own kind;
 * - [IllegalArgumentException] → `invalid-option`: it is what a `require` in the `init` of an
 *   engine options class throws, and its message is written for the caller;
 * - [SerializationException] (and Ktor's [BadRequestException] around it) → `invalid-body`;
 * - an [IOException] that is Ktor's multipart body limit ([isBodyLimitExceeded]) → `payload-too-large`;
 * - bare 404 / 405 / 406 / 415 statuses → their slug;
 * - anything else → `internal`, logged, with a generic detail — a stack trace is not an answer.
 */
fun StatusPagesConfig.configureProblems() {
    exception<ApiProblem> { call, cause ->
        call.respondProblem(cause.kind, cause.detail, cause.unknownKeys, cause.errors, cause.retryAfterSeconds)
    }
    exception<IllegalArgumentException> { call, cause ->
        call.respondProblem(ProblemKind.INVALID_OPTION, cause.message)
    }
    exception<SerializationException> { call, cause ->
        call.respondProblem(ProblemKind.INVALID_BODY, cause.message)
    }
    exception<BadRequestException> { call, cause ->
        val root = generateSequence(cause as Throwable) { it.cause }.firstOrNull { it is ApiProblem } as ApiProblem?
        if (root != null) {
            call.respondProblem(root.kind, root.detail, root.unknownKeys, root.errors, root.retryAfterSeconds)
        } else {
            call.respondProblem(ProblemKind.INVALID_BODY, cause.message)
        }
    }
    exception<IOException> { call, cause ->
        if (isBodyLimitExceeded(cause)) {
            val problem = payloadTooLarge(call.apiServices.config.maxBodyBytes)
            call.respondProblem(problem.kind, problem.detail)
        } else {
            call.application.log.error("Unhandled error on ${call.request.path()}", cause)
            call.respondProblem(ProblemKind.INTERNAL, "Internal error; see the server log")
        }
    }
    exception<Throwable> { call, cause ->
        call.application.log.error("Unhandled error on ${call.request.path()}", cause)
        call.respondProblem(ProblemKind.INTERNAL, "Internal error; see the server log")
    }
    for (kind in listOf(
        ProblemKind.NOT_FOUND,
        ProblemKind.METHOD_NOT_ALLOWED,
        ProblemKind.NOT_ACCEPTABLE,
        ProblemKind.UNSUPPORTED_MEDIA_TYPE,
    )) {
        status(kind.status) { call, _ ->
            call.respondProblem(kind, null)
        }
    }
}
