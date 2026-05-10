package io.github.glandais.elevation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun createTestPath(): List<CoordinatesElevation> =
    listOf(
        LatLonElevation(0.0, 0.0, 0.0),
        LatLonElevation(0.001, 0.001, 50.0),
        LatLonElevation(0.002, 0.002, 25.0),
        LatLonElevation(0.003, 0.003, 100.0),
    )

class DouglasPeuckerTest {
    @Test
    fun `very low tolerance preserves the full path`() {
        val path = createTestPath()
        val simplified = DouglasPeucker.simplify(path, 0.1)
        assertEquals(path.size, simplified.size)
        assertEquals(path.first(), simplified.first())
        assertEquals(path.last(), simplified.last())
    }

    @Test
    fun `very high tolerance simplifies the path but keeps endpoints`() {
        val path = createTestPath()
        val simplified = DouglasPeucker.simplify(path, 1000.0)
        assertTrue(simplified.size < path.size, "size=${simplified.size}")
        assertEquals(path.first(), simplified.first())
        assertEquals(path.last(), simplified.last())
    }

    @Test
    fun `path of 2 points is returned as-is`() {
        val short =
            listOf(
                LatLonElevation(0.0, 0.0, 0.0),
                LatLonElevation(0.001, 0.001, 100.0),
            )
        val simplified = DouglasPeucker.simplify(short, 10.0)
        assertEquals(2, simplified.size)
        assertEquals(short, simplified)
    }

    @Test
    fun `path of 1 point is returned as a defensive copy`() {
        val single = listOf(LatLonElevation(0.0, 0.0, 0.0))
        val simplified = DouglasPeucker.simplify(single, 10.0)
        assertEquals(1, simplified.size)
        assertEquals(single, simplified)
    }

    @Test
    fun `empty path returns empty`() {
        val empty = emptyList<CoordinatesElevation>()
        val simplified = DouglasPeucker.simplify(empty, 10.0)
        assertEquals(emptyList(), simplified)
    }

    @Test
    fun `flat path with identical elevations keeps at least endpoints`() {
        val flat =
            listOf(
                LatLonElevation(0.0, 0.0, 100.0),
                LatLonElevation(0.001, 0.001, 100.0),
                LatLonElevation(0.002, 0.002, 100.0),
            )
        val simplified = DouglasPeucker.simplify(flat, 5.0)
        assertTrue(simplified.size >= 2, "size=${simplified.size}")
        assertEquals(flat.first(), simplified.first())
        assertEquals(flat.last(), simplified.last())
    }

    @Test
    fun `real-world Zermatt path with 20m tolerance`() {
        val mountain =
            listOf(
                LatLonElevation(46.5197, 9.8544, 1650.0),
                LatLonElevation(46.5200, 9.8547, 1680.0),
                LatLonElevation(46.5203, 9.8550, 1720.0),
                LatLonElevation(46.5206, 9.8553, 1750.0),
                LatLonElevation(46.5209, 9.8556, 1780.0),
            )
        val simplified = DouglasPeucker.simplify(mountain, 20.0)
        assertTrue(simplified.size >= 2, "size=${simplified.size}")
        assertEquals(mountain.first(), simplified.first())
        assertEquals(mountain.last(), simplified.last())
    }

    @Test
    fun `extreme tolerance collapses to exactly first and last`() {
        val many = (0..9).map { i -> LatLonElevation(i * 0.0001, i * 0.0001, i * 10.0) }
        val simplified = DouglasPeucker.simplify(many, 1e9)
        assertEquals(2, simplified.size)
        assertEquals(many.first(), simplified[0])
        assertEquals(many.last(), simplified[1])
    }

    @Test
    fun `mutating input list after simplify does not affect output`() {
        val input = createTestPath().toMutableList()
        val simplified = DouglasPeucker.simplify(input, 0.1)
        val snapshot = simplified.toList()
        input.removeAt(0)
        input.add(LatLonElevation(99.0, 99.0, 99.0))
        assertEquals(snapshot, simplified)
    }
}
