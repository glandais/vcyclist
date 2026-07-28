package io.github.glandais.elevation

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The cross-target byte-exactness test: every target must decode [ReferenceTile.URL] to the
 * **same** RGBA bytes as the JVM reference (see [ReferenceTile.RGBA_SHA256]).
 *
 * This lives in `commonTest`, so it runs on JVM, JS/Node, JS/browser (Karma + headless Chrome)
 * and Wasm/browser. It is deliberately **not** an "elevation looks plausible" assertion: a
 * plausibility check would happily pass while `createImageBitmap` premultiplied the alpha or
 * applied a colour-space conversion, either of which silently rewrites the low bits that carry
 * the `B/256` term of the Terrarium encoding.
 *
 * Gated on `INTEGRATION=1` so `./gradlew check` stays offline — see `IntegrationGate.kt`.
 */
class ReferenceTileDigestTest {
    @Test
    fun referenceTileDecodesToTheFrozenBytes() =
        runTest {
            if (skipIfOffline("ReferenceTileDigestTest")) return@runTest

            val tile = fetchAndDecodeTile(ReferenceTile.URL)

            assertEquals(ReferenceTile.WIDTH, tile.width, "tile width")
            assertEquals(ReferenceTile.HEIGHT, tile.height, "tile height")
            assertEquals(tile.width * tile.height * 4, tile.rgba.size, "rgba must be 4 bytes per pixel")

            // Alpha must be fully opaque everywhere. Anything else means the decoder invented an
            // alpha channel, and premultiplication would then have scaled the RGB elevation bits.
            val nonOpaque = (0 until tile.width * tile.height).count { tile.rgba[it * 4 + 3] != 255.toByte() }
            assertEquals(0, nonOpaque, "expected every pixel opaque, found $nonOpaque non-opaque")

            val actual = Sha256.hex(tile.rgba)
            assertEquals(
                ReferenceTile.RGBA_SHA256,
                actual,
                "RGBA digest mismatch for ${ReferenceTile.URL}. " +
                    "Expected ${ReferenceTile.RGBA_SHA256}, got $actual. " +
                    "This target's decoder is not byte-exact — suspect premultiplyAlpha / " +
                    "colorSpaceConversion in createImageBitmap. " +
                    "Centre pixel = ${centrePixel(tile)}",
            )
        }

    @Test
    fun referenceTileCentrePixelDecodesToAnAlpineAltitude() =
        runTest {
            if (skipIfOffline("ReferenceTileDigestTest")) return@runTest
            val tile = fetchAndDecodeTile(ReferenceTile.URL)
            val ofs = (tile.height / 2 * tile.width + tile.width / 2) * 4
            val r = tile.rgba[ofs].toInt() and 0xFF
            val g = tile.rgba[ofs + 1].toInt() and 0xFF
            val b = tile.rgba[ofs + 2].toInt() and 0xFF
            val altitude = r * 256.0 + g + b / 256.0 - 32768.0
            // Secondary, human-readable sanity check on top of the digest.
            assertTrue(altitude > 100.0, "centre altitude > 100 m (got $altitude)")
            assertTrue(altitude < 6000.0, "centre altitude < 6000 m (got $altitude)")
        }

    private fun centrePixel(tile: RawTile): String {
        val ofs = (tile.height / 2 * tile.width + tile.width / 2) * 4
        return (0 until 4).joinToString(",") { (tile.rgba[ofs + it].toInt() and 0xFF).toString() }
    }
}
