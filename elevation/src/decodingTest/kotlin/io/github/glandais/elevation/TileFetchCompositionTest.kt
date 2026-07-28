package io.github.glandais.elevation

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The one decode test that cannot live in `commonTest`, and why.
 *
 * Task w11 brought `TileDecodeSplitTest` back to `commonTest`, since the WebP decoder is now
 * pure Kotlin and runs everywhere. This check is different: it calls [fetchAndDecodeTile] and
 * [fetchTileBytes], which on wasmWasi go through the `vcyclist.fetch_tile` host import. Merely
 * *reaching* them from a test keeps that import alive in the test binary — reachability is
 * static, and the `INTEGRATION` gate below is a runtime condition — and the KGP runner cannot
 * supply it, so the whole module's suite dies with `unknown import`. That is measured, not
 * feared: it is what happened in w05.
 *
 * So `src/decodingTest` survives w11 with exactly one test in it, compiled into `jvmTest` and
 * `jsTest` only. Its subject is the transport, which those two targets are the only ones to have.
 */
class TileFetchCompositionTest {
    /**
     * The contract of the split, on a real tile over the network: whatever shortcut a target
     * takes inside [fetchAndDecodeTile], it must land on the same bytes as the two halves called
     * in sequence. Gated on `INTEGRATION=1` so `./gradlew check` stays offline.
     */
    @Test
    fun `fetchAndDecode equals fetch then decode on the reference tile`() =
        runTest {
            if (skipIfOffline("TileDecodeSplitTest")) return@runTest

            val composed = decodeTileBytes(fetchTileBytes(ReferenceTile.URL), ReferenceTile.URL)
            val direct = fetchAndDecodeTile(ReferenceTile.URL)

            assertEquals(direct.width, composed.width, "width")
            assertEquals(direct.height, composed.height, "height")
            assertEquals(
                Sha256.hex(direct.rgba),
                Sha256.hex(composed.rgba),
                "the composition must be byte-identical to the direct path",
            )
            assertEquals(ReferenceTile.RGBA_SHA256, Sha256.hex(composed.rgba), "frozen reference digest")
        }
}
