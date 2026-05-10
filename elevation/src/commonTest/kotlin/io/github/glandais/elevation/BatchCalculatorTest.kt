package io.github.glandais.elevation

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A counting fetcher that produces synthetic Terrarium tiles (elevation = px * 100 + py at every pixel). */
private class CountingFetcher(
    private val tileSize: Int = 4,
) {
    val urls = mutableListOf<String>()

    val fetch: suspend (String) -> RawTile = { url ->
        urls += url
        val rgba = ByteArray(tileSize * tileSize * 4)
        for (py in 0 until tileSize) {
            for (px in 0 until tileSize) {
                val e = px * 100 + py
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

    fun makeBatchCalculator(cacheSize: Int = 16): BatchCalculator {
        val tm = TileManager("test://{z}/{x}/{y}", cacheSize, fetch)
        val calc = ElevationCalculator(tm, tileSize = tileSize)
        return BatchCalculator(calc)
    }
}

class BatchCalculatorTest {
    @Test
    fun `setElevations same tile fetches once`() =
        runTest {
            val cf = CountingFetcher()
            val bc = cf.makeBatchCalculator()
            val coords =
                listOf(
                    LatLon(0.0, 0.0),
                    LatLon(0.0, 1.0),
                    LatLon(0.0, 2.0),
                    LatLon(0.0, 3.0),
                    LatLon(0.0, 4.0),
                )
            val res = bc.setElevations(coords, zoomLevel = 0, interpolation = false)
            assertEquals(5, res.size)
            assertEquals(1, cf.urls.distinct().size, "expected 1 unique tile, got ${cf.urls}")
        }

    @Test
    fun `setElevations distinct tiles fetches each`() =
        runTest {
            val cf = CountingFetcher()
            val bc = cf.makeBatchCalculator()
            // At z=2, n=4; each lon=90° lands in a distinct tile column.
            val coords =
                listOf(
                    LatLon(0.0, -135.0),
                    LatLon(0.0, -45.0),
                    LatLon(0.0, 45.0),
                    LatLon(0.0, 135.0),
                )
            bc.setElevations(coords, zoomLevel = 2, interpolation = false)
            assertEquals(4, cf.urls.distinct().size, "urls=${cf.urls}")
        }

    @Test
    fun `setElevations preserves order`() =
        runTest {
            val cf = CountingFetcher()
            val bc = cf.makeBatchCalculator()
            val coords = listOf(LatLon(0.0, -135.0), LatLon(0.0, 0.0), LatLon(0.0, 135.0))
            val res = bc.setElevations(coords, zoomLevel = 2, interpolation = false)
            assertEquals(coords.size, res.size)
            for (i in coords.indices) {
                assertEquals(coords[i].latitude, res[i].latitude)
                assertEquals(coords[i].longitude, res[i].longitude)
            }
        }

    @Test
    fun `setElevations on empty input returns empty`() =
        runTest {
            val cf = CountingFetcher()
            val bc = cf.makeBatchCalculator()
            assertEquals(emptyList(), bc.setElevations(emptyList(), 0, false))
            assertEquals(0, cf.urls.size)
        }

    @Test
    fun `getElevationsAlong rejects path of size 1`() =
        runTest {
            val bc = CountingFetcher().makeBatchCalculator()
            val ex =
                assertFailsWith<IllegalArgumentException> {
                    bc.getElevationsAlong(listOf(LatLon(0.0, 0.0)), zoomLevel = 0, step = 10.0)
                }
            assertEquals("Path must contain at least 2 coordinates", ex.message)
        }

    @Test
    fun `getElevationsAlong rejects step less or equal to 1 meter`() =
        runTest {
            val bc = CountingFetcher().makeBatchCalculator()
            val ex =
                assertFailsWith<IllegalArgumentException> {
                    bc.getElevationsAlong(
                        listOf(LatLon(0.0, 0.0), LatLon(0.0, 0.001)),
                        zoomLevel = 0,
                        step = 1.0,
                    )
                }
            assertEquals("Step is too small: 1 meters", ex.message)
        }

    @Test
    fun `generateCoordinatesBetween short distance returns endpoints only`() {
        val bc = CountingFetcher().makeBatchCalculator()
        val a = LatLon(0.0, 0.0)
        val b = LatLon(0.0, 0.0001)
        val r = bc.generateCoordinatesBetween(a, b, step = 100.0, distance = 50.0)
        assertEquals(listOf(a, b), r)
    }

    @Test
    fun `generateCoordinatesBetween splits at step`() {
        val bc = CountingFetcher().makeBatchCalculator()
        val a = LatLon(0.0, 0.0)
        val b = LatLon(0.0, 1.0)
        val r = bc.generateCoordinatesBetween(a, b, step = 10.0, distance = 25.0)
        // numSteps = 25/10 = 2 → output: a, +1*step, +2*step, b ⇒ 4 points
        assertEquals(4, r.size)
        assertEquals(a, r[0])
        assertEquals(b, r[3])
        // Intermediate fractions: 0.4, 0.8 of (lat=0, lon=1)
        assertEquals(0.4, r[1].longitude)
        assertEquals(0.8, r[2].longitude)
    }

    @Test
    fun `generateCoordinatesAlong skips segments shorter than minDistance`() {
        val bc = CountingFetcher().makeBatchCalculator()
        // 3 points; segment 1→2 is < 1m, segment 2→3 is ~111km.
        val path = listOf(LatLon(0.0, 0.0), LatLon(0.0, 0.000001), LatLon(0.0, 1.0))
        val out = bc.generateCoordinatesAlong(path, step = 100_000.0, minDistance = 1.0)
        // First point always included; the tiny segment is skipped so middle point is NOT inserted;
        // segment 2→3 generates intermediates from 2 to 3.
        assertEquals(path[0], out.first())
        assertEquals(path[2], out.last())
        // middle point of path (path[1]) is skipped because seg 1→2 < minDistance
        assertTrue(path[1] !in out, "tiny segment endpoint should be skipped: out=$out")
    }

    @Test
    fun `getElevationsAlong applies smoothing when enabled`() =
        runTest {
            val bc = CountingFetcher().makeBatchCalculator()
            val path = listOf(LatLon(0.0, 0.0), LatLon(0.0, 0.0005))
            // Without smoothing
            val raw = bc.getElevationsAlong(path, zoomLevel = 0, step = 2.0, minDistance = 1.0)
            // With smoothing enabled — must produce the same number of points, but elevations are averaged.
            val smoothed =
                bc.getElevationsAlong(
                    path,
                    zoomLevel = 0,
                    step = 2.0,
                    minDistance = 1.0,
                    smoothingOptions = SmoothingOptions(windowSize = 50.0, enabled = true),
                )
            assertEquals(raw.size, smoothed.size)
            // Smoothed should differ from raw at least on one interior point (window > step).
            if (raw.size >= 3) {
                assertTrue(raw[1].elevation != smoothed[1].elevation || raw[2].elevation != smoothed[2].elevation)
            }
        }

    @Test
    fun `getElevationsAlong applies filtering when enabled`() =
        runTest {
            val bc = CountingFetcher().makeBatchCalculator()
            val path = listOf(LatLon(0.0, 0.0), LatLon(0.0, 0.0005))
            val raw = bc.getElevationsAlong(path, zoomLevel = 0, step = 2.0, minDistance = 1.0)
            val filtered =
                bc.getElevationsAlong(
                    path,
                    zoomLevel = 0,
                    step = 2.0,
                    minDistance = 1.0,
                    filterOptions = FilterOptions(tolerance = 1_000_000.0, zExaggeration = 1.0, enabled = true),
                )
            assertTrue(filtered.size <= raw.size, "filtered=${filtered.size}, raw=${raw.size}")
            assertEquals(raw.first(), filtered.first())
            assertEquals(raw.last(), filtered.last())
        }

    @Test
    fun `getElevationsAlong chains smoothing then filtering`() =
        runTest {
            val bc = CountingFetcher().makeBatchCalculator()
            val path = listOf(LatLon(0.0, 0.0), LatLon(0.0, 0.001))
            val r =
                bc.getElevationsAlong(
                    path,
                    zoomLevel = 0,
                    step = 5.0,
                    minDistance = 1.0,
                    smoothingOptions = SmoothingOptions(windowSize = 30.0, enabled = true),
                    filterOptions = FilterOptions(tolerance = 1.0, zExaggeration = 3.0, enabled = true),
                )
            assertTrue(r.isNotEmpty())
            assertEquals(path.first().latitude, r.first().latitude)
        }
}
