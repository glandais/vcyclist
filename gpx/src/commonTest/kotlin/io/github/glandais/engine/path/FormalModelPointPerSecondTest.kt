package io.github.glandais.engine.path

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Counterexamples found by model-checking [PointPerSecond.computeOnePointPerSecond] (TLC, Lean and
 * an IEEE-754 model of `buildPlan`).
 *
 * Contracts under test (KDoc): the resampled path covers `[floor(start), ceil(end))` seconds; a
 * source starting exactly on a second boundary keeps a sample at that boundary; a non-empty source
 * never resamples to an empty path. `msInSec` is computed with `time.toLong()`, so a time in
 * `[k*1000, k*1000 + 1)` that is *not* exactly on the boundary is treated as aligned.
 * `VirtualizeService` writes fractional-ms times (`timeMs += dt * 1000.0`), so such inputs reach
 * the resampler in the normal pipeline.
 */
class FormalModelPointPerSecondTest {
    private fun pathWithTimes(times: DoubleArray): Path {
        val p = Path(times.size)
        for (i in times.indices) {
            p.setLatitude(i, 0.0)
            p.setLongitude(i, i * 1e-5)
            p.setElevation(i, 100.0 + i * 10.0)
            p.setTime(i, times[i])
        }
        return p
    }

    private fun flatPathWithTimes(times: LongArray): Path {
        val p = Path(times.size)
        for (i in times.indices) {
            p.setLatitude(i, 0.0)
            p.setLongitude(i, i * 1e-5)
            p.setElevation(i, 100.0)
            p.setTime(i, times[i].toDouble())
        }
        return p
    }

    private fun pathWith(
        times: DoubleArray,
        elevations: DoubleArray,
    ): Path {
        val p = Path(times.size)
        for (i in times.indices) {
            p.setLatitude(i, 0.0)
            p.setLongitude(i, i * 1e-5)
            p.setElevation(i, elevations[i])
            p.setTime(i, times[i])
        }
        return p
    }

    // Control: an aligned start whose next point is in a later second keeps epoch 0
    // (interpolation with coef = 0).
    @Test
    fun startOnBoundaryIsKeptWhenSecondPointIsInLaterSecond() {
        val out = PointPerSecond.computeOnePointPerSecond(pathWithTimes(doubleArrayOf(0.0, 1500.0)))
        assertEquals(0.0, out.time(0), 1e-12)
        assertEquals(100.0, out.elevation(0), 1e-12)
    }

    // TLC counterexample: times = <<0, 500>> → plan = {1 ↦ Copy(last)}.
    // Same aligned start, but the second point lies in the same second: epoch 0 is lost and the
    // output begins at 1000 ms. VirtualizeService pins time(0) = 0 and its first step can be < 1 s.
    @Test
    fun startOnBoundaryIsKeptWhenSecondPointIsInSameSecond() {
        val source = pathWithTimes(doubleArrayOf(0.0, 500.0, 1500.0))
        val out = PointPerSecond.computeOnePointPerSecond(source)
        assertTrue(out.size > 0)
        assertEquals(
            0.0,
            out.time(0),
            1e-12,
            "first output sample must sit at floor(start) = 0 ms, got ${out.time(0)}",
        )
        assertEquals(100.0, out.elevation(0), 1e-12, "first output sample must be the source start")
    }

    @Test
    fun singlePointOnBoundaryIsNotDropped() {
        // One point mid-second (1234 ms) yields 2 samples; one point on a boundary must yield
        // at least one, not zero.
        val out = PointPerSecond.computeOnePointPerSecond(flatPathWithTimes(longArrayOf(0L)))
        assertEquals(1, out.size, "1-point source at t=0 resampled to ${out.size} points")
        assertEquals(0.0, out.time(0))
    }

    @Test
    fun duplicateFixesOnBoundaryAreNotDropped() {
        val out = PointPerSecond.computeOnePointPerSecond(flatPathWithTimes(longArrayOf(5000L, 5000L)))
        assertEquals(1, out.size, "2-point source at t=5000 resampled to ${out.size} points")
        assertEquals(5000.0, out.time(0))
    }

    /** The 1000 ms sample lies between source points 0 (0 ms) and 1 (1000.4 ms): no extrapolation. */
    @Test
    fun interpolationNeverExtrapolatesOnSubMillisecondOffset() {
        val source = pathWith(doubleArrayOf(0.0, 1000.4, 2700.0), doubleArrayOf(100.0, 110.0, 1e6))
        val out = PointPerSecond.computeOnePointPerSecond(source)
        var idx = -1
        for (i in 0 until out.size) if (abs(out.time(i) - 1000.0) < 1e-9) idx = i
        assertTrue(idx >= 0, "no sample at 1000 ms")
        val e = out.elevation(idx)
        assertTrue(e in 100.0..110.0, "elevation at 1000 ms should lie in [100, 110], was $e")
    }

    /** KDoc: a last point mid-second gets a copy at the next boundary (cf. 1234 ms -> 2000 ms). */
    @Test
    fun lastPointWithSubMillisecondOffsetIsCovered() {
        val source = pathWith(doubleArrayOf(0.0, 2000.9), doubleArrayOf(100.0, 110.0))
        val out = PointPerSecond.computeOnePointPerSecond(source)
        val lastTime = out.time(out.size - 1)
        assertTrue(lastTime >= 2000.9, "output ends at $lastTime ms, before the source end 2000.9 ms")
        assertEquals(110.0, out.elevation(out.size - 1), 1e-9)
    }

    /** A single mid-second point yields copies (cf. singlePointMidSecondYieldsTwoCopies), never nothing. */
    @Test
    fun singlePointWithSubMillisecondOffsetIsNotDropped() {
        val source = pathWith(doubleArrayOf(2000.4), doubleArrayOf(100.0))
        val out = PointPerSecond.computeOnePointPerSecond(source)
        assertTrue(out.size > 0, "a non-empty source resampled to an empty path")
    }
}
