@file:JvmName("MapFactoriesJvm")

package io.github.glandais.map

import java.io.File
import java.net.http.HttpClient
import java.time.Duration

/**
 * Java-callable factories for the `:map` classes whose constructors carry Kotlin defaults
 * (task g27).
 *
 * Unlike the rest of this module, these are **not** `@JvmOverloads` on the constructors
 * themselves. ktlint requires an annotated constructor to move onto its own line, which forces
 * the entire class body to be re-indented: about a thousand lines of pure whitespace churn for
 * four annotations, in files whose git history would then be useless. Factories cost four
 * functions and leave the classes untouched.
 *
 * Functions elsewhere in `:map` — `MapImage.ofMaxSize` / `ofZoom` / `ofSize`,
 * `TileMapProducer.createTileMap`, `SrtmMapProducer.createSrtmMap` — carry `@JvmOverloads`
 * directly, since annotating a function reindents nothing.
 */
@JvmOverloads
fun mapSpace(tileSize: Int = MapSpace.DEFAULT_TILE_SIZE): MapSpace = MapSpace(tileSize)

@JvmOverloads
fun httpTileFetcher(
    userAgent: String = HttpTileFetcher.DEFAULT_USER_AGENT,
    timeout: Duration = Duration.ofSeconds(15),
    httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
): HttpTileFetcher = HttpTileFetcher(userAgent, timeout, httpClient)

@JvmOverloads
fun tileMapProducer(
    cacheFolder: File,
    fetcher: TileFetcher = HttpTileFetcher(),
): TileMapProducer = TileMapProducer(cacheFolder, fetcher)

@JvmOverloads
fun srtmMapProducer(
    elevationProvider: io.github.glandais.elevation.ElevationProvider,
    maxSamples: Int = SrtmMapProducer.DEFAULT_MAX_SAMPLES,
): SrtmMapProducer = SrtmMapProducer(elevationProvider, maxSamples)
