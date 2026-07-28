package io.github.glandais.engine.path

import io.github.glandais.elevation.MathConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * Task g33: what [Path.withoutTime] keeps and what it drops.
 *
 * Its KDoc used to claim that speed survives. It does not — `computeDerivedData` reads speed off
 * the clock, and the clock is exactly what this method removes. These cases pin the real contract
 * so the documentation and the behaviour cannot drift apart again silently.
 */
class PathWithoutTimeTest {
    /** Four points, 10 s and ~110 m apart, with sensor values and a simulated power. */
    private fun timedPath(): Path {
        val p = Path(4)
        for (i in 0 until 4) {
            p.setLatitude(i, (45.0 + i * 0.001) * MathConstants.DEG_TO_RAD)
            p.setLongitude(i, 6.0 * MathConstants.DEG_TO_RAD)
            p.setElevation(i, 100.0 + i)
            p.setTime(i, i * 10_000.0)
            p.setPComputedPower(i, 200.0 + i)
            p.setPInputPower(i, 180.0 + i)
            p.setHeartRate(i, 140.0 + i)
            p.setCadence(i, 85.0)
            p.setTemperature(i, 17.5)
        }
        p.computeDerivedData()
        return p
    }

    @Test
    fun `case 01 — every timestamp is cleared`() {
        val stripped = timedPath().withoutTime()

        for (i in 0 until stripped.size) {
            assertEquals(0.0, stripped.time(i), "time($i)")
        }
    }

    @Test
    fun `case 02 — the whole time-derived family comes back as zero`() {
        val source = timedPath()
        assertTrue(source.speed(1) > 0.0, "the fixture must have a speed to lose")

        val stripped = source.withoutTime()

        for (i in 0 until stripped.size) {
            assertEquals(0.0, stripped.speed(i), "speed($i) — derived from the clock that just went")
            assertEquals(0.0, stripped.dt(i), "dt($i)")
            assertEquals(0.0, stripped.elapsed(i), "elapsed($i)")
        }
        assertEquals(0.0, stripped.durationMs, "durationMs")
    }

    @Test
    fun `case 03 — geometry is preserved exactly`() {
        val source = timedPath()

        val stripped = source.withoutTime()

        assertEquals(source.totalDistance, stripped.totalDistance, 1e-9)
        assertEquals(source.elevationGain, stripped.elevationGain, 1e-9)
        assertEquals(source.elevationLoss, stripped.elevationLoss, 1e-9)
        for (i in 0 until source.size) {
            assertEquals(source.latitude(i), stripped.latitude(i), 1e-12, "latitude($i)")
            assertEquals(source.longitude(i), stripped.longitude(i), 1e-12, "longitude($i)")
            assertEquals(source.elevation(i), stripped.elevation(i), 1e-12, "elevation($i)")
            assertEquals(source.distance(i), stripped.distance(i), 1e-9, "distance($i)")
            assertEquals(source.grade(i), stripped.grade(i), 1e-12, "grade($i)")
            assertEquals(source.bearing(i), stripped.bearing(i), 1e-12, "bearing($i)")
        }
    }

    @Test
    fun `case 04 — fields no derivation touches are carried through`() {
        val source = timedPath()

        val stripped = source.withoutTime()

        for (i in 0 until source.size) {
            assertEquals(source.pComputedPower(i), stripped.pComputedPower(i), "pComputedPower($i)")
            assertEquals(source.pInputPower(i), stripped.pInputPower(i), "pInputPower($i)")
            assertEquals(source.heartRate(i), stripped.heartRate(i), "heartRate($i)")
            assertEquals(source.cadence(i), stripped.cadence(i), "cadence($i)")
            assertEquals(source.temperature(i), stripped.temperature(i), "temperature($i)")
        }
    }

    @Test
    fun `case 05 — the source path is untouched`() {
        val source = timedPath()
        val speedBefore = source.speed(1)
        val durationBefore = source.durationMs

        val stripped = source.withoutTime()

        assertNotSame(source, stripped)
        assertEquals(30_000.0, source.time(3), "the source still has its clock")
        assertEquals(speedBefore, source.speed(1))
        assertEquals(durationBefore, source.durationMs)
    }

    @Test
    fun `case 06 — a non-monotonic clock becomes monotonic, which is the point`() {
        val source = timedPath()
        source.setTime(2, 1.0) // a head unit resyncing mid-ride steps backwards
        source.computeDerivedData()
        assertTrue(source.time(2) < source.time(1), "the fixture must be non-monotonic")

        val stripped = source.withoutTime()

        for (i in 1 until stripped.size) {
            assertTrue(stripped.time(i) >= stripped.time(i - 1), "time($i) must not go backwards")
        }
    }

    @Test
    fun `case 07 — an empty path survives it`() {
        assertEquals(0, Path(0).withoutTime().size)
    }
}
