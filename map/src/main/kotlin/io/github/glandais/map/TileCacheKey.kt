package io.github.glandais.map

import java.security.MessageDigest

/**
 * The cache directory of a tile URL pattern, relative to the cache folder: `{z}/{x}/{y}.png` goes
 * underneath it. See [TileMapProducer]'s "Cache" section for why the key looks like this.
 *
 * The key is derived from the **pattern**, never from an expanded URL: expanding `{s}` picks a
 * random subdomain, and a key that depends on it would split one source over three caches.
 *
 * Built from:
 *
 * 1. the host, with `_{port}` appended when the port is explicit;
 * 2. every **whole** path segment before the first `{z}`/`{x}`/`{y}` placeholder;
 * 3. `h_{hash}` — a 12-hex-digit SHA-256 prefix of the pattern (scheme excluded) — only when (1)
 *    and (2) do not identify the source on their own: the remainder of the pattern is anything
 *    but the plain `{z}/{x}/{y}.png` (a query, another extension, a placeholder inside a
 *    segment…), a segment had to be sanitized or truncated, or there are credentials.
 *
 * `{s}` is written `_s_` wherever it appears and never counts as a difference, so its three
 * subdomains keep sharing one cache.
 *
 * Every segment is reduced to `[A-Za-z0-9._-]`, capped in length, and a segment made only of dots
 * is rewritten, so no pattern can produce `..`, an absolute path or a separator: the result always
 * stays under the cache folder.
 */
internal object TileCacheKey {
    /** Everything after the readable prefix, when the source is an ordinary slippy-map one. */
    private const val STANDARD_REMAINDER = "{z}/{x}/{y}.png"

    private val COORDINATES = listOf("{z}", "{x}", "{y}")

    private const val SUBDOMAIN = "{s}"

    /** How `{s}` reads on disk, in the host or in a path segment. */
    private const val SUBDOMAIN_ON_DISK = "_s_"

    private val UNSAFE = Regex("[^A-Za-z0-9._-]")

    /** Well under the 255 bytes most filesystems allow per name. */
    private const val MAX_SEGMENT = 64

    private const val HASH_LENGTH = 12

    fun of(urlPattern: String): String {
        val schemeEnd = urlPattern.indexOf("://")
        if (schemeEnd < 0) return "unknown/h_${hash(urlPattern)}"
        val afterScheme = urlPattern.substring(schemeEnd + 3)

        val authorityEnd = afterScheme.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) afterScheme.length else it }
        val authority = afterScheme.substring(0, authorityEnd)
        val firstCoordinate = COORDINATES.map { afterScheme.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: afterScheme.length
        if (firstCoordinate < authorityEnd) {
            // A coordinate in the host itself: nothing readable is fixed, the hash is the key.
            return "unknown/h_${hash(afterScheme)}"
        }

        var lossy = false
        val hostAndPort =
            authority.substringAfterLast('@').also { if (it != authority) lossy = true } // user info: keep it off the disk
        val (host, port) = splitPort(hostAndPort)
        val readable =
            mutableListOf(
                sanitize(host).let { (s, l) ->
                    lossy = lossy || l || s.isEmpty()
                    s.ifEmpty { "unknown" }
                },
            )
        if (port != null) {
            val (p, l) = sanitize(port)
            lossy = lossy || l
            readable[0] = "${readable[0]}_$p"
        }

        // Whole segments before the one holding the first coordinate; the rest is the remainder.
        val path = afterScheme.substring(authorityEnd)
        val prefixEnd = path.lastIndexOf('/', firstCoordinate - authorityEnd - 1)
        val fixed = if (prefixEnd <= 0) "" else path.substring(1, prefixEnd)
        val remainder = if (prefixEnd < 0) path else path.substring(prefixEnd + 1)
        if (fixed.isNotEmpty()) {
            for (segment in fixed.split('/')) {
                val (s, l) = sanitize(segment)
                lossy = lossy || l || s.isEmpty()
                if (s.isNotEmpty()) readable += s
            }
        }
        if (remainder != STANDARD_REMAINDER) lossy = true

        if (lossy) readable += "h_${hash(afterScheme)}"
        return readable.joinToString("/")
    }

    /** `host:port` → host and port; bracketed IPv6 hosts keep their colons. */
    private fun splitPort(hostAndPort: String): Pair<String, String?> {
        val colon = hostAndPort.lastIndexOf(':')
        if (colon < 0 || colon < hostAndPort.lastIndexOf(']')) return hostAndPort to null
        return hostAndPort.substring(0, colon) to hostAndPort.substring(colon + 1).ifEmpty { null }
    }

    /** A disk-safe segment, and whether anything but `{s}` had to change to get it. */
    private fun sanitize(segment: String): Pair<String, Boolean> {
        val safe = segment.split(SUBDOMAIN).joinToString(SUBDOMAIN_ON_DISK) { it.replace(UNSAFE, "_") }
        var result = if (safe.isNotEmpty() && safe.all { it == '.' }) "_".repeat(safe.length) else safe
        result = result.take(MAX_SEGMENT)
        return result to (result != segment.replace(SUBDOMAIN, SUBDOMAIN_ON_DISK))
    }

    private fun hash(text: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(HASH_LENGTH)
}
