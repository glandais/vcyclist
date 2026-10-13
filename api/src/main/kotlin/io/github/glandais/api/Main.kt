package io.github.glandais.api

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlin.system.exitProcess

/**
 * Starts the API on CIO. Settings come from `VCYCLIST_API_*` environment variables and the
 * `--host` / `--port` arguments (see [ApiConfig.fromEnvironment]).
 */
fun main(args: Array<String>) {
    val config =
        try {
            ApiConfig.fromEnvironment(args, System.getenv())
        } catch (e: IllegalArgumentException) {
            System.err.println("vcyclist-api: ${e.message}")
            System.err.println("usage: vcyclist-api [--host <host>] [--port <port>]   (see VCYCLIST_API_* variables)")
            exitProcess(2)
        }
    embeddedServer(CIO, host = config.host, port = config.port) {
        module(config)
    }.start(wait = true)
}
