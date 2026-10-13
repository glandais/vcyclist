package io.github.glandais.api.http

import io.ktor.server.application.ApplicationCall

/**
 * Rejects every query parameter not in [allowed], all of them at once, as `unknown-key` with
 * pointers spelled `?name`. Every route calls it — with no argument when it takes none — so a
 * misspelled `?formt=gpx` is an error and not a silently ignored request.
 *
 * @throws ApiProblem `unknown-key`
 */
fun ApplicationCall.requireOnlyQuery(vararg allowed: String) {
    val unknown =
        request.queryParameters
            .names()
            .filter { it !in allowed }
            .sorted()
    if (unknown.isNotEmpty()) {
        throw ApiProblem(
            ProblemKind.UNKNOWN_KEY,
            "Unknown query parameter(s): ${unknown.joinToString(", ")}" +
                if (allowed.isEmpty()) "; this operation takes none" else "; allowed: ${allowed.joinToString(", ")}",
            unknownKeys = unknown.map { "?$it" },
        )
    }
}

/** The single value of query parameter [name], or `null` when absent; repeated → `invalid-option`. */
fun ApplicationCall.queryString(name: String): String? {
    val values = request.queryParameters.getAll(name) ?: return null
    if (values.size != 1) {
        throw ApiProblem(ProblemKind.INVALID_OPTION, "Query parameter '$name' given ${values.size} times")
    }
    return values.single()
}

/** Query parameter [name] as an integer; malformed → `invalid-option`. */
fun ApplicationCall.queryInt(name: String): Int? =
    queryString(name)?.let {
        it.toIntOrNull() ?: throw ApiProblem(ProblemKind.INVALID_OPTION, "Query parameter '$name' must be an integer, was '$it'")
    }

/** Query parameter [name] as a boolean, `true` or `false` exactly; anything else → `invalid-option`. */
fun ApplicationCall.queryBoolean(name: String): Boolean? =
    queryString(name)?.let {
        when (it) {
            "true" -> true
            "false" -> false
            else -> throw ApiProblem(ProblemKind.INVALID_OPTION, "Query parameter '$name' must be true or false, was '$it'")
        }
    }

/**
 * Query parameter [name] parsed by [parse] — typically an engine `byId` / `fromWire`. A parser
 * returning `null` or throwing [IllegalArgumentException] is `invalid-option`.
 */
fun <T : Any> ApplicationCall.queryEnum(
    name: String,
    parse: (String) -> T?,
): T? =
    queryString(name)?.let {
        val parsed =
            try {
                parse(it)
            } catch (e: IllegalArgumentException) {
                throw ApiProblem(ProblemKind.INVALID_OPTION, "Query parameter '$name': ${e.message}", cause = e)
            }
        parsed ?: throw ApiProblem(ProblemKind.INVALID_OPTION, "Query parameter '$name' has an unknown value '$it'")
    }
