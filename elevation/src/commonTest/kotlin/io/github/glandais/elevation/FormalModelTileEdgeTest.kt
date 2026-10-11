package io.github.glandais.elevation

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Counterexamples found by the Lean model in scratchpad/formal/webp/Huffman.lean
 * (`normalize_east_edge_counterexample`): [ElevationFunctions.normalizePixel] shifts an
 * overflowing pixel into the next tile and *then* clamps the tile index, so at the last tile
 * column/row of the world the pixel lands on the **opposite edge of the same tile** — neither
 * clamped to the edge pixel nor wrapped across the antimeridian.
 *
 * [ElevationCalculator]'s bilinear interpolation always samples `x0 + 1` / `y0 + 1`, so every point
 * within one pixel of longitude 180° (or of the southern Web-Mercator limit) blends in the
 * elevation of a pixel a whole tile away (360° / 2^z of longitude), weighted by up to ~100 %.
 *
 * The test tiles are built so that the clamp interpretation (neighbour = last pixel) and the wrap
 * interpretation (neighbour = first pixel of tile 0) agree on 500 m, while the far edge of the last
 * tile — the pixel the current code samples — holds 100 m.
 */
class FormalModelTileEdgeTest {
    private val size = 4
    private val zoom = 2

    private fun calculator(farEdge: (tx: Int, ty: Int, px: Int, py: Int) -> Boolean): ElevationCalculator {
        val fetch: suspend (String) -> RawTile = { url ->
            val parts = url.removePrefix("test://").split("/")
            val tx = parts[1].toInt()
            val ty = parts[2].toInt()
            val rgba = ByteArray(size * size * 4)
            for (py in 0 until size) {
                for (px in 0 until size) {
                    val metres = if (farEdge(tx, ty, px, py)) 100 else 500
                    val raw = 32768 + metres
                    val i = (py * size + px) * 4
                    rgba[i] = ((raw shr 8) and 0xFF).toByte()
                    rgba[i + 1] = (raw and 0xFF).toByte()
                    rgba[i + 3] = 255.toByte()
                }
            }
            RawTile(size, size, rgba)
        }
        return ElevationCalculator(TileManager("test://{z}/{x}/{y}", 16, fetch), tileSize = size)
    }

    /**
     * Lean: normalizePixel(tile (3,0), x = 4) at z = 2, ts = 4 = tile (3,0), x = 0 — global
     * column 12, three pixels west of the last column 15. At lon 179.99 the neighbour weight dx is
     * 0.9996, so the result is ~100 m instead of 500 m.
     */
    @Test
    fun `interpolation next to longitude 180 does not sample the west edge of the last tile`() =
        runTest {
            val calc = calculator { tx, _, px, _ -> tx == (1 shl zoom) - 1 && px == 0 }
            val e = calc.getElevation(LatLon(10.0, 179.99), zoom, interpolation = true)
            assertEquals(500.0, e, 1.0, "elevation at lon 179.99 blended in the tile's west edge")
        }

    /** Same counterexample on the y axis: the bottom-row neighbour at the southern limit. */
    @Test
    fun `interpolation next to the southern Mercator limit does not sample the top row of the last tile`() =
        runTest {
            val calc = calculator { _, ty, _, py -> ty == (1 shl zoom) - 1 && py == 0 }
            val e = calc.getElevation(LatLon(-85.0511, 10.0), zoom, interpolation = true)
            assertEquals(500.0, e, 1.0, "elevation at lat -85.0511 blended in the tile's top row")
        }
}
