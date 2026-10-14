package io.github.glandais.api

import io.github.glandais.api.http.ApiProblem
import io.github.glandais.api.http.ComputeGate
import io.github.glandais.api.http.ProblemKind
import io.github.glandais.api.http.decodeStrict
import io.github.glandais.api.http.receiveBoundedBytes
import io.github.glandais.api.http.receiveStrict
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProblemTest {
    @Serializable
    @SerialName("Inner")
    data class Inner(
        val a: Int? = null,
    )

    @Serializable
    @SerialName("Flagged")
    data class Flagged(
        val inner: Inner? = null,
        val flag: Boolean? = null,
    )

    @Serializable
    @SerialName("Outer")
    data class Outer(
        val inner: Inner? = null,
        val items: List<Inner>? = null,
        val name: String? = null,
    )

    private val testRoutes: io.ktor.server.application.Application.() -> Unit = {
        routing {
            post("/test/strict") {
                val outer = call.receiveStrict(Outer.serializer())
                call.respondText(outer.toString())
            }
            post("/test/bytes") {
                val bytes = call.receiveBoundedBytes(MAX)
                call.respondText(bytes.size.toString())
            }
        }
    }

    @Test
    fun `an unknown route is a problem`() =
        apiTest { client ->
            val problem = assertProblem(client.get("/api/v1/nope"), HttpStatusCode.NotFound, "not-found")
            assertEquals("/api/v1/nope", problem.instance)
        }

    @Test
    fun `a wrong method is a problem`() =
        apiTest { client ->
            assertProblem(client.post("/api/v1/health"), HttpStatusCode.MethodNotAllowed, "method-not-allowed")
        }

    @Test
    fun `strict decoding lists every unknown key with its pointer`() {
        val failure =
            assertFailsWith<ApiProblem> {
                decodeStrict(
                    Outer.serializer(),
                    """{"inner":{"a":1,"b":2},"items":[{"a":1},{"c/d":3}],"name":"x","zzz":true}""",
                )
            }
        assertEquals(ProblemKind.UNKNOWN_KEY, failure.kind)
        assertEquals(listOf("/inner/b", "/items/1/c~1d", "/zzz"), failure.unknownKeys)
    }

    @Test
    fun `strict decoding reports malformed JSON and wrong types as invalid-body`() {
        assertEquals(ProblemKind.INVALID_BODY, assertFailsWith<ApiProblem> { decodeStrict(Outer.serializer(), "{") }.kind)
        assertEquals(
            ProblemKind.INVALID_BODY,
            assertFailsWith<ApiProblem> { decodeStrict(Outer.serializer(), """{"name":3}""") }.kind,
        )
        assertEquals(Outer(inner = Inner(a = 4)), decodeStrict(Outer.serializer(), """{"inner":{"a":4}}"""))
    }

    @Test
    fun `strict decoding refuses a quoted number or boolean`() {
        // The contract types these as number / boolean: a string is not one, even if it parses.
        for ((body, pointer) in listOf("""{"inner":{"a":"4"}}""" to "/inner/a", """{"flag":"true"}""" to "/flag")) {
            val failure = assertFailsWith<ApiProblem>(body) { decodeStrict(Flagged.serializer(), body) }
            assertEquals(ProblemKind.INVALID_BODY, failure.kind, body)
            assertEquals(listOf(pointer), failure.errors?.map { it.pointer }, body)
        }
        assertEquals(Flagged(inner = Inner(a = 4), flag = true), decodeStrict(Flagged.serializer(), """{"inner":{"a":4},"flag":true}"""))
    }

    @Test
    fun `strict decoding over HTTP answers 400 with all unknown keys`() =
        apiTest(extraRoutes = testRoutes) { client ->
            val response =
                client.post("/test/strict") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"inner":{"b":2},"other":1}""")
                }
            val problem = assertProblem(response, HttpStatusCode.BadRequest, "unknown-key")
            assertEquals(listOf("/inner/b", "/other"), problem.unknownKeys)
        }

    @Test
    fun `a declared Content-Length over the limit is 413`() =
        apiTest(extraRoutes = testRoutes) { client ->
            val ok =
                client.post("/test/bytes") {
                    setBody(ByteArray(MAX.toInt()))
                }
            assertEquals("$MAX", ok.bodyAsText())
            val response =
                client.post("/test/bytes") {
                    setBody(ByteArray(MAX.toInt() + 1))
                }
            assertProblem(response, HttpStatusCode.PayloadTooLarge, "payload-too-large")
        }

    @Test
    fun `a chunked body over the limit is 413 too`() =
        apiTest(extraRoutes = testRoutes) { client ->
            val response =
                client.post("/test/bytes") {
                    setBody(
                        object : OutgoingContent.WriteChannelContent() {
                            // No contentLength: the body is sent chunked.
                            override suspend fun writeTo(channel: ByteWriteChannel) {
                                repeat(4) { channel.writeFully(ByteArray(MAX.toInt() / 2)) }
                            }
                        },
                    )
                }
            assertProblem(response, HttpStatusCode.PayloadTooLarge, "payload-too-large")
        }

    @Test
    fun `retry-after is sent when a problem carries it`() =
        apiTest(
            extraRoutes = {
                routing {
                    post("/test/rate") {
                        throw ApiProblem(ProblemKind.DEM_RATE_LIMITED, "slow down", retryAfterSeconds = 7)
                    }
                }
            },
        ) { client ->
            val response = client.post("/test/rate") { header(HttpHeaders.Accept, "application/json") }
            assertProblem(response, HttpStatusCode.ServiceUnavailable, "dem-rate-limited")
            assertEquals("7", response.headers[HttpHeaders.RetryAfter])
        }

    @Test
    fun `an unexpected exception is a 500 without its message`() =
        apiTest(
            extraRoutes = {
                routing {
                    post("/test/boom") { error("secret detail") }
                }
            },
        ) { client ->
            val problem = assertProblem(client.post("/test/boom"), HttpStatusCode.InternalServerError, "internal")
            assertEquals(false, problem.detail?.contains("secret"))
        }

    @Test
    fun `the compute gate answers timeout when the delay elapses`() {
        val gate = ComputeGate(concurrency = 1, timeoutMs = 50)
        val started = System.nanoTime()
        val failure =
            assertFailsWith<ApiProblem> {
                // A loop that never suspends, like the engine's: the response is bounded anyway.
                runBlocking { gate.run { Thread.sleep(1_000) } }
            }
        assertEquals(ProblemKind.TIMEOUT, failure.kind)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsedMs < 900, "the timeout waited for the work: $elapsedMs ms")
        assertEquals(42, runBlocking { ComputeGate(concurrency = 1, timeoutMs = 1_000).run { 42 } })
    }

    private companion object {
        const val MAX: Long = 1024
    }
}
