package io.github.glandais.cli

import io.github.glandais.elevation.ElevationProvider
import io.github.glandais.elevation.ElevationProviderConfig
import io.github.glandais.elevation.ElevationSource
import io.github.glandais.elevation.RawTile
import io.github.glandais.elevation.TileEncoding
import io.github.glandais.elevation.decodeBil32Tile
import io.github.glandais.elevation.decodeTileBytes
import io.github.glandais.elevation.fetchTileBytes
import io.github.glandais.elevation.pacedTileBytesFetcher
import io.github.glandais.elevation.provider
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * An [ElevationProvider] whose DEM tiles persist on disk under [cacheFolder] (task g34).
 *
 * Layout is `{cacheFolder}/{host}/{z}/{x}/{y}.webp` for an XYZ source — the same shape the map tile cache uses
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
 * A WMS source (IGN) puts the whole tile in the query string, so its tiles are filed under a hash
 * of it instead — `{host}/{path}/{sha256(query)}.bil` — or every tile would share one file.
 *
 * @param fetchBytes the network half. Injected by tests; the default downloads over HTTP.
 */
internal fun diskCachedElevationProvider(
    cacheFolder: File,
    config: ElevationProviderConfig = ElevationProviderConfig(),
    fetchBytes: suspend (String) -> ByteArray = ::fetchTileBytes,
): ElevationProvider = ElevationProvider(config, diskCachedFetcher(cacheFolder, config, fetchBytes))

/**
 * [source]'s provider, its fallback included, with every tier cached under [cacheFolder] and
 * decoded with its own encoding.
 */
internal fun diskCachedElevationProvider(
    cacheFolder: File,
    source: ElevationSource,
    zoomLevel: Int?,
    fetchBytes: suspend (String) -> ByteArray = ::fetchTileBytes,
): ElevationProvider = source.provider(zoomLevel) { config -> diskCachedFetcher(cacheFolder, config, fetchBytes) }

/**
 * The network half goes through [pacedTileBytesFetcher] at the source's own rate: a cache hit costs
 * the server nothing, so only misses are paced — which is exactly when a cold run hits IGN's 429.
 */
private fun diskCachedFetcher(
    cacheFolder: File,
    config: ElevationProviderConfig,
    fetchNetwork: suspend (String) -> ByteArray,
): suspend (String) -> RawTile {
    val fetchBytes = pacedTileBytesFetcher(config.maxRequestsPerSecond, fetchNetwork)
    return fetcher@{ url ->
        val cached = File(cacheFolder, cachePathFor(url, config.encoding))
        if (cached.isFile && cached.length() > 0) {
            decodeOrNull(cached.readBytes(), url, config)?.let { return@fetcher it }
            cached.delete()
        }
        val fresh = fetchBytes(url)
        // Throws on a body that is not a tile, before anything reaches the disk.
        val tile = decode(fresh, url, config)
        writeAtomically(cached, fresh)
        tile
    }
}

private suspend fun decode(
    bytes: ByteArray,
    url: String,
    config: ElevationProviderConfig,
): RawTile =
    when (config.encoding) {
        TileEncoding.TERRARIUM -> decodeTileBytes(bytes, url)
        TileEncoding.BIL32 -> decodeBil32Tile(bytes, config.tileSize, url)
    }

private suspend fun decodeOrNull(
    bytes: ByteArray,
    url: String,
    config: ElevationProviderConfig,
): RawTile? =
    try {
        decode(bytes, url, config)
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

/**
 * `https://host/z/x/y.webp` → `host/z/x/y.webp`, each segment scrubbed of anything path-hostile;
 * a URL with a query gets the query's hash as its last segment.
 */
internal fun cachePathFor(
    url: String,
    encoding: TileEncoding = TileEncoding.TERRARIUM,
): String {
    val withoutScheme = url.substringAfter("://")
    val query = withoutScheme.substringAfter('?', "")
    val segments =
        withoutScheme
            .substringBefore('?')
            .split('/')
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .map { segment -> segment.replace(Regex("[^A-Za-z0-9._-]"), "_") }
    if (query.isEmpty()) return segments.joinToString("/")
    val extension = if (encoding == TileEncoding.BIL32) "bil" else "tile"
    return (segments + "${sha256Hex(query)}.$extension").joinToString("/")
}

private fun sha256Hex(text: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(text.encodeToByteArray())
        .joinToString("") { b -> (b.toInt() and 0xFF).toString(16).padStart(2, '0') }
