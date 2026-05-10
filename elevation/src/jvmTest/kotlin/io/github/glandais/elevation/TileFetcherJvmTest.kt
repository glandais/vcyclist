package io.github.glandais.elevation

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TileFetcherJvmTest {
    private lateinit var server: HttpServer
    private var port: Int = 0

    @BeforeTest
    fun startServer() {
        server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { srv ->
                port = srv.address.port
                srv.createContext("/tile.png") { ex -> sendImage(ex, format = "png") }
                srv.createContext("/404") { ex ->
                    ex.sendResponseHeaders(404, -1)
                    ex.close()
                }
                srv.createContext("/500") { ex ->
                    ex.sendResponseHeaders(500, -1)
                    ex.close()
                }
                srv.createContext("/corrupt") { ex ->
                    val bogus = "not an image".toByteArray()
                    ex.sendResponseHeaders(200, bogus.size.toLong())
                    ex.responseBody.use { it.write(bogus) }
                }
                srv.start()
            }
    }

    @AfterTest
    fun stopServer() {
        server.stop(0)
    }

    private fun sendImage(
        ex: HttpExchange,
        format: String,
    ) {
        val img =
            BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB).apply {
                // Encode four distinct Terrarium-friendly pixels.
                setRGB(0, 0, 0xFF800000.toInt()) // R=128 G=0   B=0   → 0 m
                setRGB(1, 0, 0xFF83E800.toInt()) // R=131 G=232 B=0   → 1000 m
                setRGB(0, 1, 0xFF7C1800.toInt()) // R=124 G=24  B=0   → -1000 m
                setRGB(1, 1, 0xFFFFFFFF.toInt()) // R=255 G=255 B=255 → ≈32768 m
            }
        val baos = ByteArrayOutputStream().apply { ImageIO.write(img, format, this) }
        ex.responseHeaders.add("Content-Type", "image/$format")
        ex.sendResponseHeaders(200, baos.size().toLong())
        ex.responseBody.use { it.write(baos.toByteArray()) }
    }

    @Test
    fun `decodes PNG tile`() =
        runTest {
            val tile = fetchAndDecodeTile("http://127.0.0.1:$port/tile.png")
            assertEquals(2, tile.width)
            assertEquals(2, tile.height)
            assertEquals(16, tile.rgba.size)
            assertEquals(
                0.0,
                Tile.decodeTerrariumElevation(
                    tile.rgba[0].toInt() and 0xFF,
                    tile.rgba[1].toInt() and 0xFF,
                    tile.rgba[2].toInt() and 0xFF,
                ),
            )
            assertEquals(
                1000.0,
                Tile.decodeTerrariumElevation(
                    tile.rgba[4].toInt() and 0xFF,
                    tile.rgba[5].toInt() and 0xFF,
                    tile.rgba[6].toInt() and 0xFF,
                ),
            )
        }

    @Test
    fun `TwelveMonkeys registers a WebP ImageReader via SPI`() {
        // imageio-webp 3.x is read-only — we can't write a WebP fixture here, but the SPI
        // registration is the part that matters for production tile decoding.
        val readers = ImageIO.getImageReadersByFormatName("webp")
        assertTrue(readers.hasNext(), "Expected a WebP ImageReader registered via SPI")
    }

    @Test
    fun `throws on HTTP 404 with exact message`() =
        runTest {
            val ex =
                assertFailsWith<IllegalStateException> {
                    fetchAndDecodeTile("http://127.0.0.1:$port/404")
                }
            assertEquals("Tile fetch failed for http://127.0.0.1:$port/404: HTTP 404", ex.message)
        }

    @Test
    fun `throws on HTTP 500`() =
        runTest {
            val ex =
                assertFailsWith<IllegalStateException> {
                    fetchAndDecodeTile("http://127.0.0.1:$port/500")
                }
            assertTrue(ex.message!!.contains("HTTP 500"), "message=${ex.message}")
        }

    @Test
    fun `throws on corrupt body with no decoder`() =
        runTest {
            val ex =
                assertFailsWith<IllegalStateException> {
                    fetchAndDecodeTile("http://127.0.0.1:$port/corrupt")
                }
            assertTrue(ex.message!!.startsWith("No ImageIO decoder for tile at "), "message=${ex.message}")
        }
}
