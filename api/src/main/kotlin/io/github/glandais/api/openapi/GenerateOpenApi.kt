package io.github.glandais.api.openapi

import io.github.glandais.api.ApiConfig
import io.github.glandais.api.module
import io.ktor.http.ContentType
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import java.io.File

/**
 * `./gradlew :api:generateOpenApi`: starts the real server on a free port, renders its routing
 * tree through [OpenApiContract.render] and writes [OpenApiContract.CONTRACT_PATH], relative to
 * the working directory (the task sets it to the repository root).
 */
fun main() {
    val config = ApiConfig(host = "127.0.0.1", port = 0)
    val server = embeddedServer(CIO, host = config.host, port = config.port) { module(config) }
    server.start(wait = false)
    try {
        val yaml = OpenApiContract.render(server.application, ContentType.Application.Yaml)
        val target = File(OpenApiContract.CONTRACT_PATH)
        target.parentFile.mkdirs()
        target.writeText(yaml)
        println("Wrote ${target.absolutePath}")
    } finally {
        server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
    }
}
