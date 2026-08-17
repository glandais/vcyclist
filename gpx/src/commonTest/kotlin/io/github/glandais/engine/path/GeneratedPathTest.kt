package io.github.glandais.engine.path

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Concrete subclass used only by tests (the production [GeneratedPath] is abstract). */
private class TestPath(
    size: Int,
) : GeneratedPath(size) {
    fun internalDataSize(): Int = data.size
}

class GeneratedPathTest {
    @Test
    fun `negative size is rejected`() {
        assertFailsWith<IllegalArgumentException> { TestPath(-1) }
    }

    @Test
    fun `size zero is allowed and produces empty data`() {
        val path = TestPath(0)
        assertEquals(0, path.internalDataSize())
    }

    @Test
    fun `data length is size times COUNT`() {
        val path = TestPath(7)
        assertEquals(7 * PointField.COUNT, path.internalDataSize())
    }

    /**
     * The load-bearing test for the `nanDefault` mechanism.
     *
     * `MaxSpeedComputer` gates on `trajectoryCurvature(i).isNaN()` to decide whether the
     * curvature estimator ran. If a fresh [GeneratedPath] ever reads `0.0` there instead, the
     * sentinel is dead: a zero curvature is a legal value meaning "straight", so every point
     * would be treated as estimator-written and the cornering limits would be computed from a
     * radius of 1e9. Equally, if the NaN fill ever leaked into a non-flagged slot, every
     * unwritten field in the codebase would become NaN and propagate through the whole pipeline.
     */
    @Test
    fun `a fresh path reads NaN for nanDefault fields and zero for all others`() {
        val size = 3
        val path = TestPath(size)
        val flagged = PointField.entries.filter { it.nanDefault }
        assertTrue(flagged.isNotEmpty(), "no field declares nanDefault - this test would be vacuous")
        for (i in 0 until size) {
            for (field in PointField.entries) {
                val v = path.get(i, field)
                if (field.nanDefault) {
                    assertTrue(v.isNaN(), "$field at i=$i should default to NaN, was $v")
                } else {
                    assertEquals(0.0, v, "$field at i=$i should default to 0.0, was $v")
                }
            }
        }
    }

    @Test
    fun `nanDefault fields are still writable and readable`() {
        val path = TestPath(2)
        path.setTrajectoryCurvature(1, -0.05)
        assertEquals(-0.05, path.trajectoryCurvature(1))
        // the untouched row keeps its sentinel
        assertTrue(path.trajectoryCurvature(0).isNaN())
    }

    @Test
    fun `generic get and set round trip on every field at every index`() {
        val size = 5
        val path = TestPath(size)
        for (i in 0 until size) {
            for ((slot, field) in PointField.entries.withIndex()) {
                val v = 1000.0 * i + slot
                path.set(i, field, v)
                assertEquals(v, path.get(i, field), "field=$field i=$i")
            }
        }
    }

    @Test
    fun `named getter returns value written through generic set`() {
        val path = TestPath(size = 3)
        for ((slot, accessor) in POINT_FIELD_ACCESSORS.withIndex()) {
            val raw = 9000.0 + slot
            path.set(2, accessor.field, raw)
            val viaNamed = accessor.getter(path, 2)
            assertEquals(raw, viaNamed, "${accessor.field}: expected $raw via named getter")
        }
    }

    @Test
    fun `named setter is observed through generic get`() {
        val path = TestPath(size = 2)
        for ((slot, accessor) in POINT_FIELD_ACCESSORS.withIndex()) {
            val raw = 4000.0 + slot
            accessor.setter(path, 1, raw)
            val viaGeneric = path.get(1, accessor.field)
            assertEquals(raw, viaGeneric, "${accessor.field}: setter mismatch")
        }
    }

    @Test
    fun `POINT_FIELD_ACCESSORS contains every PointField exactly once, in declaration order`() {
        val expected = PointField.entries.toList()
        val actual = POINT_FIELD_ACCESSORS.map { it.field }
        assertEquals(expected, actual, "accessors list is out of sync with PointField.entries")
    }

    @Test
    fun `accessors do not collide - sentinel pattern`() {
        val path = TestPath(size = 1)
        for ((slot, accessor) in POINT_FIELD_ACCESSORS.withIndex()) {
            path.set(0, accessor.field, slot.toDouble())
        }
        for ((slot, accessor) in POINT_FIELD_ACCESSORS.withIndex()) {
            assertEquals(slot.toDouble(), accessor.getter(path, 0), "slot $slot leaked")
        }
    }

    @Test
    fun `independent rows do not bleed into one another`() {
        val path = TestPath(size = 3)
        path.setLatitude(0, 1.0)
        path.setLatitude(1, 2.0)
        path.setLatitude(2, 3.0)
        path.setCadence(0, 100.0)
        path.setCadence(1, 200.0)
        path.setCadence(2, 300.0)
        assertEquals(1.0, path.latitude(0))
        assertEquals(2.0, path.latitude(1))
        assertEquals(3.0, path.latitude(2))
        assertEquals(100.0, path.cadence(0))
        assertEquals(200.0, path.cadence(1))
        assertEquals(300.0, path.cadence(2))
    }
}
