package io.github.glandais.elevation

import kotlin.math.absoluteValue
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val EPS = 1e-9

private fun assertVectorClose(
    expected: Vector3D,
    actual: Vector3D,
    eps: Double = EPS,
) {
    assertTrue((expected.x - actual.x).absoluteValue < eps, "x: expected $expected got $actual")
    assertTrue((expected.y - actual.y).absoluteValue < eps, "y: expected $expected got $actual")
    assertTrue((expected.z - actual.z).absoluteValue < eps, "z: expected $expected got $actual")
}

class Vector3DTest {
    @Test
    fun `distanceTo - euclidean 3D`() {
        val a = Vector3D(1.0, 2.0, 3.0)
        val b = Vector3D(4.0, 6.0, 8.0)
        assertTrue((a.distanceTo(b) - sqrt(50.0)).absoluteValue < EPS)
    }

    @Test
    fun `distanceTo - symmetric`() {
        val a = Vector3D(1.0, 2.0, 3.0)
        val b = Vector3D(4.0, 6.0, 8.0)
        assertTrue((a.distanceTo(b) - b.distanceTo(a)).absoluteValue < EPS)
    }

    @Test
    fun `minus - subtracts componentwise`() {
        val r = Vector3D(5.0, 7.0, 9.0) - Vector3D(1.0, 2.0, 3.0)
        assertEquals(Vector3D(4.0, 5.0, 6.0), r)
    }

    @Test
    fun `plus - adds componentwise`() {
        val r = Vector3D(1.0, 1.0, 1.0) + Vector3D(2.0, 3.0, 4.0)
        assertEquals(Vector3D(3.0, 4.0, 5.0), r)
    }

    @Test
    fun `times - scales by scalar`() {
        val r = Vector3D(1.0, 2.0, 3.0) * 2.5
        assertEquals(Vector3D(2.5, 5.0, 7.5), r)
    }

    @Test
    fun `dot - product`() {
        val r = Vector3D(1.0, 2.0, 3.0).dot(Vector3D(4.0, 5.0, 6.0))
        assertEquals(32.0, r)
    }

    @Test
    fun `cross - x cross y is z`() {
        val r = Vector3D(1.0, 0.0, 0.0).cross(Vector3D(0.0, 1.0, 0.0))
        assertEquals(Vector3D(0.0, 0.0, 1.0), r)
    }

    @Test
    fun `cross - anticommutative`() {
        val a = Vector3D(1.0, 2.0, 3.0)
        val b = Vector3D(4.0, 5.0, 6.0)
        val ab = a.cross(b)
        val ba = b.cross(a)
        assertVectorClose(Vector3D(-ab.x, -ab.y, -ab.z), ba)
    }

    @Test
    fun `magnitude - 3-4-12 is 13`() {
        assertEquals(13.0, Vector3D(3.0, 4.0, 12.0).magnitude())
    }

    @Test
    fun `magnitude - zero vector`() {
        assertEquals(0.0, Vector3D.ZERO.magnitude())
    }

    @Test
    fun `normalize - 3-4-0 yields unit`() {
        val n = Vector3D(3.0, 4.0, 0.0).normalize()
        assertVectorClose(Vector3D(0.6, 0.8, 0.0), n)
        assertTrue((n.magnitude() - 1.0).absoluteValue < EPS)
    }

    @Test
    fun `normalize - zero stays zero`() {
        assertEquals(Vector3D.ZERO, Vector3D.ZERO.normalize())
    }

    @Test
    fun `distanceToSegment - point on segment`() {
        val start = Vector3D(0.0, 0.0, 0.0)
        val end = Vector3D(10.0, 0.0, 0.0)
        val p = Vector3D(3.0, 0.0, 0.0)
        assertTrue(p.distanceToSegment(start, end).absoluteValue < EPS)
    }

    @Test
    fun `distanceToSegment - projection before start`() {
        val start = Vector3D(0.0, 0.0, 0.0)
        val end = Vector3D(10.0, 0.0, 0.0)
        val p = Vector3D(-5.0, 0.0, 0.0)
        assertTrue((p.distanceToSegment(start, end) - 5.0).absoluteValue < EPS)
    }

    @Test
    fun `distanceToSegment - projection after end`() {
        val start = Vector3D(0.0, 0.0, 0.0)
        val end = Vector3D(10.0, 0.0, 0.0)
        val p = Vector3D(15.0, 0.0, 0.0)
        assertTrue((p.distanceToSegment(start, end) - 5.0).absoluteValue < EPS)
    }

    @Test
    fun `distanceToSegment - perpendicular midpoint`() {
        val start = Vector3D(0.0, 0.0, 0.0)
        val end = Vector3D(10.0, 0.0, 0.0)
        val p = Vector3D(5.0, 3.0, 0.0)
        assertTrue((p.distanceToSegment(start, end) - 3.0).absoluteValue < EPS)
    }

    @Test
    fun `distanceToSegment - degenerate segment falls back to distance to start`() {
        val s = Vector3D(5.0, 5.0, 5.0)
        val p = Vector3D(1.0, 2.0, 3.0)
        assertTrue((p.distanceToSegment(s, s) - p.distanceTo(s)).absoluteValue < EPS)
    }

    @Test
    fun `data class equality`() {
        assertEquals(Vector3D(1.0, 2.0, 3.0), Vector3D(1.0, 2.0, 3.0))
        assertEquals(Vector3D(1.0, 2.0, 3.0).hashCode(), Vector3D(1.0, 2.0, 3.0).hashCode())
        assertNotEquals(Vector3D(1.0, 2.0, 3.0), Vector3D(1.0, 2.0, 3.5))
    }

    @Test
    fun `ZERO companion`() {
        assertEquals(Vector3D(0.0, 0.0, 0.0), Vector3D.ZERO)
    }
}
