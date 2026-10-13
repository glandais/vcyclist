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
 * [ElevationCalculator]'s bilinear interpolation runs between pixel **centres**: in the eastern /
 * southern half of a pixel it samples `x0 + 1` / `y0 + 1`, in the western / northern half
 * `x0 − 1` / `y0 − 1`. Without the clamp, every point within half a pixel of longitude ±180° (or of
 * either Web-Mercator latitude limit) would blend in the elevation of a pixel a whole tile away
 * (360° / 2^z of longitude), weighted by up to ½.
 *
 * The test tiles are built so that the clamp interpretation (neighbour = edge pixel) and the wrap
 * interpretation (neighbour = the pixel across the antimeridian) agree on 500 m, while the
 * opposite edge of the edge tile — the pixel an unclamped shift lands on — holds 100 m.
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
     * column 12, three pixels west of the last column 15. At lon 179.99, `pf.x ≈ 3.9996` and between
     * centres the neighbour weight dx is ≈ 0.4996, so an unclamped shift would read ~300 m.
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

    /**
     * The mirror case the centre convention creates: at lon −179.99, `pf.x ≈ 0.0004`, so
     * `fx = pf.x − ½ ≈ −0.4996` and `x0 = −1`, weighted ≈ 0.4996. normalizePixel turns it into
     * tile −1 → clamped to tile 0, x = 0. Without the `x = 0` reset it would keep x = 3, the
     * **east** edge of tile 0, three pixels away.
     */
    @Test
    fun `interpolation next to longitude -180 does not sample the east edge of the first tile`() =
        runTest {
            val calc = calculator { tx, _, px, _ -> tx == 0 && px == size - 1 }
            val e = calc.getElevation(LatLon(10.0, -179.99), zoom, interpolation = true)
            assertEquals(500.0, e, 1.0, "elevation at lon -179.99 blended in the tile's east edge")
        }

    /** Same on the y axis: the row above the first tile at the northern Mercator limit. */
    @Test
    fun `interpolation next to the northern Mercator limit does not sample the bottom row of the first tile`() =
        runTest {
            val calc = calculator { _, ty, _, py -> ty == 0 && py == size - 1 }
            val e = calc.getElevation(LatLon(85.0511, 10.0), zoom, interpolation = true)
            assertEquals(500.0, e, 1.0, "elevation at lat 85.0511 blended in the tile's bottom row")
        }
}
