package io.github.glandais.cli

import io.github.glandais.elevation.ElevationProvider
import io.github.glandais.elevation.ElevationProviderConfig
import io.github.glandais.elevation.RawTile
import io.github.glandais.elevation.decodeTileBytes
import io.github.glandais.elevation.fetchTileBytes
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * An [ElevationProvider] whose DEM tiles persist on disk under [cacheFolder] (task g34).
 *
 * Layout is `{cacheFolder}/{host}/{z}/{x}/{y}.webp` — the same shape the map tile cache uses
 * (`TileMapProducer`), so `--cache` holds both kinds of tiles side by side, as its help text has
 * always promised. Same policies too: tiles are immutable so they never expire, and a *failed*
 * fetch is not cached.
 *
 * "Failed" includes a 2xx whose body is not an image — a captive portal, a CDN error page served
 * with the wrong status. Bytes are decoded **before** they are written, so such a body fails the
 * lookup loudly and is never cached; caching it first would fail every later run on the same
 * tile, with no way out short of clearing the folder. For the same reason, a cached entry that no
 * longer decodes (written by an older version, or damaged) is deleted and fetched once more.
 *
 * Errors stay loud: a fetch or decode failure of a fresh tile throws, and the file being
 * processed fails, rather than yielding elevations that were never corrected.
 *
 * @param fetchBytes the network half. Injected by tests; the default downloads over HTTP.
 */
internal fun diskCachedElevationProvider(
    cacheFolder: File,
    config: ElevationProviderConfig = ElevationProviderConfig(),
    fetchBytes: suspend (String) -> ByteArray = ::fetchTileBytes,
): ElevationProvider =
    ElevationProvider(config) { url ->
        val cached = File(cacheFolder, cachePathFor(url))
        if (cached.isFile && cached.length() > 0) {
            decodeOrNull(cached.readBytes(), url)?.let { return@ElevationProvider it }
            cached.delete()
        }
        val fresh = fetchBytes(url)
        // Throws on a non-image body, before anything reaches the disk.
        val tile = decodeTileBytes(fresh, url)
        writeAtomically(cached, fresh)
        tile
    }

private suspend fun decodeOrNull(
    bytes: ByteArray,
    url: String,
): RawTile? =
    try {
        decodeTileBytes(bytes, url)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

/**
 * BatchCalculator fetches up to ten tiles concurrently: write to a private temp file and move it
 * into place so a half-written tile is never visible. Atomic where the filesystem supports it.
 */
private fun writeAtomically(
    target: File,
    bytes: ByteArray,
) {
    val parent = target.absoluteFile.parentFile
    parent.mkdirs()
    val tmp = Files.createTempFile(parent.toPath(), target.name, ".tmp")
    try {
        Files.write(tmp, bytes)
        try {
            Files.move(tmp, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } catch (e: IOException) {
        Files.deleteIfExists(tmp)
        throw e
    }
}

/** `https://host/z/x/y.webp` → `host/z/x/y.webp`, each segment scrubbed of anything path-hostile. */
private fun cachePathFor(url: String): String =
    url
        .substringAfter("://")
        .substringBefore('?')
        .split('/')
        .filter { it.isNotEmpty() && it != "." && it != ".." }
        .joinToString("/") { segment -> segment.replace(Regex("[^A-Za-z0-9._-]"), "_") }
