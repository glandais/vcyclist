package io.github.glandais.map

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Fetches a single map tile.
 *
 * An interface rather than a hard-wired HTTP call so the renderer can be tested without a
 * network — a test that reaches the internet is a test that will eventually fail in CI for an
 * unrelated reason, and hammering a public tile server from a test suite is exactly the abuse
 * the usage policies forbid.
 */
fun interface TileFetcher {
    /**
     * @return the encoded image bytes, or `null` when the tile is unavailable. Returning `null`
     *   rather than throwing is deliberate: a map with one missing tile is more useful than an
     *   exception, and a tile server dropping a request should not fail a whole render.
     */
    fun fetch(url: String): ByteArray?
}

/**
 * [TileFetcher] over HTTP.
 *
 * ## Tile usage policy — read before choosing a URL
 *
 * Every public tile server imposes a usage policy, and OpenStreetMap's in particular requires a
 * **valid identifying User-Agent** and forbids bulk downloading. A generic or absent User-Agent
 * gets the source IP banned. That is why [userAgent] has no permissive default and why
 * `TileMapProducer` takes the URL pattern as a mandatory argument: choosing the source is the
 * caller's decision, and so is complying with its terms.
 *
 * @param userAgent sent on every request. Must identify the application.
 * @param timeout per-request timeout; a slow tile should not stall a whole render.
 */
class HttpTileFetcher(
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val timeout: Duration = Duration.ofSeconds(15),
    private val httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
) : TileFetcher {
    init {
        require(userAgent.isNotBlank()) { "A tile server requires an identifying User-Agent" }
    }

    override fun fetch(url: String): ByteArray? =
        try {
            val request =
                HttpRequest
                    .newBuilder(URI.create(url))
                    .setHeader("User-Agent", userAgent)
                    .timeout(timeout)
                    .GET()
                    .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
            // Status is checked, unlike the reference, which streams the body straight to the
            // cache file — so a 404 or a rate-limit page would be cached AS the tile and then
            // rendered as garbage. An error response is simply no tile.
            if (response.statusCode() in 200..299) response.body().takeIf { it.isNotEmpty() } else null
        } catch (e: java.io.IOException) {
            null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }

    companion object {
        /**
         * Identifies vcyclist to tile servers. Adapted from gpx2web's, which named that project.
         * Keep it specific: this string is what a server operator sees and what they block if the
         * traffic misbehaves.
         */
        const val DEFAULT_USER_AGENT: String = "vcyclist (https://github.com/glandais/vcyclist)"
    }
}
