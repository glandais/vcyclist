package io.github.glandais.elevation

import kotlin.math.PI
import kotlin.math.absoluteValue
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val EPS = 1e-9

class DistanceTest {
    // haversine

    @Test
    fun `haversine - close points near Paris`() {
        val a = LatLon(48.8566, 2.3522)
        val b = LatLon(48.8606, 2.3376)
        val d = Distance.haversine(a, b)
        assertTrue(d > 1000.0, "expected > 1000, got $d")
        assertTrue(d < 1500.0, "expected < 1500, got $d")
    }

    @Test
    fun `haversine - Paris to London`() {
        val paris = LatLon(48.8566, 2.3522)
        val london = LatLon(51.5074, -0.1278)
        val d = Distance.haversine(paris, london)
        assertTrue(d > 340_000.0, "expected > 340_000, got $d")
        assertTrue(d < 350_000.0, "expected < 350_000, got $d")
    }

    @Test
    fun `haversine - same coordinates return zero`() {
        val p = LatLon(48.8566, 2.3522)
        assertEquals(0.0, Distance.haversine(p, p))
    }

    @Test
    fun `haversine - one degree at equator is ~111 km`() {
        val a = LatLon(0.0, 0.0)
        val b = LatLon(0.0, 1.0)
        val d = Distance.haversine(a, b)
        assertTrue(d > 110_000.0, "expected > 110_000, got $d")
        assertTrue(d < 112_000.0, "expected < 112_000, got $d")
    }

    @Test
    fun `haversine - antipodal points`() {
        val a = LatLon(0.0, 0.0)
        val b = LatLon(0.0, 180.0)
        val d = Distance.haversine(a, b)
        assertTrue(d > 19_900_000.0, "expected > 19_900_000, got $d")
        assertTrue(d < 20_100_000.0, "expected < 20_100_000, got $d")
    }

    @Test
    fun `haversine - fine parity equator 1deg lon = PI x R_mean over 180`() {
        val a = LatLon(0.0, 0.0)
        val b = LatLon(0.0, 1.0)
        val expected = PI * EarthConstants.MEAN_RADIUS / 180.0
        val d = Distance.haversine(a, b)
        assertTrue((d - expected).absoluteValue < EPS, "expected $expected, got $d (diff ${(d - expected).absoluteValue})")
    }

    // euclidean3D

    @Test
    fun `euclidean3D - 3-4-5 triangle`() {
        assertEquals(5.0, Distance.euclidean3D(Vector3D(0.0, 0.0, 0.0), Vector3D(3.0, 4.0, 0.0)))
    }

    @Test
    fun `euclidean3D - same point zero`() {
        val p = Vector3D(1.0, 2.0, 3.0)
        assertEquals(0.0, Distance.euclidean3D(p, p))
    }

    @Test
    fun `euclidean3D - true 3D`() {
        assertEquals(5.0, Distance.euclidean3D(Vector3D(1.0, 1.0, 1.0), Vector3D(4.0, 5.0, 1.0)))
    }

    // pointToSegment3D

    @Test
    fun `pointToSegment3D - perpendicular to segment`() {
        val d =
            Distance.pointToSegment3D(
                Vector3D(0.0, 1.0, 0.0),
                Vector3D(-1.0, 0.0, 0.0),
                Vector3D(1.0, 0.0, 0.0),
            )
        assertEquals(1.0, d)
    }

    @Test
    fun `pointToSegment3D - point at endpoint returns zero`() {
        val d =
            Distance.pointToSegment3D(
                Vector3D(1.0, 0.0, 0.0),
                Vector3D(-1.0, 0.0, 0.0),
                Vector3D(1.0, 0.0, 0.0),
            )
        assertEquals(0.0, d)
    }

    @Test
    fun `pointToSegment3D - zero-length segment falls back to distance to start`() {
        val d =
            Distance.pointToSegment3D(
                Vector3D(1.0, 1.0, 0.0),
                Vector3D(0.0, 0.0, 0.0),
                Vector3D(0.0, 0.0, 0.0),
            )
        assertTrue((d - sqrt(2.0)).absoluteValue < EPS, "expected sqrt(2), got $d")
    }

    @Test
    fun `pointToSegment3D - projection beyond end clamps to end`() {
        val d =
            Distance.pointToSegment3D(
                Vector3D(5.0, 0.0, 0.0),
                Vector3D(0.0, 0.0, 0.0),
                Vector3D(1.0, 0.0, 0.0),
            )
        assertTrue((d - 4.0).absoluteValue < EPS, "expected 4.0, got $d")
    }

    @Test
    fun `pointToSegment3D - projection before start clamps to start`() {
        val d =
            Distance.pointToSegment3D(
                Vector3D(-3.0, 0.0, 0.0),
                Vector3D(0.0, 0.0, 0.0),
                Vector3D(1.0, 0.0, 0.0),
            )
        assertTrue((d - 3.0).absoluteValue < EPS, "expected 3.0, got $d")
    }

    // cumulativeDistances

    @Test
    fun `cumulativeDistances - 4 points spaced ~11m`() {
        val pts =
            listOf(
                LatLon(45.0, 0.0),
                LatLon(45.0001, 0.0),
                LatLon(45.0002, 0.0),
                LatLon(45.0003, 0.0),
            )
        val out = Distance.cumulativeDistances(pts)
        assertEquals(4, out.size)
        assertEquals(0.0, out[0])
        assertTrue(out[1] > 10.0 && out[1] < 12.0, "out[1]=${out[1]}")
        assertTrue(out[2] > 20.0 && out[2] < 24.0, "out[2]=${out[2]}")
        assertTrue(out[3] > 30.0 && out[3] < 36.0, "out[3]=${out[3]}")
    }

    @Test
    fun `cumulativeDistances - single point yields zero`() {
        val out = Distance.cumulativeDistances(listOf(LatLon(45.0, 0.0)))
        assertEquals(1, out.size)
        assertEquals(0.0, out[0])
    }

    @Test
    fun `cumulativeDistances - empty list yields zero`() {
        val out = Distance.cumulativeDistances(emptyList())
        assertEquals(1, out.size)
        assertEquals(0.0, out[0])
    }

    // totalPathDistance

    @Test
    fun `totalPathDistance - 3 points spaced ~11m`() {
        val pts =
            listOf(
                LatLon(45.0, 0.0),
                LatLon(45.0001, 0.0),
                LatLon(45.0002, 0.0),
            )
        val d = Distance.totalPathDistance(pts)
        assertTrue(d > 20.0 && d < 24.0, "d=$d")
    }

    @Test
    fun `totalPathDistance - single point zero`() {
        assertEquals(0.0, Distance.totalPathDistance(listOf(LatLon(45.0, 0.0))))
    }

    @Test
    fun `totalPathDistance - empty zero`() {
        assertEquals(0.0, Distance.totalPathDistance(emptyList()))
    }
}
