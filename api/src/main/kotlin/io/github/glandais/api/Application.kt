package io.github.glandais.api

import io.github.glandais.api.http.ApiJson
import io.github.glandais.api.http.ComputeGate
import io.github.glandais.api.http.configureProblems
import io.github.glandais.api.openapi.openApiRoutes
import io.github.glandais.api.openapi.swaggerRoutes
import io.github.glandais.api.routes.enhanceRoutes
import io.github.glandais.api.routes.gpxRoutes
import io.github.glandais.api.routes.metaRoutes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import java.util.UUID

/** What a handler needs from the server: its settings, its collaborators, its compute gate. */
class ApiServices(
    val config: ApiConfig,
    val deps: ApiDependencies,
    val computeGate: ComputeGate,
)

/** Where [module] stores the [ApiServices] of the application. */
val ApiServicesKey: AttributeKey<ApiServices> = AttributeKey("vcyclist.api.services")

/** The [ApiServices] of this call's application. */
val ApplicationCall.apiServices: ApiServices get() = application.attributes[ApiServicesKey]

/** Path prefix of every operation. */
const val API_PREFIX: String = "/api/v1"

/**
 * The whole server: plugins, then routes under [API_PREFIX]. `Main`, the OpenAPI generator and
 * every test install exactly this, with their own [config] and [deps].
 */
fun Application.module(
    config: ApiConfig = ApiConfig(),
    deps: ApiDependencies = ApiDependencies.production(config),
) {
    attributes.put(
        ApiServicesKey,
        ApiServices(config, deps, ComputeGate(config.computeConcurrency, config.requestTimeoutMs)),
    )

    install(ContentNegotiation) {
        json(ApiJson)
    }
    install(StatusPages) {
        configureProblems()
    }
    install(CallId) {
        retrieveFromHeader(HttpHeaders.XRequestId)
        generate { UUID.randomUUID().toString() }
        verify { it.isNotBlank() && it.length <= MAX_REQUEST_ID_LENGTH }
        replyToHeader(HttpHeaders.XRequestId)
    }
    install(CallLogging) {
        callIdMdc("requestId")
    }
    if (config.corsOrigins.isNotEmpty()) {
        install(CORS) {
            for (origin in config.corsOrigins) {
                if (origin == "*") {
                    anyHost()
                } else {
                    allowHost(origin.substringAfter("://"), schemes = listOf(origin.substringBefore("://", "https")))
                }
            }
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Post)
            allowHeader(HttpHeaders.ContentType)
            allowHeader(HttpHeaders.XRequestId)
            exposeHeader(HttpHeaders.XRequestId)
            exposeHeader(HttpHeaders.ContentDisposition)
            exposeHeader(HttpHeaders.RetryAfter)
        }
    }

    routing {
        route(API_PREFIX) {
            metaRoutes()
            gpxRoutes()
            enhanceRoutes()
            openApiRoutes()
        }
        swaggerRoutes()
    }
}

private const val MAX_REQUEST_ID_LENGTH = 128
