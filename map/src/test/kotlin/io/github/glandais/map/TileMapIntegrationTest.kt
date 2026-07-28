package io.github.glandais.map

import io.github.glandais.elevation.MathConstants
import io.github.glandais.engine.path.Path
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Downloads real tiles. **Gated behind `INTEGRATION=1`**, like the `:elevation` integration
 * tests, and skipped otherwise.
 *
 * It is gated rather than merely slow-marked because it makes requests to a third-party tile
 * server. Running it on every build would put a public service under load it never agreed to,
 * which is the behaviour the usage policies exist to prevent — and it would make the suite fail
 * whenever the network hiccups. It fetches a handful of tiles at a low zoom, once.
 *
 * The tile source is taken from `VCYCLIST_TILE_URL` and has **no default**, for the same reason
 * the production API has none: whoever runs this chooses the source and accepts its terms.
 */
class TileMapIntegrationTest {
    private val enabled = System.getenv("INTEGRATION") == "1"
    private val tileUrl: String? = System.getenv("VCYCLIST_TILE_URL")

    private fun stelvio(): Path {
        val coords = listOf(46.5318 to 10.4439, 46.5325 to 10.4500, 46.5320 to 10.4591)
        val p = Path(coords.size)
        for ((i, ll) in coords.withIndex()) {
            p.setLatitude(i, ll.first * MathConstants.DEG_TO_RAD)
            p.setLongitude(i, ll.second * MathConstants.DEG_TO_RAD)
            p.setElevation(i, 2600.0)
            p.setTime(i, i * 1000.0)
        }
        p.computeDerivedData()
        return p
    }

    @Test
    fun `case 12 — real tiles produce a non-empty, readable image`() {
        if (!enabled) {
            println("Skipping: set INTEGRATION=1 to run (this test downloads real map tiles)")
            return
        }
        val url =
            tileUrl ?: run {
                println("Skipping: set VCYCLIST_TILE_URL to a tile source you are entitled to use")
                return
            }

        val cache =
            File.createTempFile("vcyclist-integration", "").let {
                it.delete()
                it.mkdirs()
                it
            }
        val out = File.createTempFile("vcyclist-integration-map", ".png")
        try {
            val map = TileMapProducer(cache).createTileMap(out, listOf(stelvio()), url, zoom = 13)
            assertTrue(out.length() > 0, "no PNG written")
            val image = ImageIO.read(out)
            assertEquals(map.width, image.width)
            assertEquals(map.height, image.height)
            // Something was actually drawn: the red track is present, over a background that is
            // not entirely red.
            //
            // Deliberately NOT "the image has many distinct colours". The tile source is chosen
            // by whoever runs this, so an assertion tuned to a real street map would fail against
            // a plain or synthetic source for no good reason. What must hold for ANY source is
            // that the background got drawn and the track went on top of it.
            var trackPixels = 0
            var backgroundPixels = 0
            for (x in 0 until image.width step 3) {
                for (y in 0 until image.height step 3) {
                    val c = java.awt.Color(image.getRGB(x, y))
                    if (c.red > c.blue + 40 && c.red > c.green + 40) trackPixels++ else backgroundPixels++
                }
            }
            assertTrue(trackPixels > 0, "the track was not drawn")
            assertTrue(backgroundPixels > 0, "no background behind the track")
            assertTrue(cache.walkTopDown().any { it.extension == "png" }, "tiles should have been cached")

            // A second render must be served entirely from the cache — the property that keeps
            // repeated use from becoming a bulk download.
            TileMapProducer(cache) { error("no tile may be fetched on a cached render") }
                .createTileMap(out, listOf(stelvio()), url, zoom = 13)
        } finally {
            out.delete()
            cache.deleteRecursively()
        }
    }
}
