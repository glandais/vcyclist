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
    fun `points far apart are unchanged`() {
        val pts =
            listOf(
                LatLonElevation(45.0, 0.0, 100.0),
                LatLonElevation(45.01, 0.0, 200.0),
                LatLonElevation(45.02, 0.0, 150.0),
            )
        val r = ElevationSmoother.smooth(pts, 50.0)
        assertEquals(100.0, r[0].elevation)
        assertEquals(200.0, r[1].elevation)
        assertEquals(150.0, r[2].elevation)
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
    fun `points beyond window keep their original elevation`() {
        val pts =
            listOf(
                LatLonElevation(45.0, 0.0, 100.0),
                LatLonElevation(46.0, 0.0, 200.0),
                LatLonElevation(47.0, 0.0, 300.0),
            )
        val r = ElevationSmoother.smooth(pts, 10.0)
        assertEquals(100.0, r[0].elevation)
        assertEquals(200.0, r[1].elevation)
        assertEquals(300.0, r[2].elevation)
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
