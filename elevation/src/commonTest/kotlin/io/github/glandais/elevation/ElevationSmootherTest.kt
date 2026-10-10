package io.github.glandais.elevation

import kotlin.math.absoluteValue
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private fun avgAbsAdjacentDelta(values: List<Double>): Double {
    if (values.size < 2) return 0.0
    var sum = 0.0
    for (i in 1 until values.size) sum += (values[i] - values[i - 1]).absoluteValue
    return sum / (values.size - 1)
}

class ElevationSmootherTest {
    @Test
    fun `returns original data for less than 3 points`() {
        val pts =
            listOf(
                LatLonElevation(45.0, 0.0, 100.0),
                LatLonElevation(45.001, 0.0, 150.0),
            )
        assertEquals(pts, ElevationSmoother.smooth(pts))
    }

    @Test
    fun `throws on zero or negative window size with the documented message`() {
        val pts =
            listOf(
                LatLonElevation(45.0, 0.0, 100.0),
                LatLonElevation(45.001, 0.0, 150.0),
                LatLonElevation(45.002, 0.0, 200.0),
            )
        val ex0 = assertFailsWith<IllegalArgumentException> { ElevationSmoother.smooth(pts, 0.0) }
        assertEquals("Invalid window size: 0. Must be positive", ex0.message)

        val exNeg = assertFailsWith<IllegalArgumentException> { ElevationSmoother.smooth(pts, -50.0) }
        assertEquals("Invalid window size: -50. Must be positive", exNeg.message)
    }

    @Test
    fun `smooths with default-equivalent 50m window`() {
        val pts =
            listOf(
                LatLonElevation(45.0, 0.0, 100.0),
                LatLonElevation(45.0001, 0.0, 200.0),
                LatLonElevation(45.0002, 0.0, 120.0),
                LatLonElevation(45.0003, 0.0, 180.0),
                LatLonElevation(45.0004, 0.0, 150.0),
            )
        val result = ElevationSmoother.smooth(pts, 50.0)

        assertEquals(pts.size, result.size)
        assertNotEquals(120.0, result[2].elevation)
        assertTrue(result[1].elevation > 100.0 && result[1].elevation < 200.0, "result[1]=${result[1].elevation}")
        assertTrue(result[0].elevation > 100.0, "result[0]=${result[0].elevation}")
        assertTrue(result[4].elevation < 180.0, "result[4]=${result[4].elevation}")
    }

    @Test
    fun `larger window smooths the spike more than a small window`() {
        val pts =
            listOf(
                LatLonElevation(45.0, 0.0, 100.0),
                LatLonElevation(45.0001, 0.0, 300.0),
                LatLonElevation(45.0002, 0.0, 100.0),
                LatLonElevation(45.0003, 0.0, 100.0),
                LatLonElevation(45.0004, 0.0, 100.0),
            )
        val small = ElevationSmoother.smooth(pts, 15.0)
        val large = ElevationSmoother.smooth(pts, 100.0)

        assertTrue(large[1].elevation < small[1].elevation, "large=${large[1].elevation}, small=${small[1].elevation}")
        assertTrue(small[1].elevation < 300.0)
        assertTrue(large[1].elevation < 300.0)
    }

    @Test
    fun `preserves elevation at edges with appropriate weights`() {
        val pts =
            listOf(
                LatLonElevation(45.0, 0.0, 500.0),
                LatLonElevation(45.0001, 0.0, 100.0),
                LatLonElevation(45.0002, 0.0, 100.0),
                LatLonElevation(45.0003, 0.0, 100.0),
                LatLonElevation(45.0004, 0.0, 600.0),
            )
        val r = ElevationSmoother.smooth(pts, 30.0)

        assertTrue(r[0].elevation > 200.0, "r[0]=${r[0].elevation}")
        assertTrue(r[0].elevation < 500.0)
        assertTrue(r[4].elevation > 200.0, "r[4]=${r[4].elevation}")
        assertTrue(r[4].elevation < 600.0)
        assertTrue(r[2].elevation > 100.0, "r[2]=${r[2].elevation}")
        assertTrue(r[2].elevation < 400.0)
    }

    @Test
    fun `points far apart are still smoothed over the window`() {
        // The kernel integrates the piecewise-linear profile over distance, so a sample whose
        // neighbours lie outside the window is not passed through: it is averaged with the
        // terrain inside the window. Exact values for a triangular window of half-width w:
        // a one-sided end moves by `slope * w / 3`, a V-shaped apex by the mean of both slopes.
        val pts =
            listOf(
                LatLonElevation(45.0, 0.0, 100.0),
                LatLonElevation(45.01, 0.0, 200.0),
                LatLonElevation(45.02, 0.0, 150.0),
            )
        val w = 50.0
        val r = ElevationSmoother.smooth(pts, w)
        val d = Distance.cumulativeDistances(pts)
        val s0 = 100.0 / (d[1] - d[0])
        val s1 = -50.0 / (d[2] - d[1])
        assertEquals(100.0 + s0 * w / 3.0, r[0].elevation, 1e-9)
        assertEquals(200.0 + (s1 - s0) / 2.0 * w / 3.0, r[1].elevation, 1e-9)
        assertEquals(150.0 - s1 * w / 3.0, r[2].elevation, 1e-9)
    }

    @Test
    fun `dense points influence one another`() {
        val pts =
            listOf(
                LatLonElevation(45.0, 0.0, 100.0),
                LatLonElevation(45.00001, 0.0, 200.0),
                LatLonElevation(45.00002, 0.0, 150.0),
                LatLonElevation(45.00003, 0.0, 250.0),
                LatLonElevation(45.00004, 0.0, 120.0),
            )
        val r = ElevationSmoother.smooth(pts, 10.0)
        assertNotEquals(150.0, r[2].elevation)
        assertTrue(r[2].elevation > 150.0, "r[2]=${r[2].elevation}")
    }

    @Test
    fun `applies triangular kernel weighting`() {
        val pts =
            listOf(
                LatLonElevation(45.0, 0.0, 0.0),
                LatLonElevation(45.0001, 0.0, 100.0),
                LatLonElevation(45.0002, 0.0, 0.0),
            )
        val r = ElevationSmoother.smooth(pts, 25.0)

        assertTrue(r[1].elevation < 100.0, "r[1]=${r[1].elevation}")
        assertTrue(r[1].elevation > 0.0)
        assertTrue(r[0].elevation > 0.0, "r[0]=${r[0].elevation}")
        assertTrue(r[2].elevation > 0.0, "r[2]=${r[2].elevation}")
    }

    @Test
    fun `a sparse straight ramp keeps its interior sample`() {
        // Neighbours far beyond the window: the interior sample still sees the ramp's own
        // terrain on both sides, which a symmetric kernel leaves unchanged; the ends move by the
        // one-sided `slope * w / 3`.
        val pts =
            listOf(
                LatLonElevation(45.0, 0.0, 100.0),
                LatLonElevation(46.0, 0.0, 200.0),
                LatLonElevation(47.0, 0.0, 300.0),
            )
        val w = 10.0
        val r = ElevationSmoother.smooth(pts, w)
        val d = Distance.cumulativeDistances(pts)
        val s0 = 100.0 / (d[1] - d[0])
        val s1 = 100.0 / (d[2] - d[1])
        assertEquals(100.0 + s0 * w / 3.0, r[0].elevation, 1e-9)
        assertEquals(200.0 + (s1 - s0) / 2.0 * w / 3.0, r[1].elevation, 1e-9)
        assertEquals(300.0 - s1 * w / 3.0, r[2].elevation, 1e-9)
    }

    @Test
    fun `inserting points on the profile's own segments leaves the original samples unchanged`() {
        val sparseD = doubleArrayOf(0.0, 37.0, 50.0, 120.0, 121.0, 300.0)
        val sparseE = doubleArrayOf(10.0, 25.0, 18.0, 40.0, 39.0, 5.0)
        // Every metre of the same piecewise-linear terrain, original vertices included.
        val denseD = DoubleArray(301) { it.toDouble() }
        val denseE =
            DoubleArray(301) { k ->
                val x = denseD[k]
                var j = 0
                while (j < sparseD.size - 2 && x > sparseD[j + 1]) j++
                sparseE[j] + (sparseE[j + 1] - sparseE[j]) * (x - sparseD[j]) / (sparseD[j + 1] - sparseD[j])
            }
        for (w in doubleArrayOf(5.0, 30.0, 150.0)) {
            val sparse = ElevationSmoother.smoothProfile(sparseD, sparseE, w)
            val dense = ElevationSmoother.smoothProfile(denseD, denseE, w)
            for (i in sparseD.indices) {
                assertEquals(sparse[i], dense[sparseD[i].toInt()], 1e-9, "w=$w, vertex at ${sparseD[i]} m")
            }
        }
    }

    @Test
    fun `preserves coordinates while smoothing elevations`() {
        val pts =
            listOf(
                LatLonElevation(45.123, -122.456, 100.0),
                LatLonElevation(45.1231, -122.456, 200.0),
                LatLonElevation(45.1232, -122.456, 150.0),
            )
        val r = ElevationSmoother.smooth(pts, 50.0)

        assertEquals(45.123, r[0].latitude)
        assertEquals(-122.456, r[0].longitude)
        assertEquals(45.1231, r[1].latitude)
        assertEquals(-122.456, r[1].longitude)
        assertEquals(45.1232, r[2].latitude)
        assertEquals(-122.456, r[2].longitude)

        assertNotEquals(100.0, r[0].elevation)
        assertNotEquals(200.0, r[1].elevation)
        assertNotEquals(150.0, r[2].elevation)
    }

    @Test
    fun `smooths a realistic elevation profile`() {
        val pts =
            listOf(
                LatLonElevation(45.0000, 0.0, 100.0),
                LatLonElevation(45.0001, 0.0, 105.0),
                LatLonElevation(45.0002, 0.0, 112.0),
                LatLonElevation(45.0003, 0.0, 108.0),
                LatLonElevation(45.0004, 0.0, 120.0),
                LatLonElevation(45.0005, 0.0, 125.0),
                LatLonElevation(45.0006, 0.0, 130.0),
                LatLonElevation(45.0007, 0.0, 128.0),
                LatLonElevation(45.0008, 0.0, 125.0),
                LatLonElevation(45.0009, 0.0, 120.0),
                LatLonElevation(45.0010, 0.0, 115.0),
            )
        val r = ElevationSmoother.smooth(pts, 40.0)

        assertTrue(r[3].elevation > 108.0, "r[3]=${r[3].elevation}")
        assertTrue(r[7].elevation < 130.0, "r[7]=${r[7].elevation}")

        val maxOriginal = pts.fold(Double.NEGATIVE_INFINITY) { acc, p -> max(acc, p.elevation) }
        val maxSmoothed = r.fold(Double.NEGATIVE_INFINITY) { acc, p -> max(acc, p.elevation) }
        assertTrue(maxSmoothed <= maxOriginal, "maxSmoothed=$maxSmoothed > maxOriginal=$maxOriginal")

        val avg = avgAbsAdjacentDelta(r.map { it.elevation })
        assertTrue(avg < 10.0, "avgAbsAdjacentDelta=$avg")
    }
}
