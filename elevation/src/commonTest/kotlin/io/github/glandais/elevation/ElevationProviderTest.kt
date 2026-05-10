package io.github.glandais.elevation

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Tile fetcher whose URL template is `test://{z}/{x}/{y}` and elevation is px*100+py at every pixel. */
private fun syntheticFetcher(tileSize: Int): suspend (String) -> RawTile =
    { url ->
        val parts = url.removePrefix("test://").split("/")
        require(parts.size == 3) { "unexpected url: $url" }
        val rgba = ByteArray(tileSize * tileSize * 4)
        for (py in 0 until tileSize) {
            for (px in 0 until tileSize) {
                val e = px * 100 + py
                val raw = e + 32768
                val r = (raw shr 8) and 0xFF
                val g = raw and 0xFF
                val idx = (py * tileSize + px) * 4
                rgba[idx] = r.toByte()
                rgba[idx + 1] = g.toByte()
                rgba[idx + 2] = 0
                rgba[idx + 3] = 255.toByte()
            }
        }
        RawTile(tileSize, tileSize, rgba)
    }

class ElevationProviderTest {
    @Test
    fun `rejects zoom level below 0`() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                ElevationProvider(ElevationProviderConfig(zoomLevel = -1))
            }
        assertEquals("Invalid zoom level: -1. Must be an integer between 0 and 15", ex.message)
    }

    @Test
    fun `rejects zoom level above 15`() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                ElevationProvider(ElevationProviderConfig(zoomLevel = 16))
            }
        assertEquals("Invalid zoom level: 16. Must be an integer between 0 and 15", ex.message)
    }

    @Test
    fun `rejects cache size 0`() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                ElevationProvider(ElevationProviderConfig(cacheSize = 0))
            }
        assertEquals("Invalid cache size: 0. Must be a positive integer", ex.message)
    }

    @Test
    fun `rejects tile size that is not a power of 2`() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                ElevationProvider(ElevationProviderConfig(tileSize = 7))
            }
        assertEquals("Invalid tile size: 7. Must be a positive power of 2", ex.message)
    }

    @Test
    fun `rejects tile size zero`() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                ElevationProvider(ElevationProviderConfig(tileSize = 0))
            }
        assertEquals("Invalid tile size: 0. Must be a positive power of 2", ex.message)
    }

    @Test
    fun `accepts powers of 2 - 128, 256, 512`() {
        for (size in listOf(128, 256, 512)) {
            ElevationProvider(ElevationProviderConfig(tileSize = size))
        }
    }

    @Test
    fun `default attribution mentions mapterhorn`() {
        val p = ElevationProvider(ElevationProviderConfig())
        assertTrue("mapterhorn" in p.attribution.text, "attribution text = ${p.attribution.text}")
        assertEquals("https://mapterhorn.com/attribution/", p.attribution.url)
    }

    @Test
    fun `getElevation end to end via synthetic fetcher`() =
        runTest {
            val cfg =
                ElevationProviderConfig(
                    zoomLevel = 0,
                    tileSize = 4,
                    cacheSize = 4,
                    tileUrlTemplate = "test://{z}/{x}/{y}",
                )
            val p = ElevationProvider(cfg, fetcher = syntheticFetcher(cfg.tileSize))
            val v = p.getElevation(latitude = 0.0, longitude = 0.0, interpolation = false)
            // At z=0/tileSize=4 with lat=lon=0, pixel is (2,2). elevation = 2*100+2 = 202.
            assertEquals(202.0, v)
        }

    @Test
    fun `setElevations end to end via synthetic fetcher`() =
        runTest {
            val cfg =
                ElevationProviderConfig(
                    zoomLevel = 0,
                    tileSize = 4,
                    cacheSize = 4,
                    tileUrlTemplate = "test://{z}/{x}/{y}",
                )
            val p = ElevationProvider(cfg, fetcher = syntheticFetcher(cfg.tileSize))
            val coords = listOf(LatLon(0.0, 0.0), LatLon(0.0, 0.001), LatLon(0.0, 0.002))
            val res = p.setElevations(coords, interpolation = false)
            assertEquals(3, res.size)
            // All three coords fall in the same tile and the same nearest pixel — same value.
            assertEquals(res[0].elevation, res[1].elevation)
        }

    @Test
    fun `getElevationsAlong end to end with smoothing and filtering`() =
        runTest {
            val cfg =
                ElevationProviderConfig(
                    zoomLevel = 0,
                    tileSize = 4,
                    cacheSize = 4,
                    tileUrlTemplate = "test://{z}/{x}/{y}",
                )
            val p = ElevationProvider(cfg, fetcher = syntheticFetcher(cfg.tileSize))
            val path = listOf(LatLon(0.0, 0.0), LatLon(0.0, 0.001))
            val res =
                p.getElevationsAlong(
                    path,
                    step = 5.0,
                    minDistance = 1.0,
                    smoothingOptions = SmoothingOptions(windowSize = 30.0, enabled = true),
                    filterOptions = FilterOptions(tolerance = 1.0, zExaggeration = 3.0, enabled = true),
                )
            assertTrue(res.isNotEmpty())
            assertEquals(path.first().latitude, res.first().latitude)
            assertEquals(path.last().latitude, res.last().latitude)
        }
}
