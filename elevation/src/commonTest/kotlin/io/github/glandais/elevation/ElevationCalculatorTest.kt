package io.github.glandais.elevation

import kotlinx.coroutines.test.runTest
import kotlin.math.absoluteValue
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val EPS = 1e-6

/** Synthesises a tile fetcher that encodes a known elevation function with Terrarium. */
private fun makeSyntheticFetcher(
    tileSize: Int,
    elevation: (z: Int, tx: Int, ty: Int, px: Int, py: Int) -> Int,
): suspend (String) -> RawTile =
    { url ->
        val parts = url.removePrefix("test://").split("/")
        val z = parts[0].toInt()
        val tx = parts[1].toInt()
        val ty = parts[2].toInt()
        val rgba = ByteArray(tileSize * tileSize * 4)
        for (py in 0 until tileSize) {
            for (px in 0 until tileSize) {
                val e = elevation(z, tx, ty, px, py)
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

private fun makeCalculator(
    tileSize: Int = 4,
    cacheSize: Int = 16,
    elevation: (z: Int, tx: Int, ty: Int, px: Int, py: Int) -> Int,
): ElevationCalculator {
    val tm =
        TileManager(
            urlTemplate = "test://{z}/{x}/{y}",
            cacheSize = cacheSize,
            fetcher = makeSyntheticFetcher(tileSize, elevation),
        )
    return ElevationCalculator(tm, tileSize)
}

private fun expectedPixel(
    coords: Coordinates,
    z: Int,
    tileSize: Int,
): Pair<Double, Double> {
    val n = (1 shl z).toDouble()
    val xFloat = ((coords.longitude + 180.0) / 360.0) * n
    val latRad = coords.latitude * kotlin.math.PI / 180.0
    val yFloat = ((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / kotlin.math.PI) / 2.0) * n
    val tx = floor(xFloat).toInt().coerceAtLeast(0)
    val ty = floor(yFloat).toInt().coerceAtLeast(0)
    val px = (xFloat - tx) * tileSize
    val py = (yFloat - ty) * tileSize
    return px to py
}

class ElevationCalculatorTest {
    @Test
    fun `getElevation interpolation=false returns the closest pixel value`() =
        runTest {
            val calc = makeCalculator { _, _, _, px, py -> px * 100 + py }
            // lat=0 lon=0 z=0 tileSize=4 → tile (0,0,0), pixel (2, 2). Closest pixel elevation 202.
            val v = calc.getElevation(LatLon(0.0, 0.0), zoomLevel = 0, interpolation = false)
            assertEquals(202.0, v)
        }

    @Test
    fun `getElevation interpolation=true with dx=0 dy=0 equals nearest-pixel`() =
        runTest {
            val calc = makeCalculator { _, _, _, px, py -> px * 100 + py }
            val v = calc.getElevation(LatLon(0.0, 0.0), zoomLevel = 0, interpolation = true)
            // At (lat=0, lon=0, z=0, tileSize=4), x=2.0 y=2.0 exact (dx=dy=0).
            // p00 = 202, contributes weight 1.
            assertEquals(202.0, v)
        }

    @Test
    fun `bilinear interpolation matches weighted formula`() =
        runTest {
            val tileSize = 4
            val calc = makeCalculator(tileSize = tileSize) { _, _, _, px, py -> px * 100 + py }
            val coords = LatLon(45.0, 22.5)
            val z = 0

            val (px, py) = expectedPixel(coords, z, tileSize)
            val x0 = floor(px).toInt()
            val y0 = floor(py).toInt()
            val dx = px - x0
            val dy = py - y0

            // Synthetic tile elevation at pixel (x, y) is x * 100 + y.
            val p00 = (x0 * 100 + y0).toDouble()
            val p10 = ((x0 + 1) * 100 + y0).toDouble()
            val p01 = (x0 * 100 + (y0 + 1)).toDouble()
            val p11 = ((x0 + 1) * 100 + (y0 + 1)).toDouble()
            val expected =
                p00 * (1 - dx) * (1 - dy) +
                    p10 * dx * (1 - dy) +
                    p01 * (1 - dx) * dy +
                    p11 * dx * dy

            val actual = calc.getElevation(coords, zoomLevel = z, interpolation = true)
            assertTrue((actual - expected).absoluteValue < EPS, "expected $expected, got $actual")
        }

    @Test
    fun `dx=0_5 dy=0_5 averages all four neighbours`() =
        runTest {
            // Build a synthetic case where px and py land exactly at .5 by choosing tileSize=2 at z=0.
            // At z=0, xFloat = (lon+180)/360, yFloat in [0,1]. With tileSize=2 the pixel is (xFloat*2, yFloat*2).
            // Pick lon such that xFloat*2 - 0 = 0.5 → xFloat = 0.25 → lon = -90.
            // Pick lat such that yFloat*2 - 0 = 0.5 → yFloat = 0.25 → solve ln(tan(lat)+sec(lat))/PI = 0.5 → tan(lat)+sec(lat) = exp(PI/2)
            // exp(PI/2) ≈ 4.8105 → using (sin+1)/cos = 4.8105 → lat ≈ 66.51°.
            // We don't need a precise number — we compute the synthetic value with the same formula and check the
            // bilinear matches the formula. This test guards against e.g. inverted weights.
            val tileSize = 2
            val calc = makeCalculator(tileSize = tileSize) { _, _, _, px, py -> px * 10 + py }
            val coords = LatLon(66.51326044, -90.0)
            val z = 0
            val (px, py) = expectedPixel(coords, z, tileSize)
            val x0 = floor(px).toInt()
            val y0 = floor(py).toInt()
            val dx = px - x0
            val dy = py - y0

            val p00 = (x0 * 10 + y0).toDouble()
            val p10 = ((x0 + 1) * 10 + y0).toDouble()
            val p01 = (x0 * 10 + (y0 + 1)).toDouble()
            val p11 = ((x0 + 1) * 10 + (y0 + 1)).toDouble()
            val expected =
                p00 * (1 - dx) * (1 - dy) +
                    p10 * dx * (1 - dy) +
                    p01 * (1 - dx) * dy +
                    p11 * dx * dy

            val actual = calc.getElevation(coords, zoomLevel = z, interpolation = true)
            assertTrue((actual - expected).absoluteValue < EPS, "px=$px py=$py expected=$expected actual=$actual")
        }

    @Test
    fun `interpolation overflows tile via normalizePixel`() =
        runTest {
            // Build a calculator whose elevation depends on tile coordinates as well, so we can detect a
            // tile fetch crossing the boundary: elevation = tx * 1_000_000 + ty * 10_000 + px * 100 + py.
            val tileSize = 4
            val calc =
                makeCalculator(tileSize = tileSize) { _, tx, ty, px, py ->
                    tx * 1_000_000 + ty * 10_000 + px * 100 + py
                }
            // Pick a coord whose tile = (0, 0, 1) and pixel-x near 3.7 so x1 = 4 → overflow to tile (1, 0, 1).
            // At z=1, n=2, lon=22.5 → xFloat = (22.5+180)/360*2 = 1.125. tx=1, but we want tx=0...
            // Recompute: at z=1, lon=-90 → xFloat = (-90+180)/360 * 2 = 0.5. tx=0. px = 0.5 * 4 = 2.0. dx=0.
            // For dx>0 we want xFloat to be in (0, 1) and away from .0; e.g. lon=-22.5: xFloat = 0.875, px=3.5, dx=0.5, x1=4.
            // Then x1 overflows; normalizePixel should redirect to tile (1, 0, 1) pixel 0.
            val coords = LatLon(0.0, -22.5)
            val z = 1
            val (px, _) = expectedPixel(coords, z, tileSize)
            val x0 = floor(px).toInt()
            assertEquals(3, x0, "expected x0=3 for px=$px")

            val v = calc.getElevation(coords, zoomLevel = z, interpolation = true)
            // It should be a blend of (tile 0, pixel x=3) and (tile 1, pixel x=0) horizontally.
            // dy = py - floor(py). For lat=0 z=1: yFloat = 1.0 exactly → py=4.0 → y0=4 → y1=5 (overflowing tile y).
            // normalizePixel will redirect y=4 → tile (..., 1, 1), pixel y=0; y=5 → tile (..., 1, 1), pixel y=1.
            // The point of this test is: it does not throw, and the result is finite & non-zero.
            assertTrue(v.isFinite(), "expected finite, got $v")
        }

    @Test
    fun `internal failure is wrapped in IllegalStateException with TS-style message`() =
        runTest {
            val failingFetcher: suspend (String) -> RawTile = { _ -> error("network down") }
            val tm = TileManager("test://{z}/{x}/{y}", cacheSize = 4, fetcher = failingFetcher)
            val calc = ElevationCalculator(tm, tileSize = 4)
            val ex =
                assertFailsWith<IllegalStateException> {
                    calc.getElevation(LatLon(0.0, 0.0), zoomLevel = 0, interpolation = false)
                }
            assertEquals("Failed to get elevation: network down", ex.message)
        }
}
