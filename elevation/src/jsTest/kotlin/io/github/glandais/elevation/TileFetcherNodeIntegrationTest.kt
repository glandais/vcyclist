package io.github.glandais.elevation

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Live HTTP integration test of the Node tile fetcher (task 32).
 *
 * Gated by `INTEGRATION=1` (env propagated to KotlinJsTest by `build.gradle.kts`).
 * Skips silently in browser environments where `process` is undefined.
 */
class TileFetcherNodeIntegrationTest {
    @Test
    fun montBlancTileDecodes() =
        runTest {
            if (!integrationEnabled()) return@runTest
            // Mont Blanc tile at zoom 12 : x=2138, y=1466 (Web Mercator). Mapterhorn serves
            // 512 × 512 high-DPI WebP tiles.
            val url = "https://tiles.mapterhorn.com/12/2138/1466.webp"
            val tile = fetchAndDecodeTile(url)
            assertEquals(tile.width, tile.height, "tile should be square")
            assertTrue(tile.width >= 256, "tile width $tile.width should be >= 256")
            assertEquals(tile.width * tile.height * 4, tile.rgba.size, "tile rgba should be 4 bytes per pixel")
            // Decode center pixel as Terrarium altitude : R*256 + G + B/256 - 32768.
            val cx = tile.width / 2
            val cy = tile.height / 2
            val ofs = (cy * tile.width + cx) * 4
            val r = tile.rgba[ofs].toInt() and 0xFF
            val g = tile.rgba[ofs + 1].toInt() and 0xFF
            val b = tile.rgba[ofs + 2].toInt() and 0xFF
            val altitude = r * 256.0 + g + b / 256.0 - 32768.0
            // The 12/2138/1466 tile covers ~10 km × 10 km of the Mont Blanc massif. The center
            // pixel isn't necessarily the summit — it can land on a valley floor — so we just
            // assert the decoded altitude is plausibly Alpine (above sea level, below 6 km) to
            // confirm the WebP→RGBA→Terrarium chain is working end-to-end, not stuck at zeros.
            assertTrue(altitude > 100.0, "tile center altitude > 100 m (got $altitude)")
            assertTrue(altitude < 6000.0, "tile center altitude < 6000 m (got $altitude)")
        }
}
