package io.github.glandais.map

import io.github.glandais.elevation.MathConstants
import io.github.glandais.engine.path.Path
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
        // ...but the gap is neither silent nor black.
        assertTrue(map.tileCount > 0)
        assertEquals(map.tileCount, map.missingTileCount, "every tile failed, every tile must be counted")
        val corner = Color(ImageIO.read(file).getRGB(0, 0))
        assertEquals(TileMapProducer.MISSING_TILE_COLOR, corner, "a missing tile must be painted grey, not black")
    }

    @Test
    fun `case 16 — a single missing tile is counted, the others are drawn`() {
        val complete =
            TileMapProducer(cacheDir, RecordingFetcher())
                .createTileMap(outputFile(), listOf(stelvio()), urlPattern, zoom = 14)
        assertTrue(complete.tileCount > 1, "the fixture must span several tiles for this test to mean anything")
        assertEquals(0, complete.missingTileCount)

        cacheDir.deleteRecursively()
        var first: String? = null
        val oneFails =
            RecordingFetcher(failFor = { url ->
                if (first == null) first = url
                url == first
            })
        val file = outputFile()
        val map =
            TileMapProducer(cacheDir, oneFails)
                .createTileMap(file, listOf(stelvio()), urlPattern, zoom = 14)

        assertEquals(complete.tileCount, map.tileCount)
        assertEquals(1, map.missingTileCount)
        val image = ImageIO.read(file)
        var grey = 0
        var blue = 0
        for (x in 0 until image.width) {
            for (y in 0 until image.height) {
                when (Color(image.getRGB(x, y))) {
                    TileMapProducer.MISSING_TILE_COLOR -> grey++
                    Color.BLUE -> blue++
                }
            }
        }
        assertTrue(grey > 0, "the missing tile must show as grey")
        assertTrue(blue > 0, "the tiles that were fetched must still be drawn")
    }

    @Test
    fun `case 17 — an undecodable cached tile is deleted and fetched again`() {
        TileMapProducer(cacheDir, RecordingFetcher())
            .createTileMap(outputFile(), listOf(stelvio()), urlPattern, zoom = 12)
        val cached = cacheDir.walkTopDown().filter { it.extension == "png" }.toList()
        assertTrue(cached.isNotEmpty())
        // What an interrupted write, or an HTML error page cached by an older version, looks like.
        cached.forEach { it.writeText("<html>429 Too Many Requests</html>") }

        val fetcher = RecordingFetcher()
        val map =
            TileMapProducer(cacheDir, fetcher)
                .createTileMap(outputFile(), listOf(stelvio()), urlPattern, zoom = 12)

        assertEquals(cached.size, fetcher.requested.size, "every corrupt entry must be re-fetched")
        assertEquals(0, map.missingTileCount)
        for (file in cached) {
            assertTrue(ImageIO.read(file) != null, "the corrupt entry must be replaced by a decodable tile: $file")
        }
    }

    @Test
    fun `case 18 — the cache is written through a temp file that does not outlive the write`() {
        TileMapProducer(cacheDir, RecordingFetcher())
            .createTileMap(outputFile(), listOf(stelvio()), urlPattern, zoom = 14)
        val leftovers = cacheDir.walkTopDown().filter { it.isFile && it.extension != "png" }.toList()
        assertEquals(emptyList(), leftovers, "temporary files left in the cache")
        assertTrue(cacheDir.walkTopDown().any { it.extension == "png" })
    }

    @Test
    fun `case 19 — a truncated cached PNG is re-fetched and replaced`() {
        TileMapProducer(cacheDir, RecordingFetcher())
            .createTileMap(outputFile(), listOf(stelvio()), urlPattern, zoom = 12)
        val cached = cacheDir.walkTopDown().filter { it.extension == "png" }.toList()
        assertTrue(cached.isNotEmpty())
        val intact = cached.associateWith { it.readBytes() }
        // A crash mid-write in an older version: a valid PNG header, then nothing.
        cached.forEach { it.writeBytes(intact.getValue(it).copyOf(40)) }

        val fetcher = RecordingFetcher()
        val map =
            TileMapProducer(cacheDir, fetcher, MissingTilePolicy.FAIL)
                .createTileMap(outputFile(), listOf(stelvio()), urlPattern, zoom = 12)

        assertEquals(cached.size, fetcher.requested.size, "every truncated entry must be re-fetched")
        assertEquals(0, map.missingTileCount)
        for (file in cached) {
            assertTrue(intact.getValue(file).contentEquals(file.readBytes()), "not replaced by the fetched tile: $file")
        }
        assertNoTempFiles()
    }

    @Test
    fun `case 20 — FAIL, a tile the fetcher cannot provide throws and writes no output`() {
        val file = outputFile().also { it.delete() }
        val fetcher = RecordingFetcher(failFor = { true })
        val e =
            assertFailsWith<IOException> {
                TileMapProducer(cacheDir, fetcher, MissingTilePolicy.FAIL)
                    .createTileMap(file, listOf(stelvio()), urlPattern, zoom = 12)
            }

        val url = fetcher.requested.single()
        assertEquals(1, fetcher.requested.size, "a strict render must stop at the first missing tile")
        val (z, x, y) = url.substringAfter("invalid/").removeSuffix(".png").split("/")
        assertTrue(e.message!!.contains(url), "the URL must be named: ${e.message}")
        assertTrue(e.message!!.contains("z=$z x=$x y=$y"), "z/x/y must be named: ${e.message}")
        assertFalse(file.exists(), "no output file may be left behind")
        assertNoTempFiles()
    }

    @Test
    fun `case 21 — FAIL, undecodable bytes throw and are not cached`() {
        val file = outputFile().also { it.delete() }
        val garbage = TileFetcher { "<html>503 Service Unavailable</html>".toByteArray() }
        val e =
            assertFailsWith<IOException> {
                TileMapProducer(cacheDir, garbage, MissingTilePolicy.FAIL)
                    .createTileMap(file, listOf(stelvio()), urlPattern, zoom = 12)
            }

        assertTrue(e.message!!.contains("do not decode"), "the reason must be given: ${e.message}")
        assertFalse(file.exists(), "no output file may be left behind")
        assertEquals(emptyList(), cacheDir.walkTopDown().filter { it.isFile }.toList(), "nothing may be cached")
    }

    @Test
    fun `case 22 — SKIP is the default, and FAIL renders normally when every tile is there`() {
        val skipped =
            TileMapProducer(cacheDir, RecordingFetcher(failFor = { true }))
                .createTileMap(outputFile(), listOf(stelvio()), urlPattern, zoom = 12)
        assertEquals(skipped.tileCount, skipped.missingTileCount)
        val explicit =
            TileMapProducer(cacheDir, RecordingFetcher(failFor = { true }), MissingTilePolicy.SKIP)
                .createTileMap(outputFile(), listOf(stelvio()), urlPattern, zoom = 12)
        assertEquals(explicit.tileCount, explicit.missingTileCount)

        val file = outputFile()
        val strict =
            TileMapProducer(cacheDir, RecordingFetcher(), MissingTilePolicy.FAIL)
                .createTileMap(file, listOf(stelvio()), urlPattern, zoom = 12)
        assertEquals(0, strict.missingTileCount)
        assertEquals(strict.width, ImageIO.read(file).width)
    }

    @Test
    fun `case 23 — a failing fetch leaves no temp file in the cache`() {
        var first: String? = null
        val oneFails =
            RecordingFetcher(failFor = { url ->
                if (first == null) first = url
                url == first
            })
        TileMapProducer(cacheDir, oneFails)
            .createTileMap(outputFile(), listOf(stelvio()), urlPattern, zoom = 14)
        assertTrue(cacheDir.walkTopDown().any { it.extension == "png" })
        assertNoTempFiles()
    }

    private fun assertNoTempFiles() {
        val leftovers = cacheDir.walkTopDown().filter { it.isFile && it.extension != "png" }.toList()
        assertEquals(emptyList(), leftovers, "temporary files left in the cache")
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
    fun `case 15 — the cache is laid out by source, zoom, x and y`() {
        TileMapProducer(cacheDir, RecordingFetcher())
            .createTileMap(outputFile(), listOf(stelvio()), urlPattern, zoom = 12)
        val cached = cacheDir.walkTopDown().first { it.extension == "png" }
        val relative = cached.relativeTo(cacheDir).path.replace(File.separatorChar, '/')
        assertTrue(relative.startsWith("tiles.example.invalid/12/"), "unexpected cache layout: $relative")
        assertTrue(relative.endsWith(".png"))
    }

    /**
     * A distinct solid colour per source, so a render drawn from the wrong source's cache shows.
     * Counts the calls; `null` for any URL containing [missing].
     */
    private class ColourBySourceFetcher(
        private val missing: String? = null,
    ) : TileFetcher {
        val requested = mutableListOf<String>()

        override fun fetch(url: String): ByteArray? {
            requested.add(url)
            if (missing != null && url.contains(missing)) return null
            val image = BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB)
            val g = image.createGraphics()
            g.color = colourOf(url)
            g.fillRect(0, 0, 256, 256)
            g.dispose()
            val out = ByteArrayOutputStream()
            ImageIO.write(image, "png", out)
            return out.toByteArray()
        }

        companion object {
            fun colourOf(url: String): Color =
                when {
                    url.contains("/styles/a/") -> Color.BLUE
                    url.contains("/styles/b/") -> Color.GREEN
                    url.contains(":9090/") -> Color.MAGENTA
                    else -> Color.WHITE
                }
        }
    }

    /** Colour of the rendered map's corner — background, since the track is nowhere near it. */
    private fun cornerOf(file: File) = Color(ImageIO.read(file).getRGB(0, 0))

    private fun cacheDirectories(): Set<String> =
        cacheDir
            .walkTopDown()
            .filter { it.extension == "png" }
            .map {
                it.parentFile.parentFile.parentFile
                    .relativeTo(cacheDir)
                    .path
                    .replace(File.separatorChar, '/')
            }.toSet()

    @Test
    fun `case 24 — two styles of one server do not share cached tiles`() {
        val a = "http://tileserver:8080/styles/a/256/{z}/{x}/{y}.png"
        val b = "http://tileserver:8080/styles/b/256/{z}/{x}/{y}.png"

        val first = ColourBySourceFetcher()
        val fileA = outputFile()
        TileMapProducer(cacheDir, first).createTileMap(fileA, listOf(stelvio()), a, zoom = 12)
        assertTrue(first.requested.isNotEmpty())
        assertEquals(Color.BLUE, cornerOf(fileA))

        val second = ColourBySourceFetcher()
        val fileB = outputFile()
        TileMapProducer(cacheDir, second).createTileMap(fileB, listOf(stelvio()), b, zoom = 12)
        assertEquals(first.requested.size, second.requested.size, "style b must be fetched, not read from a's cache")
        assertTrue(second.requested.all { it.contains("/styles/b/") })
        assertEquals(Color.GREEN, cornerOf(fileB), "style b drawn with style a's tiles")

        assertEquals(
            setOf("tileserver_8080/styles/a/256", "tileserver_8080/styles/b/256"),
            cacheDirectories(),
        )
    }

    @Test
    fun `case 25 — two ports of one host do not share cached tiles`() {
        val first = ColourBySourceFetcher()
        TileMapProducer(cacheDir, first)
            .createTileMap(outputFile(), listOf(stelvio()), "http://localhost:8080/{z}/{x}/{y}.png", zoom = 12)

        val second = ColourBySourceFetcher()
        val file = outputFile()
        TileMapProducer(cacheDir, second)
            .createTileMap(file, listOf(stelvio()), "http://localhost:9090/{z}/{x}/{y}.png", zoom = 12)
        assertEquals(first.requested.size, second.requested.size, "port 9090 must be fetched, not read from 8080's cache")
        assertEquals(Color.MAGENTA, cornerOf(file))

        assertEquals(setOf("localhost_8080", "localhost_9090"), cacheDirectories())
    }

    @Test
    fun `case 26 — the subdomain placeholder does not split the cache`() {
        val pattern = "https://{s}.tiles.example.invalid/{z}/{x}/{y}.png"
        val first = ColourBySourceFetcher()
        TileMapProducer(cacheDir, first).createTileMap(outputFile(), listOf(stelvio()), pattern, zoom = 14)
        assertTrue(first.requested.size > 1)

        // Several renders, so several random draws of the subdomain: none may miss the cache.
        repeat(10) {
            val again = ColourBySourceFetcher()
            TileMapProducer(cacheDir, again).createTileMap(outputFile(), listOf(stelvio()), pattern, zoom = 14)
            assertEquals(emptyList(), again.requested, "render ${it + 2} missed the cache")
        }
        assertEquals(setOf("_s_.tiles.example.invalid"), cacheDirectories())
    }

    @Test
    fun `case 27 — FAIL still throws when another style of the same server has the area cached`() {
        // The Pédalons report: a render in a style that does not exist must fail, even after
        // another style has put the very same z/x/y in the cache.
        TileMapProducer(cacheDir, ColourBySourceFetcher())
            .createTileMap(outputFile(), listOf(stelvio()), "http://tileserver:8080/styles/a/256/{z}/{x}/{y}.png", zoom = 12)

        val fetcher = ColourBySourceFetcher(missing = "/no-such-style/")
        val file = outputFile().also { it.delete() }
        assertFailsWith<IOException> {
            TileMapProducer(cacheDir, fetcher, MissingTilePolicy.FAIL)
                .createTileMap(file, listOf(stelvio()), "http://tileserver:8080/styles/no-such-style/256/{z}/{x}/{y}.png", zoom = 12)
        }
        assertEquals(1, fetcher.requested.size)
        assertFalse(file.exists())
    }

    @Test
    fun `case 28 — a hostile or odd pattern cannot write outside the cache folder`() {
        val patterns =
            listOf(
                "http://tileserver/../../../../tmp/evil/{z}/{x}/{y}.png",
                "http://tileserver/%2e%2e/%2e%2e/{z}/{x}/{y}.png",
                "http://tileserver/a/./b/../{z}/{x}/{y}.png",
                "http://..:8080/{z}/{x}/{y}.png",
                "http://user:secret@tileserver/{z}/{x}/{y}.png",
                "http://tileserver/st yles/<dark>|\\?*\"/{z}/{x}/{y}.png",
                "http://tileserver//absolute//{z}/{x}/{y}.png",
                "http://tileserver/tiles/z{z}/{x}/{y}.png?style=../../x",
                "file:///etc/{z}/{x}/{y}.png",
                "not a url {z} {x} {y}",
            )
        val root = cacheDir.canonicalFile
        val keys = mutableSetOf<String>()
        for (pattern in patterns) {
            val key = TileCacheKey.of(pattern)
            assertTrue(keys.add(key), "two patterns share the key $key")
            for (segment in key.split('/')) {
                assertTrue(segment.matches(Regex("[A-Za-z0-9._-]+")), "unsafe segment '$segment' in $key ($pattern)")
                assertFalse(segment.all { it == '.' }, "dots-only segment in $key ($pattern)")
            }
            TileMapProducer(cacheDir, ColourBySourceFetcher())
                .createTileMap(outputFile(), listOf(stelvio()), pattern, zoom = 12)
        }
        val written = cacheDir.walkTopDown().filter { it.isFile }.toList()
        assertTrue(written.isNotEmpty())
        for (file in written) {
            assertTrue(file.canonicalFile.startsWith(root), "written outside the cache: $file")
        }
    }

    @Test
    fun `case 29 — the cache key is readable for ordinary sources and hashed for the rest`() {
        assertEquals("_s_.tile.openstreetmap.org", TileCacheKey.of("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"))
        assertEquals(
            "tileserver_8080/styles/colorful/256",
            TileCacheKey.of("http://tileserver:8080/styles/colorful/256/{z}/{x}/{y}.png"),
        )
        // The scheme does not make a different source.
        assertEquals(TileCacheKey.of("http://h/{z}/{x}/{y}.png"), TileCacheKey.of("https://h/{z}/{x}/{y}.png"))

        // Whatever the readable part cannot carry lands in a hash, so it still tells sources apart.
        val light = TileCacheKey.of("https://h/t/{z}/{x}/{y}.png?style=light")
        val dark = TileCacheKey.of("https://h/t/{z}/{x}/{y}.png?style=dark")
        assertTrue(light.startsWith("h/t/h_") && dark.startsWith("h/t/h_"), "$light / $dark")
        assertTrue(light != dark)
        val retina = TileCacheKey.of("https://h/t/{z}/{x}/{y}@2x.png")
        assertTrue(retina.startsWith("h/t/h_") && retina != TileCacheKey.of("https://h/t/{z}/{x}/{y}.png"))
        // A placeholder inside a segment: the whole segments before it stay readable.
        assertTrue(TileCacheKey.of("https://h/tiles/z{z}/{x}/{y}.png").startsWith("h/tiles/h_"))
    }
}
