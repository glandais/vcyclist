package io.github.glandais.elevation

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Task g21: [fetchTileBytes] and [decodeTileBytes] are the two halves of [fetchAndDecodeTile],
 * exposed so a caller can own the transport (disk cache, object store, bundled tiles) without
 * reimplementing the decoder.
 *
 * These tests run **offline on all four targets**, which the pre-g21 decode tests could not:
 * they went through a `data:` URL, which the JVM's `HttpClient` does not support. Bytes have no
 * such problem — which is the point of the split.
 *
 * ## They were exiled, and they came back
 *
 * Task w01 moved this file to `src/decodingTest` because `decodeTileBytes` was a stub on
 * wasmWasi: the target had no WebP decoder, so seven tests asserting decoded pixels could only
 * fail there. Task w11 wrote that decoder in `commonMain`, in pure Kotlin, and they belong in
 * `commonTest` again — where they now assert the *same* decoder on JVM, JS and wasmWasi alike.
 *
 * One test did not come back: the composition check against a live tile, which reaches
 * `fetchAndDecodeTile` and therefore the host import. See `TileFetchCompositionTest`.
 */
class TileDecodeSplitTest {
    @Test
    fun `decodes the inline webp to the expected rgba`() =
        runTest {
            val tile = decodeTileBytes(InlineWebpFixture.BYTES, "inline://fixture.webp")

            assertEquals(InlineWebpFixture.WIDTH, tile.width, "width")
            assertEquals(InlineWebpFixture.HEIGHT, tile.height, "height")
            assertContentEquals(
                InlineWebpFixture.EXPECTED_RGBA,
                tile.rgba,
                "decoded=${InlineWebpFixture.describe(tile.rgba)}",
            )
        }

    @Test
    fun `sourceUrl is optional`() =
        runTest {
            val tile = decodeTileBytes(InlineWebpFixture.BYTES)
            assertContentEquals(InlineWebpFixture.EXPECTED_RGBA, tile.rgba)
        }

    @Test
    fun `decoding twice is stable`() =
        runTest {
            val first = decodeTileBytes(InlineWebpFixture.BYTES)
            val second = decodeTileBytes(InlineWebpFixture.BYTES)
            assertContentEquals(first.rgba, second.rgba)
        }

    @Test
    fun `decodes the 4x4 terrarium fixture exactly`() =
        runTest {
            val expected = InlineTerrariumTileFixture.expectedRawTile()
            val tile = decodeTileBytes(InlineTerrariumTileFixture.BYTES, "inline://terrarium.webp")

            assertEquals(expected.width, tile.width, "width")
            assertEquals(expected.height, tile.height, "height")
            assertContentEquals(expected.rgba, tile.rgba, "terrarium fixture must decode bit-exact")
        }

    @Test
    fun `decoded elevations match the generating formula`() =
        runTest {
            val tile = Tile(decodeTileBytes(InlineTerrariumTileFixture.BYTES))
            val tc = TileCoordinates(x = 0, y = 0, z = 0)
            for (py in 0 until InlineTerrariumTileFixture.SIZE) {
                for (px in 0 until InlineTerrariumTileFixture.SIZE) {
                    assertEquals(
                        InlineTerrariumTileFixture.elevationAt(px, py).toDouble(),
                        tile.getElevation(Pixel(tc, px, py)),
                        0.001,
                        "pixel ($px, $py)",
                    )
                }
            }
        }

    @Test
    fun `corrupt bytes fail with a traceable message`() =
        runTest {
            val garbage = ByteArray(64) { 0x7A }
            val message =
                try {
                    decodeTileBytes(garbage, "https://host/9/1/2.webp")
                    fail("expected decoding to fail")
                } catch (e: IllegalStateException) {
                    e.message ?: ""
                }
            assertTrue(
                message.contains("https://host/9/1/2.webp"),
                "error must name the source, was: $message",
            )
        }

    /**
     * The caller-owned-cache scenario this task exists for: bytes come from a map, the library
     * only decodes. No HTTP client is involved at any point — the test would fail on a network
     * call because the URL template resolves to a scheme nothing can fetch.
     */
    @Test
    fun `an ElevationProvider can run entirely off caller-supplied bytes`() =
        runTest {
            val byteCache = mutableMapOf("cache://0/0/0" to InlineTerrariumTileFixture.BYTES)
            val requested = mutableListOf<String>()

            val cfg =
                ElevationProviderConfig(
                    zoomLevel = 0,
                    tileSize = InlineTerrariumTileFixture.SIZE,
                    cacheSize = 4,
                    tileUrlTemplate = "cache://{z}/{x}/{y}",
                )
            val fromBytes =
                ElevationProvider(cfg) { url ->
                    requested += url
                    decodeTileBytes(byteCache.getValue(url), url)
                }
            val inMemory = ElevationProvider(cfg, fetcher = { InlineTerrariumTileFixture.expectedRawTile() })

            val coords = listOf(LatLon(0.0, 0.0), LatLon(10.0, 20.0), LatLon(-40.0, -70.0))
            val viaBytes = fromBytes.setElevations(coords, interpolation = false)
            val viaRawTile = inMemory.setElevations(coords, interpolation = false)

            assertEquals(
                viaRawTile.map { it.elevation },
                viaBytes.map { it.elevation },
                "the bytes path must agree with the in-memory path, elevation for elevation",
            )
            assertEquals(listOf("cache://0/0/0"), requested, "exactly one tile, served from the caller's map")
        }
}
