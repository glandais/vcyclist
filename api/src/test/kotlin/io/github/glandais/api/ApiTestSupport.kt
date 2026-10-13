package io.github.glandais.api

import io.github.glandais.api.http.ApiJson
import io.github.glandais.api.http.MediaTypes
import io.github.glandais.api.http.PROBLEM_TYPE_PREFIX
import io.github.glandais.api.http.Problem
import io.github.glandais.api.service.ElevationGateway
import io.github.glandais.elevation.ElevationProviderConfig
import io.github.glandais.elevation.RawTile
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
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
    deps: ApiDependencies = offlineDependencies(),
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

/**
 * A DEM where every point is at [ELEVATION_M]: each tile is filled with the Terrarium pixel
 * R=128, G=100, B=0, which decodes to `128·256 + 100 − 32768` = 100 m. Every tier gets tiles of
 * its own [ElevationProviderConfig.tileSize] (BIL sources decode into the same Terrarium bytes).
 */
object FlatTerrariumTiles {
    const val ELEVATION_M: Double = 100.0

    /** How many tiles were asked for, across every tier. */
    val fetched = AtomicInteger()

    val fetcherFor: (ElevationProviderConfig) -> suspend (String) -> RawTile = { config ->
        { _ ->
            fetched.incrementAndGet()
            tile(config.tileSize)
        }
    }

    private fun tile(size: Int): RawTile {
        val rgba = ByteArray(size * size * 4)
        for (i in 0 until size * size) {
            rgba[i * 4] = 128.toByte()
            rgba[i * 4 + 1] = 100.toByte()
            rgba[i * 4 + 2] = 0
            rgba[i * 4 + 3] = 255.toByte()
        }
        return RawTile(size, size, rgba)
    }
}

/** Collaborators that never reach the network: the DEM is [FlatTerrariumTiles]. */
fun offlineDependencies(): ApiDependencies = ApiDependencies(elevation = ElevationGateway(FlatTerrariumTiles.fetcherFor))

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

/**
 * Posts [gpx] to [url]: as a raw `application/gpx+xml` body when [request] is `null`, else as a
 * multipart upload with [request] as the `request` JSON part. [configure] sets headers.
 */
suspend fun HttpClient.postGpx(
    url: String,
    gpx: String,
    request: String? = null,
    configure: HttpRequestBuilder.() -> Unit = {},
): HttpResponse =
    if (request == null) {
        post(url) {
            contentType(MediaTypes.Gpx)
            setBody(gpx)
            configure()
        }
    } else {
        submitFormWithBinaryData(
            url = url,
            formData =
                formData {
                    append(
                        "gpx",
                        gpx.encodeToByteArray(),
                        Headers.build {
                            append(HttpHeaders.ContentType, MediaTypes.Gpx.toString())
                            append(HttpHeaders.ContentDisposition, "filename=\"in.gpx\"")
                        },
                    )
                    append(
                        "request",
                        request,
                        Headers.build { append(HttpHeaders.ContentType, ContentType.Application.Json.toString()) },
                    )
                },
            block = configure,
        )
    }
