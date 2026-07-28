package io.github.glandais.map

import com.sun.net.httpserver.HttpServer
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [HttpTileFetcher] against a **local** server from the JDK, so the HTTP path is genuinely
 * exercised — headers, status handling, empty bodies — without touching a public tile server.
 *
 * The User-Agent assertion matters more than it looks: OpenStreetMap's usage policy makes a
 * valid identifying User-Agent a hard requirement, and sending a generic one gets the source IP
 * banned. Checking it through a fake fetcher would prove nothing, since the header is set inside
 * the HTTP implementation; a real request is the only way to see what is actually sent.
 */
class HttpTileFetcherTest {
    private lateinit var server: HttpServer
    private val receivedUserAgents = mutableListOf<String?>()
    private var port: Int = 0

    private fun pngBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB), "png", out)
        return out.toByteArray()
    }

    @BeforeTest
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        port = server.address.port

        server.createContext("/ok") { exchange ->
            receivedUserAgents.add(exchange.requestHeaders.getFirst("User-Agent"))
            val body = pngBytes()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/missing") { exchange ->
            receivedUserAgents.add(exchange.requestHeaders.getFirst("User-Agent"))
            val body = "<html>not found</html>".toByteArray()
            exchange.sendResponseHeaders(404, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/empty") { exchange ->
            exchange.sendResponseHeaders(200, -1)
            exchange.responseBody.close()
        }
        server.start()
    }

    @AfterTest
    fun stopServer() {
        server.stop(0)
    }

    private fun url(path: String) = "http://127.0.0.1:$port$path"

    @Test
    fun `case 11 — the identifying User-Agent is actually sent`() {
        val fetcher = HttpTileFetcher(userAgent = "vcyclist-test/1.0 (+https://example.invalid)")
        assertNotNull(fetcher.fetch(url("/ok")))
        assertEquals(listOf<String?>("vcyclist-test/1.0 (+https://example.invalid)"), receivedUserAgents)
    }

    @Test
    fun `the default User-Agent names vcyclist and carries a contact URL`() {
        // What a tile server operator sees, and what they would block. It must not be generic.
        val ua = HttpTileFetcher.DEFAULT_USER_AGENT
        assertTrue(ua.contains("vcyclist"), "the User-Agent must identify the application: $ua")
        assertTrue(ua.contains("https://"), "the User-Agent should carry a contact URL: $ua")
        HttpTileFetcher(userAgent = ua).fetch(url("/ok"))
        assertEquals(listOf<String?>(ua), receivedUserAgents)
    }

    @Test
    fun `a blank User-Agent is rejected at construction`() {
        // Failing loudly here is kinder than being silently banned by the tile source later.
        assertFailsWith<IllegalArgumentException> { HttpTileFetcher(userAgent = "   ") }
    }

    @Test
    fun `an error response yields no tile rather than a cached error page`() {
        // The reference streams the response body straight into the cache file, so a 404 page
        // would be stored and later rendered as the tile. Status is checked instead.
        assertNull(HttpTileFetcher().fetch(url("/missing")))
    }

    @Test
    fun `an empty body yields no tile`() {
        assertNull(HttpTileFetcher().fetch(url("/empty")))
    }

    @Test
    fun `an unreachable host yields no tile instead of throwing`() {
        // A render must survive a dead tile source.
        assertNull(HttpTileFetcher().fetch("http://127.0.0.1:1/nope/0/0/0.png"))
    }
}
