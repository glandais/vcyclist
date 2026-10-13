package io.github.glandais.api

import java.util.Properties

/**
 * Version of the HTTP contract, as published in `info.version` of `api/contract/openapi.yaml`.
 *
 * A **constant**, never `project.version`: semantic-release bumps the project version on every
 * push, which would make the committed contract permanently stale. Bump it by hand when the
 * contract changes in a way a client can observe. The engine's own version is [engineVersion].
 */
const val API_VERSION: String = "1.0.0"

/** Build-time resource written by `:api:generateVersionProperties`. */
internal const val VERSION_RESOURCE: String = "/vcyclist-api.properties"

/** The version the engine artefacts were built under, read from [VERSION_RESOURCE]. */
val engineVersion: String by lazy {
    ApiConfig::class.java.getResourceAsStream(VERSION_RESOURCE)?.use { stream ->
        Properties().apply { load(stream) }.getProperty("version")
    } ?: "unknown"
}
