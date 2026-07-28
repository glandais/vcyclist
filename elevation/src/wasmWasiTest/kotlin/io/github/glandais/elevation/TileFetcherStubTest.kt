package io.github.glandais.elevation

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The counterpart of moving `TileDecodeSplitTest` out of `commonTest` (task w01): the three
 * `TileFetcher` actuals of this target are stubs, and an untested stub drifts. What matters here
 * is not that they throw — anything throws eventually — but *what they say when they do*, since
 * that message is the only documentation a host embedding the `.wasm` will ever read.
 *
 * So each assertion pins the escape hatch: the fetchers must point at the injection seam added by
 * task g21 (`TileManager` / `ElevationProvider` take a fetcher), and the decoder must name its
 * source URL so a failure is traceable to one tile. Task w11 replaces the decoder stub with a
 * pure-Kotlin VP8L implementation ; task w05 wires the host-provided fetcher.
 */
class TileFetcherStubTest {
    @Test
    fun `fetchTileBytes fails and names the injection seam`() =
        runTest {
            val message =
                assertFailsWith<UnsupportedOperationException> {
                    fetchTileBytes("https://host/9/1/2.webp")
                }.message ?: ""

            assertTrue(message.contains("wasmWasi"), "must name the target, was: $message")
            assertTrue(
                message.contains("TileManager") || message.contains("ElevationProvider"),
                "must point at the injection seam, was: $message",
            )
        }

    @Test
    fun `fetchAndDecodeTile fails and names the injection seam`() =
        runTest {
            val message =
                assertFailsWith<UnsupportedOperationException> {
                    fetchAndDecodeTile("https://host/9/1/2.webp")
                }.message ?: ""

            assertTrue(
                message.contains("TileManager") || message.contains("ElevationProvider"),
                "must point at the injection seam, was: $message",
            )
        }

    @Test
    fun `decodeTileBytes fails and names the tile it was given`() =
        runTest {
            val message =
                assertFailsWith<UnsupportedOperationException> {
                    decodeTileBytes(ByteArray(8), "https://host/9/1/2.webp")
                }.message ?: ""

            assertTrue(
                message.contains("https://host/9/1/2.webp"),
                "must name the source tile, was: $message",
            )
        }

    /**
     * The stub is only tolerable because everything *around* the decoder still works under WASI:
     * an `ElevationProvider` fed by a caller-supplied fetcher never touches it. This is the
     * wasmWasi half of the coverage that moved to `src/decodingTest/kotlin` — same scenario,
     * minus the bytes-to-pixels step the target cannot do.
     */
    @Test
    fun `an ElevationProvider fed by an injected fetcher works without any decoder`() =
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
            assertTrue(requested == listOf("cache://0/0/0"), "exactly one tile, was: $requested")
        }
}
