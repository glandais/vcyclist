package io.github.glandais.elevation

import kotlin.math.absoluteValue
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val EPS = 1e-6

private fun assertVectorClose(
    expected: Vector3D,
    actual: Vector3D,
    eps: Double = EPS,
) {
    assertTrue((expected.x - actual.x).absoluteValue < eps, "x: expected $expected got $actual")
    assertTrue((expected.y - actual.y).absoluteValue < eps, "y: expected $expected got $actual")
    assertTrue((expected.z - actual.z).absoluteValue < eps, "z: expected $expected got $actual")
}

class EcefConverterTest {
    private val a = EarthConstants.SEMI_MAJOR_AXIS
    private val polarSemiAxis =
        EarthConstants.SEMI_MAJOR_AXIS *
            sqrt(1.0 - EarthConstants.FIRST_ECCENTRICITY_SQUARED)

    @Test
    fun `origin - lat 0 lon 0 ele 0`() {
        val v = EcefConverter.toEcef(LatLonElevation(0.0, 0.0, 0.0), zExaggeration = 0.0)
        assertVectorClose(Vector3D(a, 0.0, 0.0), v)
    }

    @Test
    fun `equator 90E`() {
        val v = EcefConverter.toEcef(LatLonElevation(0.0, 90.0, 0.0), zExaggeration = 0.0)
        assertVectorClose(Vector3D(0.0, a, 0.0), v)
    }

    @Test
    fun `equator 180`() {
        val v = EcefConverter.toEcef(LatLonElevation(0.0, 180.0, 0.0), zExaggeration = 0.0)
        assertVectorClose(Vector3D(-a, 0.0, 0.0), v)
    }

    @Test
    fun `north pole - z equals polar semi-axis`() {
        val v = EcefConverter.toEcef(LatLonElevation(90.0, 0.0, 0.0), zExaggeration = 0.0)
        assertVectorClose(Vector3D(0.0, 0.0, polarSemiAxis), v)
    }

    @Test
    fun `south pole - z equals minus polar semi-axis`() {
        val v = EcefConverter.toEcef(LatLonElevation(-90.0, 0.0, 0.0), zExaggeration = 0.0)
        assertVectorClose(Vector3D(0.0, 0.0, -polarSemiAxis), v)
    }

    @Test
    fun `magnitude on ellipsoid lies between polar and equatorial radii`() {
        val v = EcefConverter.toEcef(LatLonElevation(45.0, 0.0, 0.0), zExaggeration = 0.0)
        val mag = v.magnitude()
        assertTrue(mag >= polarSemiAxis - EPS, "magnitude $mag below polar semi-axis $polarSemiAxis")
        assertTrue(mag <= a + EPS, "magnitude $mag above semi-major axis $a")
    }

    @Test
    fun `default zExaggeration is 3`() {
        val v = EcefConverter.toEcef(LatLon(0.0, 0.0, 1000.0))
        assertTrue((v.x - (a + 3000.0)).absoluteValue < EPS, "x=${v.x}, expected ${a + 3000.0}")
    }

    @Test
    fun `explicit zExaggeration 0 ignores elevation`() {
        val v = EcefConverter.toEcef(LatLonElevation(0.0, 0.0, 1000.0), zExaggeration = 0.0)
        assertTrue((v.x - a).absoluteValue < EPS, "x=${v.x}, expected $a")
    }

    @Test
    fun `null elevation treated as zero`() {
        val v = EcefConverter.toEcef(LatLon(0.0, 0.0, elevation = null), zExaggeration = 3.0)
        assertTrue(!v.x.isNaN() && !v.y.isNaN() && !v.z.isNaN())
        assertTrue((v.x - a).absoluteValue < EPS, "x=${v.x}, expected $a")
    }

    @Test
    fun `antipodal points produce opposite x`() {
        val v1 = EcefConverter.toEcef(LatLonElevation(0.0, 0.0, 0.0), zExaggeration = 0.0)
        val v2 = EcefConverter.toEcef(LatLonElevation(0.0, 180.0, 0.0), zExaggeration = 0.0)
        assertTrue((v1.x + v2.x).absoluteValue < EPS, "v1.x=${v1.x}, v2.x=${v2.x}")
        assertTrue(v1.y.absoluteValue < EPS && v2.y.absoluteValue < EPS)
        assertTrue(v1.z.absoluteValue < EPS && v2.z.absoluteValue < EPS)
    }

    @Test
    fun `convertBatch preserves order and size`() {
        val pts =
            listOf(
                LatLonElevation(0.0, 0.0, 0.0),
                LatLonElevation(45.0, 0.0, 0.0),
                LatLonElevation(45.0, 90.0, 0.0),
                LatLonElevation(-45.0, 0.0, 100.0),
                LatLonElevation(0.0, 90.0, 500.0),
            )
        val batch = EcefConverter.convertBatch(pts, zExaggeration = 2.0)
        assertEquals(5, batch.size)
        for ((i, p) in pts.withIndex()) {
            assertEquals(EcefConverter.toEcef(p, zExaggeration = 2.0), batch[i])
        }
    }

    @Test
    fun `convertBatch on empty list`() {
        assertEquals(emptyList(), EcefConverter.convertBatch(emptyList()))
    }

    @Test
    fun `convertBatch propagates custom zExaggeration`() {
        val pts =
            listOf(
                LatLonElevation(0.0, 0.0, 100.0),
                LatLonElevation(0.0, 90.0, 200.0),
            )
        val batch = EcefConverter.convertBatch(pts, zExaggeration = 5.0)
        for ((i, p) in pts.withIndex()) {
            assertEquals(EcefConverter.toEcef(p, zExaggeration = 5.0), batch[i])
        }
    }
}
