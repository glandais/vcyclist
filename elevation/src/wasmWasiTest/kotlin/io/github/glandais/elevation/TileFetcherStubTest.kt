package io.github.glandais.elevation

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What this target does and does not do with DEM tiles.
 *
 * The shape of these tests is dictated by one constraint: **nothing here may reach an export or
 * a function that touches a host import**. Since task w05, `fetchAndDecodeTile` calls
 * `vcyclist.fetch_tile`, and merely calling it from a test keeps that import alive in the test
 * binary — which the KGP runner cannot supply, so the module stops instantiating and the whole
 * suite dies with `unknown import`. That is not a hypothetical: it happened while writing w05,
 * and this file was rewritten because of it.
 *
 * So what is tested here is everything around the import — the two operations that stay
 * unavailable, the URL parsing, the sea-level fallback, and the fact that a provider fed by an
 * injected fetcher works without any of it.
 */
class TileFetcherStubTest {
    @Test
    fun `fetchTileBytes stays unavailable and points at the host import`() =
        runTest {
            val message =
                assertFailsWith<UnsupportedOperationException> {
                    fetchTileBytes("https://host/9/1/2.webp")
                }.message ?: ""

            assertTrue(message.contains("wasmWasi"), "must name the target, was: $message")
            assertTrue(message.contains("fetch_tile"), "must name the way tiles arrive, was: $message")
        }

    @Test
    fun `decodeTileBytes stays unavailable, names its tile and the task that would fix it`() =
        runTest {
            val message =
                assertFailsWith<UnsupportedOperationException> {
                    decodeTileBytes(ByteArray(8), "https://host/9/1/2.webp")
                }.message ?: ""

            assertTrue(message.contains("https://host/9/1/2.webp"), "must name the source tile, was: $message")
            assertTrue(message.contains("w11"), "must name the pure-Kotlin decoder task, was: $message")
        }

    @Test
    fun `the host URL template round-trips through TileManager's substitution`() {
        val url =
            HostTileSource.URL_TEMPLATE
                .replace("{z}", "12")
                .replace("{x}", "2145")
                .replace("{y}", "1436")

        assertEquals(TileCoordinates(z = 12, x = 2145, y = 1436), parseTileUrl(url))
    }

    @Test
    fun `an HTTPS URL is refused rather than fetched by some other means`() {
        val thrown =
            assertFailsWith<IllegalArgumentException> {
                parseTileUrl("https://tiles.mapterhorn.com/12/2145/1436.webp")
            }

        assertTrue(thrown.message!!.contains("host://"), thrown.message!!)
    }

    @Test
    fun `non-integer coordinates are refused`() {
        assertFailsWith<IllegalArgumentException> { parseTileUrl("host://12/x/1436") }
        assertFailsWith<IllegalArgumentException> { parseTileUrl("host://12/2145") }
    }

    @Test
    fun `the no-tile answer decodes as exactly 0 m, not as minus 32768`() {
        val tile = Tile(seaLevelTile(4))
        val tc = TileCoordinates(x = 0, y = 0, z = 0)

        for (y in 0 until 4) {
            for (x in 0 until 4) {
                assertEquals(0.0, tile.getElevation(Pixel(tc, x, y)), 1e-9, "pixel ($x, $y)")
            }
        }
    }

    @Test
    fun `tile geometry follows the configured size, in RGBA`() {
        val previous = HostTileSource.tileSize
        try {
            HostTileSource.tileSize = 256
            assertEquals(256 * 256 * 4, HostTileSource.tileBytes)
            assertFailsWith<IllegalArgumentException> { HostTileSource.tileSize = 300 }
        } finally {
            HostTileSource.tileSize = previous
        }
    }

    @Test
    fun `an ElevationProvider fed by an injected fetcher works without any host tile`() =
        runTest {
            val requested = mutableListOf<String>()
            val provider =
                ElevationProvider(
                    ElevationProviderConfig(
                        zoomLevel = 0,
                        tileSize = InlineTerrariumTileFixture.SIZE,
                        cacheSize = 4,
                        tileUrlTemplate = "cache://{z}/{x}/{y}",
                    ),
                ) { url ->
                    requested += url
                    InlineTerrariumTileFixture.expectedRawTile()
                }

            val elevations = provider.setElevations(listOf(LatLon(0.0, 0.0)), interpolation = false)

            assertTrue(elevations.single().elevation != null, "the injected tile must yield an elevation")
            assertEquals(listOf("cache://0/0/0"), requested, "exactly one tile, served from the caller's map")
        }
}
