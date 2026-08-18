package io.github.glandais.engine

import io.github.glandais.elevation.MathConstants
import io.github.glandais.engine.path.Path
import io.github.glandais.engine.physics.CyclistPowerProvider
import io.github.glandais.engine.physics.VirtualizeService
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `elapsed` and `dt` carry **seconds**, at every moment of the pipeline.
 *
 * They did not always : `VirtualizeService` wrote milliseconds and `Path.computeDerivedData`
 * rewrote the same two slots in seconds, under fields `PointField` declared as `"ms"`. Every
 * reader compensated with its own `/ 1000.0`, and nothing could fail when one forgot — a
 * `Double` is a `Double`. `WPrimeBalanceComputer` forgot, and under-integrated a whole W′
 * balance by a factor of 1000 (ledger R16) until a measurement caught it.
 *
 * The check that closes it does not test the unit in the abstract : it pins both fields against
 * [io.github.glandais.engine.path.PointField.TIME], which means milliseconds everywhere and at
 * every moment. A wrong scale on either side breaks the ratio.
 */
class TemporalFieldUnitsTest {
    /**
     * ~50 m spacing at 48° N, 5 s apart — the raw-GPX cadence [EnhancerTest] uses — over an
     * undulating profile. The undulation is not decoration : a straight flat road survives
     * `PathSimplifier` as two points, and two points cannot exercise a centred window.
     */
    private fun samplePath(n: Int = 40): Path {
        val p = Path(n)
        for (i in 0 until n) {
            p.setLatitude(i, 48.0 * MathConstants.DEG_TO_RAD)
            p.setLongitude(i, (2.0 + i * 7e-4) * MathConstants.DEG_TO_RAD)
            p.setElevation(i, 100.0 + 30.0 * sin(i * 0.4))
            p.setTime(i, i * 5_000.0)
        }
        p.computeDerivedData()
        return p
    }

    // ---- 1. Mid-pipeline : observed from inside the simulation ------------------

    /**
     * `virtualizeTrack` ends on `computeDerivedData`, which rewrites both fields — so reading the
     * returned path says nothing about what the simulation itself wrote. The only place the
     * mid-flight values are visible is from inside a provider, which is exactly where the four
     * stateful providers read them.
     */
    @Test
    fun virtualize_writes_seconds_while_it_runs() {
        val n = 60
        val src = Path(n)
        for (i in 0 until n) {
            src.setDistance(i, i * 10.0)
            src.setLatitude(i, i * 1e-4)
            src.setLongitude(i, i * 2e-4)
            src.setElevation(i, 100.0 + i)
            src.setSpeedMax(i, 50.0)
        }

        var observations = 0
        val spy =
            CyclistPowerProvider { _, path, i ->
                // `time(0)` is 0 during the simulation (relative-time convention), so `elapsed`
                // and `time` differ by exactly the factor under test.
                assertClose(path.time(i) / 1000.0, path.elapsed(i), "elapsed($i) mid-simulation")
                if (i >= 1) {
                    // The simulation's window is the **backward** interval, not the centred one
                    // `computeDerivedData` will overwrite it with.
                    val backward = (path.time(i) - path.time(i - 1)) / 1000.0
                    assertClose(backward, path.dt(i), "dt($i) mid-simulation")
                }
                observations++
                200.0
            }

        VirtualizeService.virtualizeTrack(
            CoursePhysics(Course(src), cyclistPowerProvider = spy),
        )
        assertTrue(observations > 0, "the spy provider was never called")
    }

    // ---- 2. Post-pipeline : what the user actually exports ----------------------

    @Test
    fun enhance_writes_seconds_against_a_millisecond_time() =
        runTest {
            val out = Enhancer.enhanceCourseDefault(samplePath(), elevationProvider = null)
            assertTrue(out.size >= 3, "pipeline output too small : ${out.size}")
            assertAgreesWithTime(out, "after Enhancer")
        }

    /**
     * `elapsed(i) = (time(i) − time(0)) / 1000` and `dt(i) = (time(i+1) − time(i−1)) / 2000` —
     * the centred half-interval [Path.computeDerivedData] writes, clamped at both ends.
     *
     * The tolerance is relative : these are seconds of a ride that lasts thousands of them, so an
     * absolute epsilon would be either meaningless or unreachable. A factor-of-1000 error — the
     * one being guarded against — misses by five orders of magnitude, not by ULPs.
     */
    private fun assertAgreesWithTime(
        p: Path,
        moment: String,
    ) {
        val t0 = p.time(0)
        for (i in 0 until p.size) {
            val expectedElapsed = (p.time(i) - t0) / 1000.0
            assertClose(expectedElapsed, p.elapsed(i), "elapsed($i) $moment")

            val im1 = max(0, i - 1)
            val ip1 = min(p.size - 1, i + 1)
            val expectedDt = (p.time(ip1) - p.time(im1)) / 2000.0
            assertClose(expectedDt, p.dt(i), "dt($i) $moment")
        }
    }

    private fun assertClose(
        expected: Double,
        actual: Double,
        what: String,
    ) {
        val scale = max(1e-3, abs(expected))
        assertTrue(
            abs(actual - expected) / scale < 1e-6,
            "$what : expected $expected s, got $actual s (ratio ${actual / expected})",
        )
    }
}
