package io.github.glandais.api

import java.io.File

/**
 * Settings of the **server**, not defaults of the engine: they are written here as literals, and
 * nowhere else. Engine defaults are read from the engine (see `docs/tasks/api.md`).
 *
 * @property host interface to bind
 * @property port TCP port; `0` picks a free one
 * @property maxBodyBytes request bodies above this are rejected with 413 `payload-too-large`
 * @property maxTrackPoints total points across all parsed tracks, above which 413 `too-many-points`
 * @property maxElevationPoints points in one elevation request, above which 413 `too-many-points`
 * @property requestTimeoutMs bound on the *response* time of CPU work (see `ComputeGate`)
 * @property computeConcurrency number of CPU-bound requests computed at once
 * @property corsOrigins allowed CORS origins (`*` allowed); empty disables CORS entirely
 * @property mapTileUrlTemplate tile server for tiled maps; `null` disables them (503)
 * @property cacheDir scratch directory of the server
 */
data class ApiConfig(
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    val maxBodyBytes: Long = 20L * 1024 * 1024,
    val maxTrackPoints: Int = 200_000,
    val maxElevationPoints: Int = 10_000,
    val requestTimeoutMs: Long = 120_000,
    val computeConcurrency: Int = Runtime.getRuntime().availableProcessors(),
    val corsOrigins: List<String> = emptyList(),
    val mapTileUrlTemplate: String? = null,
    val cacheDir: File = File(System.getProperty("java.io.tmpdir"), "vcyclist-api"),
) {
    init {
        require(port in 0..65535) { "port must be in 0..65535, was $port" }
        require(maxBodyBytes > 0) { "maxBodyBytes must be positive, was $maxBodyBytes" }
        require(maxTrackPoints > 0) { "maxTrackPoints must be positive, was $maxTrackPoints" }
        require(maxElevationPoints > 0) { "maxElevationPoints must be positive, was $maxElevationPoints" }
        require(requestTimeoutMs > 0) { "requestTimeoutMs must be positive, was $requestTimeoutMs" }
        require(computeConcurrency > 0) { "computeConcurrency must be positive, was $computeConcurrency" }
    }

    companion object {
        const val ENV_PREFIX: String = "VCYCLIST_API_"

        /**
         * Reads the configuration from environment variables (`VCYCLIST_API_*`), then from the
         * command line (`--host`, `--port`, as `--port 8081` or `--port=8081`), the command line
         * winning. Unknown arguments and malformed values fail with [IllegalArgumentException].
         */
        fun fromEnvironment(
            args: Array<String>,
            env: Map<String, String>,
        ): ApiConfig {
            fun envOf(name: String): String? = env[ENV_PREFIX + name]?.trim()?.takeIf { it.isNotEmpty() }

            fun <T> parse(
                name: String,
                value: String,
                parser: (String) -> T?,
            ): T = parser(value) ?: throw IllegalArgumentException("Invalid value for $name: '$value'")

            val defaults = ApiConfig()
            var host = envOf("HOST") ?: defaults.host
            var port = envOf("PORT")?.let { parse("${ENV_PREFIX}PORT", it, String::toIntOrNull) } ?: defaults.port

            var i = 0
            while (i < args.size) {
                val arg = args[i]
                val (key, inline) =
                    if (arg.startsWith("--") && arg.contains('=')) {
                        arg.substringBefore('=') to arg.substringAfter('=')
                    } else {
                        arg to null
                    }
                val value: () -> String = {
                    inline ?: args.getOrNull(++i) ?: throw IllegalArgumentException("Missing value for $key")
                }
                when (key) {
                    "--host" -> host = value()
                    "--port" -> port = parse(key, value(), String::toIntOrNull)
                    else -> throw IllegalArgumentException("Unknown argument '$arg': expected --host or --port")
                }
                i++
            }

            return ApiConfig(
                host = host,
                port = port,
                maxBodyBytes =
                    envOf("MAX_BODY_BYTES")?.let { parse("${ENV_PREFIX}MAX_BODY_BYTES", it, String::toLongOrNull) }
                        ?: defaults.maxBodyBytes,
                maxTrackPoints =
                    envOf("MAX_TRACK_POINTS")?.let { parse("${ENV_PREFIX}MAX_TRACK_POINTS", it, String::toIntOrNull) }
                        ?: defaults.maxTrackPoints,
                maxElevationPoints =
                    envOf("MAX_ELEVATION_POINTS")?.let {
                        parse("${ENV_PREFIX}MAX_ELEVATION_POINTS", it, String::toIntOrNull)
                    } ?: defaults.maxElevationPoints,
                requestTimeoutMs =
                    envOf("REQUEST_TIMEOUT_MS")?.let {
                        parse("${ENV_PREFIX}REQUEST_TIMEOUT_MS", it, String::toLongOrNull)
                    } ?: defaults.requestTimeoutMs,
                computeConcurrency =
                    envOf("COMPUTE_CONCURRENCY")?.let {
                        parse("${ENV_PREFIX}COMPUTE_CONCURRENCY", it, String::toIntOrNull)
                    } ?: defaults.computeConcurrency,
                corsOrigins =
                    envOf("CORS_ORIGINS")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                        ?: defaults.corsOrigins,
                mapTileUrlTemplate = envOf("MAP_TILE_URL") ?: defaults.mapTileUrlTemplate,
                cacheDir = envOf("CACHE_DIR")?.let(::File) ?: defaults.cacheDir,
            )
        }
    }
}
