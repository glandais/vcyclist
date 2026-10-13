package io.github.glandais.api

import io.github.glandais.api.openapi.OpenApiContract
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.openapi.OperationHiddenAttributeKey
import io.ktor.server.routing.routingRoot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The committed contract is what the routing tree renders, what the server serves, and complete.
 *
 * The fix, when the first test fails, is `./gradlew :api:generateOpenApi` — never a hand edit.
 * The test task declares `api/contract` as an input directory, so a hand edit re-runs it.
 */
class OpenApiContractTest {
    private val contract = File(repositoryRoot(), OpenApiContract.CONTRACT_PATH)

    /** Starts the real module and hands its [Application] to [block]. */
    private fun withApplication(block: suspend (Application, io.ktor.client.HttpClient) -> Unit) {
        lateinit var app: Application
        apiTest(extraRoutes = { app = this }) { client ->
            startApplication()
            block(app, client)
        }
    }

    @Test
    fun `the committed contract is the rendered one`() =
        withApplication { app, _ ->
            assertTrue(contract.isFile, "not found: ${contract.absolutePath}. Run `./gradlew :api:generateOpenApi`")
            assertEquals(
                OpenApiContract.render(app, ContentType.Application.Yaml),
                contract.readText(),
                "${OpenApiContract.CONTRACT_PATH} is stale. Run `./gradlew :api:generateOpenApi` — never edit by hand.",
            )
        }

    @Test
    fun `every operation has an operationId and a summary, and every visible route an operation`() =
        withApplication { app, _ ->
            val operations = operations(app)
            // Size self-check: an extractor that matches nothing would turn the assertions below
            // into a tautology over an empty list. Raise EXPECTED_OPERATIONS with each operation.
            assertEquals(EXPECTED_OPERATIONS, operations.size, "operations: ${operations.keys}")
            for ((key, operation) in operations) {
                val operationId = operation["operationId"]?.jsonPrimitive?.content
                val summary = operation["summary"]?.jsonPrimitive?.content
                assertTrue(!operationId.isNullOrBlank(), "$key has no operationId")
                assertTrue(!summary.isNullOrBlank(), "$key has no summary")
            }
            assertEquals(
                operations.size,
                operations.values
                    .map { it["operationId"]!!.jsonPrimitive.content }
                    .toSet()
                    .size,
                "operationIds are not unique",
            )

            // Ktor emits a bare path for a route without `describe`; walk the routing tree to see
            // that every visible handler is one of the described operations.
            val visible = visibleHandlers(app.routingRoot)
            assertEquals(operations.keys, visible, "routes without a described operation, or vice versa")
        }

    @Test
    fun `the served contract is the rendered one`() =
        withApplication { app, client ->
            assertEquals(OpenApiContract.render(app, ContentType.Application.Yaml), client.get("/api/v1/openapi.yaml").bodyAsText())
            assertEquals(OpenApiContract.render(app, ContentType.Application.Json), client.get("/api/v1/openapi.json").bodyAsText())
            assertEquals(OpenApiContract.render(app, ContentType.Application.Json), client.get("/swagger/openapi.json").bodyAsText())
        }

    @Test
    fun `rendering is deterministic`() =
        withApplication { app, _ ->
            assertEquals(OpenApiContract.render(app), OpenApiContract.render(app))
        }

    /** `"GET /api/v1/health"` → its operation object, read from the JSON rendering. */
    private fun operations(app: Application): Map<String, JsonObject> {
        val doc = Json.parseToJsonElement(OpenApiContract.render(app, ContentType.Application.Json)).jsonObject
        val paths = doc["paths"]?.jsonObject ?: return emptyMap()
        return paths
            .flatMap { (path, item) ->
                item.jsonObject
                    .filterKeys { it in HTTP_METHODS }
                    .map { (method, op) -> "${method.uppercase()} $path" to op.jsonObject }
            }.toMap()
    }

    private fun visibleHandlers(root: RoutingNode): Set<String> {
        val result = mutableSetOf<String>()

        fun visit(node: RoutingNode) {
            val hidden = generateSequence(node) { it.parent }.any { OperationHiddenAttributeKey in it.attributes }
            if (!hidden && node.hasHandler()) {
                val method =
                    generateSequence(
                        node,
                    ) { it.parent }.map { it.selector }.filterIsInstance<HttpMethodRouteSelector>().first().method
                result += "${method.value} ${node.path()}"
            }
            node.children.forEach(::visit)
        }
        visit(root)
        return result
    }

    /** The route's path without its method selector, as the contract spells it. */
    private fun RoutingNode.path(): String =
        generateSequence(this) { it.parent }
            .toList()
            .asReversed()
            .map { it.selector.toString() }
            .filter { it.isNotEmpty() && !it.startsWith("(method:") }
            .joinToString("/", prefix = "/")
            .replace("//", "/")

    private companion object {
        /** Operations of the contract at this step of `docs/archive/tasks/api.md`. */
        const val EXPECTED_OPERATIONS = 17

        val HTTP_METHODS = HttpMethod.DefaultMethods.map { it.value.lowercase() }.toSet()
    }
}
