package io.github.glandais.cli

import io.github.glandais.elevation.ElevationProvider
import io.github.glandais.elevation.ElevationProviderConfig
import io.github.glandais.elevation.decodeTileBytes
import io.github.glandais.elevation.fetchTileBytes
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * An [ElevationProvider] whose DEM tiles persist on disk under [cacheFolder] (task g34).
 *
 * Layout is `{cacheFolder}/{host}/{z}/{x}/{y}.webp` — the same shape the map tile cache uses
 * (`TileMapProducer`), so `--cache` holds both kinds of tiles side by side, as its help text has
 * always promised. Same policies too: tiles are immutable so they never expire, and a *failed*
 * fetch is not cached.
 */
internal fun diskCachedElevationProvider(
    cacheFolder: File,
    config: ElevationProviderConfig = ElevationProviderConfig(),
): ElevationProvider =
    ElevationProvider(config) { url ->
        val cached = File(cacheFolder, cachePathFor(url))
        val bytes =
            if (cached.isFile && cached.length() > 0) {
                cached.readBytes()
            } else {
                fetchTileBytes(url).also { fresh ->
                    val parent = cached.absoluteFile.parentFile
                    parent.mkdirs()
                    // BatchCalculator fetches up to ten tiles concurrently: write to a private
                    // temp file and move it into place so a half-written tile is never visible.
                    val tmp = Files.createTempFile(parent.toPath(), cached.name, ".tmp")
                    Files.write(tmp, fresh)
                    Files.move(tmp, cached.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        decodeTileBytes(bytes, url)
    }

/** `https://host/z/x/y.webp` → `host/z/x/y.webp`, each segment scrubbed of anything path-hostile. */
private fun cachePathFor(url: String): String =
    url
        .substringAfter("://")
        .substringBefore('?')
        .split('/')
        .filter { it.isNotEmpty() && it != "." && it != ".." }
        .joinToString("/") { segment -> segment.replace(Regex("[^A-Za-z0-9._-]"), "_") }
