package io.github.glandais.engine.physics

import io.github.glandais.elevation.MathConstants
import io.github.glandais.engine.Course
import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Repeated GPS fixes — the same coordinates twice in a row — used to hang the simulation.
 *
 * `PowerComputer.getDt` answers "how long to cover `dx` metres" by binary search with a
 * tolerance of `dx / 10_000_000`; at `dx == 0` that tolerance is `0`, `dt2 - dt1 >= 0` stays
 * true after the bounds converge, and the loop never exits. The pipeline never hit it because
 * `PointPerDistance(1, 2)` drops sub-metre points first, but `VirtualizeService.virtualizeTrack`
 * is public API and a raw GPX can hold repeated fixes.
 *
 * Found while adding `pBrake` (ledger R12) — a hairpin fixture happened to close on itself.
 */
class VirtualizeZeroLengthTest {
    private fun pathWithDuplicateAt(dupIndex: Int): Path {
        val n = 12
        val p = Path(n)
        var lon = 3.0
        for (i in 0 until n) {
            p.setLatitude(i, 45.0 * MathConstants.DEG_TO_RAD)
            p.setLongitude(i, lon * MathConstants.DEG_TO_RAD)
            p.setElevation(i, 100.0)
            p.setTime(i, i * 1000.0)
            // Unconstrained: `speedMax` defaults to 0.0 on a fresh Path, which would cap the
            // simulation at a standstill. The pipeline always runs MaxSpeedComputer first.
            p.setSpeedMax(i, 100.0)
            // The duplicated point repeats the previous longitude, so the segment is 0 m.
            if (i != dupIndex) lon += 1.27e-4
        }
        p.computeDerivedData()
        return p
    }

    private fun course(path: Path): CoursePhysics =
        CoursePhysics(
            course = Course(path = path),
            rhoProvider = RhoProviderDefault,
            aeroProvider = AeroProviderConstant,
            windProvider = WindProviderNone,
            cyclistPowerProvider = PowerProviderConstant(280.0),
        )

    @Test
    fun `a duplicated point terminates and carries the state forward`() {
        val out = VirtualizeService.virtualizeTrack(course(pathWithDuplicateAt(5)))

        assertEquals(12, out.size)
        // No time passes across the zero-length segment…
        assertEquals(out.time(5), out.time(6), 1e-12)
        // …and it produces no power of any kind rather than ±Infinity.
        assertEquals(0.0, out.pComputedPower(6), 0.0)
        assertEquals(0.0, out.pComputedTotalPower(6), 0.0)
        assertEquals(0.0, out.pBrake(6), 0.0)

        for (i in out.indices) {
            assertTrue(out.time(i).isFinite(), "non-finite time at $i")
            assertTrue(out.pComputedPower(i).isFinite(), "non-finite power at $i")
            assertTrue(out.pBrake(i).isFinite(), "non-finite brake power at $i")
        }
        for (i in 1 until out.size) {
            assertTrue(out.time(i) >= out.time(i - 1), "time went backwards at $i")
        }
        // The rest of the trace still runs.
        assertTrue(out.time(11) > out.time(0), "simulation made no progress")
    }

    @Test
    fun `the search tolerance has a floor so it cannot spin on dx = 0`() {
        // Directly: without the floor this call does not return.
        val dt = PowerComputer.getDt(pSum = -50.0, equivalentMass = 80.0, currentSpeed = 5.0, dx = 0.0)
        assertTrue(dt.isFinite() && dt >= 0.0, "getDt(dx = 0) must terminate with a finite value")
    }
}
