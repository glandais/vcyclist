package io.github.glandais.cli

import io.github.glandais.elevation.ElevationProviderConfig
import kotlinx.coroutines.runBlocking
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The disk cache in front of the DEM tiles, with an injected transport — no network. What it
 * guards against: a 2xx whose body is not an image being cached and then failing every later run.
 */
class DiskCachedElevationTest {
    private val cacheDir: File =
        File.createTempFile("vcyclist-dem-cache", "").let {
            it.delete()
            it.mkdirs()
            it
        }

    @AfterTest
    fun cleanup() {
        cacheDir.deleteRecursively()
    }

    private val config =
        ElevationProviderConfig(
            tileUrlTemplate = "https://dem.example.invalid/{z}/{x}/{y}.png",
            tileSize = TILE_SIZE,
        )

    /** A Terrarium tile at a uniform [ELEVATION_M]: `(r * 256 + g + b / 256) - 32768`. */
    private val goodTile: ByteArray =
        run {
            val image = BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_RGB)
            val g = image.createGraphics()
            g.color = Color(128, ELEVATION_M.toInt(), 0)
            g.fillRect(0, 0, TILE_SIZE, TILE_SIZE)
            g.dispose()
            ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        }

    private val errorPage = "<html>Service temporarily unavailable</html>".toByteArray()

    private class CountingFetch(
        private val body: () -> ByteArray,
    ) : suspend (String) -> ByteArray {
        var calls = 0

        override suspend fun invoke(url: String): ByteArray {
            calls++
            return body()
        }
    }

    private fun elevation(fetch: suspend (String) -> ByteArray): Double =
        runBlocking { diskCachedElevationProvider(cacheDir, config, fetch).getElevation(46.5, 10.4) }

    private fun cachedFiles() = cacheDir.walkTopDown().filter { it.isFile }.toList()

    @Test
    fun `a non-image 200 body fails loudly and is not cached`() {
        assertFailsWith<Exception> { elevation(CountingFetch { errorPage }) }
        assertEquals(emptyList(), cachedFiles(), "an undecodable body must never reach the cache")

        // So the next run, once the server is healthy again, just works.
        val healthy = CountingFetch { goodTile }
        assertTrue(abs(elevation(healthy) - ELEVATION_M) < 1.0)
        assertEquals(1, healthy.calls)
    }

    @Test
    fun `a cached tile is reused with no fetch`() {
        elevation(CountingFetch { goodTile })
        assertEquals(1, cachedFiles().size)

        val second = CountingFetch { goodTile }
        assertTrue(abs(elevation(second) - ELEVATION_M) < 1.0)
        assertEquals(0, second.calls, "a cached tile must not be fetched again")
    }

    @Test
    fun `an undecodable cached entry is deleted and fetched again, once`() {
        elevation(CountingFetch { goodTile })
        val cached = cachedFiles().single()
        cached.writeBytes(errorPage)

        val refetch = CountingFetch { goodTile }
        assertTrue(abs(elevation(refetch) - ELEVATION_M) < 1.0)
        assertEquals(1, refetch.calls)
        assertTrue(ImageIO.read(cached) != null, "the corrupt entry must be replaced by the fresh tile")
        assertEquals(listOf(cached), cachedFiles(), "no temporary file may be left behind")
    }

    @Test
    fun `a corrupt cache entry whose re-fetch is also bad still fails loudly`() {
        elevation(CountingFetch { goodTile })
        cachedFiles().single().writeBytes(errorPage)

        val stillBroken = CountingFetch { errorPage }
        assertFailsWith<Exception> { elevation(stillBroken) }
        assertEquals(1, stillBroken.calls, "one retry, not a loop")
        assertEquals(emptyList(), cachedFiles())
    }

    @Test
    fun `a transport error propagates`() {
        assertFailsWith<IllegalStateException> {
            elevation { url -> error("Tile fetch failed for $url: HTTP 503") }
        }
        assertEquals(emptyList(), cachedFiles())
    }

    private companion object {
        const val TILE_SIZE = 256
        const val ELEVATION_M = 100.0
    }
}
