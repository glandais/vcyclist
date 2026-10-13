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

/** The coordinates of the centre of pixel ([cx], [cy]) in tile (0, 0, [z]). */
private fun pixelCentre(
    cx: Int,
    cy: Int,
    z: Int,
    tileSize: Int,
): LatLon {
    val n = (1 shl z).toDouble()
    val xFloat = (cx + 0.5) / tileSize / n
    val yFloat = (cy + 0.5) / tileSize / n
    val lon = xFloat * 360.0 - 180.0
    val lat = kotlin.math.atan(kotlin.math.sinh(kotlin.math.PI * (1.0 - 2.0 * yFloat))) * 180.0 / kotlin.math.PI
    return LatLon(lat, lon)
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
    fun `getElevation interpolation=true on a pixel corner averages the four pixels sharing it`() =
        runTest {
            val calc = makeCalculator { _, _, _, px, py -> px * 100 + py }
            val v = calc.getElevation(LatLon(0.0, 0.0), zoomLevel = 0, interpolation = true)
            // At (lat=0, lon=0, z=0, tileSize=4), pf = (2.0, 2.0) exact: the corner shared by pixels
            // (1,1), (2,1), (1,2), (2,2), whose centres sit at 1.5 / 2.5. Between centres fx = fy = 1.5,
            // so dx = dy = 0.5: (101 + 201 + 102 + 202) / 4 = 151.5.
            assertEquals(151.5, v, 1e-12)
        }

    @Test
    fun `bilinear interpolation matches weighted formula`() =
        runTest {
            val tileSize = 4
            val calc = makeCalculator(tileSize = tileSize) { _, _, _, px, py -> px * 100 + py }
            val coords = LatLon(45.0, 22.5)
            val z = 0

            val (px, py) = expectedPixel(coords, z, tileSize)
            // Pixel i's value stands for its centre i + 0.5: interpolate between centres.
            val fx = px - 0.5
            val fy = py - 0.5
            val x0 = floor(fx).toInt()
            val y0 = floor(fy).toInt()
            val dx = fx - x0
            val dy = fy - y0
            // (45, 22.5) at z=0, 4 px: px = 2.25, py ≈ 1.439, so all four neighbours are inside the tile.
            assertEquals(1, x0)
            assertEquals(0, y0)

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
    fun `off-centre weights favour the nearer centres`() =
        runTest {
            // tileSize=2 at z=0, pf = (0.75, 1.25): lon = 0.375 * 360 - 180 = -45, lat from yFloat = 0.625.
            // Between centres (0.5 / 1.5) fx = 0.25, fy = 0.75: x0 = y0 = 0, dx = 0.25, dy = 0.75, all four
            // neighbours inside the tile. Values x * 10 + y give 10 * dx + dy = 3.25; inverted weights would
            // give 10 * 0.75 + 0.25 = 7.75.
            val tileSize = 2
            val calc = makeCalculator(tileSize = tileSize) { _, _, _, px, py -> px * 10 + py }
            val lat = kotlin.math.atan(kotlin.math.sinh(kotlin.math.PI * (1.0 - 2.0 * 0.625))) * 180.0 / kotlin.math.PI
            val coords = LatLon(lat, -45.0)
            val (px, py) = expectedPixel(coords, 0, tileSize)
            assertEquals(0.75, px, 1e-9)
            assertEquals(1.25, py, 1e-9)
            val actual = calc.getElevation(coords, zoomLevel = 0, interpolation = true)
            assertEquals(3.25, actual, 1e-9)
        }

    @Test
    fun `interpolation overflows tile via normalizePixel`() =
        runTest {
            // Elevation depends on the tile as well, so a fetch crossing a tile boundary is visible:
            // tx * 20_000 + ty * 10_000 + px * 100 + py, at most 30_303 — inside Terrarium's 16-bit range.
            val tileSize = 4
            val calc =
                makeCalculator(tileSize = tileSize) { _, tx, ty, px, py ->
                    tx * 20_000 + ty * 10_000 + px * 100 + py
                }
            // z=1, lon=-11.25: xFloat = 168.75 / 360 * 2 = 0.9375, tile x 0, px = 3.75. Between centres
            // fx = px - 0.5 = 3.25: x0 = 3, dx = 0.25, and x1 = 4 overflows **east** into tile x 1, pixel 0,
            // with a real weight. lat=0: yFloat = 1.0 exactly, tile y 1, py = 0.0, fy = -0.5: y0 = -1,
            // redirected **north** to tile y 0, pixel 3; y1 = 0 stays in tile y 1. dy = 0.5.
            val coords = LatLon(0.0, -11.25)
            val z = 1
            val (px, py) = expectedPixel(coords, z, tileSize)
            val fx = px - 0.5
            val fy = py - 0.5
            assertEquals(3, floor(fx).toInt(), "expected x0=3 for fx=$fx")
            assertEquals(-1, floor(fy).toInt(), "expected y0=-1 for fy=$fy")
            assertEquals(0.25, fx - floor(fx), 1e-9)

            val v = calc.getElevation(coords, zoomLevel = z, interpolation = true)
            // Row y0 (tile y 0, py 3): (0,0) px 3 = 303, (1,0) px 0 = 20_003 -> 303 * 0.75 + 20_003 * 0.25 = 5_228.
            // Row y1 (tile y 1, py 0): (0,1) px 3 = 10_300, (1,1) px 0 = 30_000 -> 7_725 + 7_500 = 15_225.
            // dy = 0.5 -> 10_226.5.
            assertEquals(10_226.5, v, 1e-9)
        }

    /**
     * A pixel's value stands for its **centre** (WMS `BBOX`, GDAL `GeoTransform`, Mapterhorn,
     * Terrarium): at the exact centre of pixel (1, 2), the bilinear read is that pixel alone, the
     * same pixel `interpolation = false` returns. The corner convention read `½ p(1,2) + ½ p(2,2)`
     * (and so on in y), i.e. the terrain half a pixel south-east.
     */
    @Test
    fun `interpolation=true at a pixel centre returns that pixel, like interpolation=false`() =
        runTest {
            val tileSize = 4
            val calc = makeCalculator(tileSize = tileSize) { _, _, _, px, py -> px * 100 + py }
            for ((cx, cy) in listOf(1 to 2, 2 to 1, 1 to 1, 2 to 2)) {
                val coords = pixelCentre(cx, cy, z = 0, tileSize = tileSize)
                val nearest = calc.getElevation(coords, zoomLevel = 0, interpolation = false)
                val bilinear = calc.getElevation(coords, zoomLevel = 0, interpolation = true)
                assertEquals((cx * 100 + cy).toDouble(), nearest, "nearest at centre of ($cx, $cy)")
                assertEquals((cx * 100 + cy).toDouble(), bilinear, 1e-12, "bilinear at centre of ($cx, $cy)")
            }
        }

    /**
     * Bilinear interpolation reproduces a plane exactly, so on a ramp `a·gx + b·gy` over global
     * pixel indices the read at continuous pixel position `(px, py)` is the plane at
     * `(px − ½, py − ½)` — sample `i` sits at `i + ½`. Points straddle tile edges on purpose.
     */
    @Test
    fun `a linear ramp is read without a half-pixel shift`() =
        runTest {
            val tileSize = 4
            val z = 2
            val a = 7
            val b = 3
            val calc =
                makeCalculator(tileSize = tileSize) { _, tx, ty, px, py ->
                    a * (tx * tileSize + px) + b * (ty * tileSize + py)
                }
            val points =
                listOf(
                    LatLon(10.0, 10.0),
                    LatLon(-20.0, 47.3),
                    LatLon(33.3, -100.0),
                    LatLon(0.5, 0.5), // near the four-tile corner at (0, 0)
                    LatLon(60.0, -0.4),
                )
            for (coords in points) {
                val pf = ElevationFunctions.toPixelFloat(coords, z, tileSize)
                val gx = pf.tile.x * tileSize + pf.x
                val gy = pf.tile.y * tileSize + pf.y
                val expected = a * (gx - 0.5) + b * (gy - 0.5)
                val actual = calc.getElevation(coords, zoomLevel = z, interpolation = true)
                assertEquals(expected, actual, 1e-12, "ramp at $coords (gx=$gx, gy=$gy)")
            }
        }

    @Test
    fun `internal failure is wrapped in IllegalStateException with the documented message`() =
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
