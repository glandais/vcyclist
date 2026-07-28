package io.github.glandais.elevation

import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Measurement step, not an assertion: decodes [ReferenceTile.URL] through the JVM
 * `fetchAndDecodeTile` and prints the SHA-256 of the resulting `RawTile.rgba` plus its
 * dimensions. The printed digest is what gets frozen into [ReferenceTile.RGBA_SHA256].
 *
 * ```
 * INTEGRATION=1 ./gradlew :elevation:jvmTest --tests '*ReferenceTileDigestDumpTest*' --rerun-tasks -i
 * ```
 */
class ReferenceTileDigestDumpTest {
    @Test
    fun dumpReferenceTileDigest() =
        runTest {
            if (skipIfOffline("reference tile digest dump")) return@runTest
            val tile = fetchAndDecodeTile(ReferenceTile.URL)
            val nonOpaque = (0 until tile.width * tile.height).count { tile.rgba[it * 4 + 3] != 255.toByte() }
            println("[reference-tile] url    = ${ReferenceTile.URL}")
            println("[reference-tile] size   = ${tile.width}x${tile.height} (${tile.rgba.size} bytes)")
            println("[reference-tile] sha256 = ${Sha256.hex(tile.rgba)}")
            println("[reference-tile] non-opaque pixels = $nonOpaque")
        }
}
