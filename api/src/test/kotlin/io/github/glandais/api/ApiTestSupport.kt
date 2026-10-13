package io.github.glandais.api

import io.github.glandais.api.http.ApiJson
import io.github.glandais.api.http.MediaTypes
import io.github.glandais.api.http.PROBLEM_TYPE_PREFIX
import io.github.glandais.api.http.Problem
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** A configuration for tests: small limits, so a 413 is cheap to provoke. */
fun testConfig(
    maxBodyBytes: Long = 64L * 1024,
    requestTimeoutMs: Long = 30_000,
    maxTrackPoints: Int = ApiConfig().maxTrackPoints,
): ApiConfig =
    ApiConfig(
        host = "127.0.0.1",
        port = 0,
        maxBodyBytes = maxBodyBytes,
        maxTrackPoints = maxTrackPoints,
        requestTimeoutMs = requestTimeoutMs,
        computeConcurrency = 2,
    )

/**
 * Runs [block] against the real [module] in a `testApplication`, with a client that speaks
 * [ApiJson]. [extraRoutes] installs test-only routes beside the real ones.
 */
fun apiTest(
    config: ApiConfig = testConfig(),
    deps: ApiDependencies = ApiDependencies(),
    extraRoutes: Application.() -> Unit = {},
    block: suspend ApplicationTestBuilder.(client: HttpClient) -> Unit,
) = testApplication {
    application {
        module(config, deps)
        extraRoutes()
    }
    val client =
        createClient {
            install(ContentNegotiation) { json(ApiJson) }
        }
    block(client)
}

/** Asserts [response] is a problem document of [slug] with [status], and returns it. */
suspend fun assertProblem(
    response: HttpResponse,
    status: HttpStatusCode,
    slug: String,
): Problem {
    val body = response.bodyAsText()
    assertEquals(status, response.status, body)
    assertTrue(
        response.contentType()?.match(MediaTypes.ProblemJson) == true,
        "expected ${MediaTypes.ProblemJson}, was ${response.contentType()}",
    )
    val problem = ApiJson.decodeFromString(Problem.serializer(), body)
    assertEquals(PROBLEM_TYPE_PREFIX + slug, problem.type, body)
    assertEquals(status.value, problem.status)
    return problem
}

/** `true` when [response] carries JSON. */
fun isJson(response: HttpResponse): Boolean = response.contentType()?.match(ContentType.Application.Json) == true

/** The repository root, found by walking up to `settings.gradle.kts`. */
fun repositoryRoot(): File {
    var dir: File? = File(".").absoluteFile
    while (dir != null) {
        if (File(dir, "settings.gradle.kts").exists()) return dir
        dir = dir.parentFile
    }
    fail("no repository root above ${File(".").absolutePath}")
}
