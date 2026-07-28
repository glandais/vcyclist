package io.github.glandais.map

import io.github.glandais.elevation.MathConstants
import io.github.glandais.engine.path.Path
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tile rendering, with **no network access whatsoever** — a fake [TileFetcher] returns tiles
 * generated in memory. A unit test that downloads is one that eventually fails in CI for an
 * unrelated reason, and pulling from a public tile server on every build is precisely the abuse
 * their usage policies forbid.
 */
class TileMapProducerTest {
    private val cacheDir: File =
        File.createTempFile("vcyclist-tiles", "").let {
            it.delete()
            it.mkdirs()
            it
        }

    @AfterTest
    fun cleanup() {
        cacheDir.deleteRecursively()
    }

    /** Records every URL asked for and answers with a solid-blue PNG. */
    private class RecordingFetcher(
        private val color: Color = Color.BLUE,
        private val failFor: (String) -> Boolean = { false },
    ) : TileFetcher {
        val requested = mutableListOf<String>()

        override fun fetch(url: String): ByteArray? {
            requested.add(url)
            if (failFor(url)) return null
            val image = BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB)
            val g = image.createGraphics()
            g.color = color
            g.fillRect(0, 0, 256, 256)
            g.dispose()
            val out = ByteArrayOutputStream()
            ImageIO.write(image, "png", out)
            return out.toByteArray()
        }
    }

    private val urlPattern = "https://tiles.example.invalid/{z}/{x}/{y}.png"

    private fun pathOf(vararg latLon: Pair<Double, Double>): Path {
        val p = Path(latLon.size)
        for ((i, ll) in latLon.withIndex()) {
            p.setLatitude(i, ll.first * MathConstants.DEG_TO_RAD)
            p.setLongitude(i, ll.second * MathConstants.DEG_TO_RAD)
            p.setElevation(i, 100.0)
            p.setTime(i, i * 1000.0)
        }
        p.computeDerivedData()
        return p
    }

    private fun stelvio() = pathOf(46.5318 to 10.4439, 46.5325 to 10.4500, 46.5320 to 10.4591)

    private fun outputFile() = File.createTempFile("vcyclist-map", ".png").also { it.deleteOnExit() }

    @Test
    fun `case 01 — maxSize framing fetches the tiles covering the bounds`() {
        val fetcher = RecordingFetcher()
        val map =
            TileMapProducer(cacheDir, fetcher)
                .createTileMap(outputFile(), listOf(stelvio()), urlPattern, maxSize = 512)

        assertTrue(fetcher.requested.isNotEmpty(), "expected at least one tile request")
        assertTrue(map.width <= 512 && map.height <= 512, "framing must respect maxSize")
        // Every requested tile must be at the chosen zoom and inside the frame's tile range.
        val iMin = kotlin.math.floor(map.getTileI(map.minLon)).toInt()
        val iMax = kotlin.math.ceil(map.getTileI(map.maxLon)).toInt()
        for (url in fetcher.requested) {
            val parts = url.substringAfter("invalid/").removeSuffix(".png").split("/")
            assertEquals(map.zoom, parts[0].toInt(), "tile requested at the wrong zoom: $url")
            assertTrue(parts[1].toInt() in iMin..iMax, "tile column outside the frame: $url")
        }
    }

    @Test
    fun `case 02 — an explicit zoom is honoured`() {
        for (zoom in listOf(10, 12, 14)) {
            val fetcher = RecordingFetcher()
            val map =
                TileMapProducer(cacheDir, fetcher)
                    .createTileMap(outputFile(), listOf(stelvio()), urlPattern, zoom = zoom)
            assertEquals(zoom, map.zoom)
            assertTrue(fetcher.requested.all { it.contains("/$zoom/") }, "all tiles must come from zoom $zoom")
        }
    }

    @Test
    fun `case 03 — explicit dimensions produce an image of exactly that size`() {
        val file = outputFile()
        val map =
            TileMapProducer(cacheDir, RecordingFetcher())
                .createTileMap(file, listOf(stelvio()), urlPattern, width = 640, height = 480)
        assertEquals(640, map.width)
        assertEquals(480, map.height)
        assertEquals(640, ImageIO.read(file).width)
        assertEquals(480, ImageIO.read(file).height)
    }

    @Test
    fun `case 04 — a tile absent from the cache is fetched once`() {
        val fetcher = RecordingFetcher()
        TileMapProducer(cacheDir, fetcher)
            .createTileMap(outputFile(), listOf(stelvio()), urlPattern, zoom = 12)
        val distinct = fetcher.requested.toSet()
        assertEquals(distinct.size, fetcher.requested.size, "each tile must be requested at most once per render")
        assertTrue(cacheDir.walkTopDown().any { it.extension == "png" }, "tiles must be written to the cache")
    }

    @Test
    fun `case 05 and 06 — a second render is served entirely from cache, with no fetches`() {
        val first = RecordingFetcher()
        TileMapProducer(cacheDir, first)
            .createTileMap(outputFile(), listOf(stelvio()), urlPattern, zoom = 12)
        assertTrue(first.requested.isNotEmpty())

        val second = RecordingFetcher()
        TileMapProducer(cacheDir, second)
            .createTileMap(outputFile(), listOf(stelvio()), urlPattern, zoom = 12)
        assertEquals(emptyList(), second.requested, "a cached render must make zero requests")
    }

    @Test
    fun `case 07 — a tile that cannot be downloaded leaves a gap instead of throwing`() {
        // The important one: a partly-downloaded map is more useful than an exception.
        val everythingFails = RecordingFetcher(failFor = { true })
        val file = outputFile()
        val map =
            TileMapProducer(cacheDir, everythingFails)
                .createTileMap(file, listOf(stelvio()), urlPattern, zoom = 12)

        assertTrue(everythingFails.requested.isNotEmpty(), "it should still have tried")
        assertTrue(file.length() > 0, "a PNG must still be written")
        assertEquals(map.width, ImageIO.read(file).width)
        // Nothing was cached, so a later render retries rather than being permanently blank.
        assertTrue(cacheDir.walkTopDown().none { it.extension == "png" }, "failures must not be cached")
    }

    @Test
    fun `case 08 — the track is drawn on top of the background`() {
        val file = outputFile()
        TileMapProducer(cacheDir, RecordingFetcher(color = Color.WHITE))
            .createTileMap(file, listOf(stelvio()), urlPattern, zoom = 14, colors = listOf(Color.RED))

        val image = ImageIO.read(file)
        var reddish = 0
        for (x in 0 until image.width) {
            for (y in 0 until image.height) {
                val c = Color(image.getRGB(x, y))
                if (c.red > c.blue + 40 && c.red > c.green + 40) reddish++
            }
        }
        assertTrue(reddish > 0, "expected the red track over the white background, found none")
    }

    @Test
    fun `case 09 — every path of a multi-track render is drawn`() {
        val file = outputFile()
        val west = pathOf(46.5318 to 10.4439, 46.5325 to 10.4460)
        val east = pathOf(46.5300 to 10.4550, 46.5310 to 10.4591)
        TileMapProducer(cacheDir, RecordingFetcher(color = Color.WHITE))
            .createTileMap(
                file,
                listOf(west, east),
                urlPattern,
                zoom = 14,
                colors = listOf(Color.RED, Color.GREEN),
            )

        val image = ImageIO.read(file)
        var red = 0
        var green = 0
        for (x in 0 until image.width) {
            for (y in 0 until image.height) {
                val c = Color(image.getRGB(x, y))
                if (c.red > c.blue + 40 && c.red > c.green + 40) red++
                if (c.green > c.red + 40 && c.green > c.blue + 40) green++
            }
        }
        assertTrue(red > 0, "first track missing")
        assertTrue(green > 0, "second track missing, colours must cycle per path")
    }

    @Test
    fun `case 10 — the PNG is readable and correctly sized`() {
        val file = outputFile()
        val map =
            TileMapProducer(cacheDir, RecordingFetcher())
                .createTileMap(file, listOf(stelvio()), urlPattern, maxSize = 800)
        val read = ImageIO.read(file)
        assertEquals(map.width, read.width)
        assertEquals(map.height, read.height)
    }

    @Test
    fun `case 13 — the URL pattern is mandatory and framing modes are exclusive`() {
        val producer = TileMapProducer(cacheDir, RecordingFetcher())
        assertFailsWith<IllegalArgumentException> {
            producer.createTileMap(outputFile(), listOf(stelvio()), "  ", maxSize = 256)
        }
        // No framing mode at all.
        assertFailsWith<IllegalArgumentException> {
            producer.createTileMap(outputFile(), listOf(stelvio()), urlPattern)
        }
        // Two at once is ambiguous rather than silently preferring one.
        assertFailsWith<IllegalArgumentException> {
            producer.createTileMap(outputFile(), listOf(stelvio()), urlPattern, maxSize = 256, zoom = 12)
        }
    }

    @Test
    fun `case 14 — the subdomain placeholder is substituted`() {
        val fetcher = RecordingFetcher()
        TileMapProducer(cacheDir, fetcher)
            .createTileMap(outputFile(), listOf(stelvio()), "https://{s}.tiles.example.invalid/{z}/{x}/{y}.png", zoom = 12)
        assertTrue(fetcher.requested.isNotEmpty())
        for (url in fetcher.requested) {
            assertTrue(Regex("^https://[abc]\\.tiles").containsMatchIn(url), "unsubstituted subdomain: $url")
        }
    }

    @Test
    fun `case 15 — the cache is laid out by host, zoom, x and y`() {
        TileMapProducer(cacheDir, RecordingFetcher())
            .createTileMap(outputFile(), listOf(stelvio()), urlPattern, zoom = 12)
        val cached = cacheDir.walkTopDown().first { it.extension == "png" }
        val relative = cached.relativeTo(cacheDir).path.replace(File.separatorChar, '/')
        assertTrue(relative.contains("tiles.example.invalid/12/"), "unexpected cache layout: $relative")
        assertTrue(relative.endsWith(".png"))
    }
}
