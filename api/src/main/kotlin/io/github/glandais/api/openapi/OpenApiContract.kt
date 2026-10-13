package io.github.glandais.api.openapi

import io.github.glandais.api.API_VERSION
import io.github.glandais.api.http.MediaTypes
import io.github.glandais.api.http.Problem
import io.github.glandais.api.http.ProblemKind
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.OpenApiDoc
import io.ktor.openapi.OpenApiInfo
import io.ktor.openapi.Responses
import io.ktor.openapi.jsonSchema
import io.ktor.server.application.Application
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.OpenApiDocSource
import io.ktor.server.routing.openapi.hide

/**
 * The **single** rendering site of the OpenAPI contract: the served `/api/v1/openapi.{yaml,json}`,
 * Swagger UI, `:api:generateOpenApi` and `OpenApiContractTest` all go through [render] or
 * [source], so what is committed is what is served.
 */
object OpenApiContract {
    /** Where `:api:generateOpenApi` writes the contract, relative to the repository root. */
    const val CONTRACT_PATH: String = "api/contract/openapi.yaml"

    val info: OpenApiInfo =
        OpenApiInfo(
            title = "vcyclist API",
            version = API_VERSION,
            description =
                "HTTP door of the vcyclist engine, a physics-based cycling simulator that turns GPS " +
                    "traces into physics-aware virtualized rides. Stateless: every request carries its " +
                    "own GPX. Errors are RFC 9457 `application/problem+json` documents. This document is " +
                    "generated from the server's routing tree (`./gradlew :api:generateOpenApi`).",
        )

    /**
     * The base document every rendering starts from. Built through [OpenApiDoc.Builder], the way
     * Swagger UI builds its own from its config, so both renderings are byte-identical (the
     * builder emits an empty `webhooks`, the data-class constructor does not).
     */
    fun baseDocument(): OpenApiDoc = OpenApiDoc.build { info = OpenApiContract.info }

    /** A source rendering the routing tree as [contentType] (YAML or JSON). */
    fun source(contentType: ContentType): OpenApiDocSource.Routing = OpenApiDocSource.Routing(contentType = contentType)

    /** Renders the contract of [application]'s routing tree as [contentType]. */
    fun render(
        application: Application,
        contentType: ContentType = ContentType.Application.Yaml,
    ): String = source(contentType).read(application, baseDocument()).content
}

/**
 * Serves the contract (`openapi.yaml`, `openapi.json`) and Swagger UI (`swagger`) under this
 * route. All three are hidden from the contract itself. Swagger UI loads its assets from unpkg:
 * acceptable for a development aid.
 */
fun Route.openApiRoutes() {
    get("openapi.yaml") {
        call.respondText(OpenApiContract.render(call.application, ContentType.Application.Yaml), ContentType.Application.Yaml)
    }.hide()
    get("openapi.json") {
        call.respondText(OpenApiContract.render(call.application, ContentType.Application.Json), ContentType.Application.Json)
    }.hide()
}

/** Swagger UI at `/swagger`, reading the same source as [OpenApiContract.render], not Ktor's default file. */
fun Route.swaggerRoutes() {
    swaggerUI("swagger") {
        info = OpenApiContract.info
        source = OpenApiContract.source(ContentType.Application.Json)
        remotePath = "openapi.json"
    }.hide()
}

/**
 * Declares the error responses an operation can answer, one entry per status, each an
 * `application/problem+json` [Problem] whose description lists the problem types it may carry.
 */
fun Responses.Builder.problemResponses(vararg kinds: ProblemKind) {
    kinds
        .groupBy { it.status }
        .toSortedMap(compareBy(HttpStatusCode::value))
        .forEach { (status, ofStatus) ->
            status {
                description = "Problem: " + ofStatus.joinToString(", ") { "`${it.type}`" }
                MediaTypes.ProblemJson {
                    schema = jsonSchema<Problem>()
                }
            }
        }
}
