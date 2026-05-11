package io.github.glandais.engine.physics

import io.github.glandais.engine.Course
import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.path.Path
import io.github.glandais.engine.path.PointPerSecond
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression tests for the task 29 fix : `VirtualizeService` used to copy the last input
 * point verbatim, leaving `time(n - 1)` at the source epoch (~1.7e12 ms in 2024) instead of
 * a value consistent with the simulated `time(n - 2)`. That caused `PointPerSecond` to try
 * to allocate ~1.7 billion second slots → OOM. After the fix the last point is fully
 * simulated and its `time` is a small monotone extension of `time(n - 2)`.
 */
class VirtualizeServiceTimestampTest {
    @Test
    fun last_point_time_is_not_an_epoch_leak_from_input() {
        // Source path with realistic epoch timestamps (2024-ish).
        val n = 5
        val path = Path(n)
        val epoch2024 = 1_700_000_000_000.0
        for (i in 0 until n) {
            path.setLatitude(i, 0.0)
            path.setLongitude(i, i * 1e-5)
            path.setElevation(i, 100.0)
            path.setTime(i, epoch2024 + i * 1_000.0) // 1 s apart in absolute epoch
            path.setDistance(i, i * 1.0) // 1 m apart
            path.setSpeedMax(i, 50.0)
        }
        val course = CoursePhysics(Course(path))
        val out = VirtualizeService.virtualizeTrack(course)

        // The simulated path must NOT carry the 1.7e12 epoch in time(n-1).
        val tEnd = out.time(out.size - 1)
        assertTrue(
            tEnd < 1_000_000.0,
            "time(n-1)=$tEnd leaked the input epoch instead of being simulated",
        )
        // It should be strictly greater than time(n-2) (monotone). The delta should also be
        // tiny (a fraction of a second per 1 m segment with a 280 W cyclist) — definitely
        // not the multi-billion-ms gap that a raw `copyAllFields(input, n-1, ...)` would
        // produce.
        val tPrev = out.time(out.size - 2)
        assertTrue(tEnd > tPrev, "time(n-1)=$tEnd must be > time(n-2)=$tPrev")
        val deltaMs = tEnd - tPrev
        assertTrue(
            deltaMs < 60_000.0,
            "time(n-1) - time(n-2) = $deltaMs ms is unreasonably large (epoch leak?)",
        )
    }

    @Test
    fun point_per_second_can_run_on_output_without_oom() {
        // Same setup as above. After the fix, PointPerSecond should not allocate ~10^9 points.
        val n = 5
        val path = Path(n)
        val epoch2024 = 1_700_000_000_000.0
        for (i in 0 until n) {
            path.setLatitude(i, 0.0)
            path.setLongitude(i, i * 1e-5)
            path.setElevation(i, 100.0)
            path.setTime(i, epoch2024 + i * 1_000.0)
            path.setDistance(i, i * 1.0)
            path.setSpeedMax(i, 50.0)
        }
        val virtual = VirtualizeService.virtualizeTrack(CoursePhysics(Course(path)))
        // Should produce at most ~30 epoch seconds, not 10^9.
        val resampled = PointPerSecond.computeOnePointPerSecond(virtual)
        assertTrue(
            resampled.size < 100,
            "PointPerSecond produced ${resampled.size} points (expected < 100)",
        )
    }
}
