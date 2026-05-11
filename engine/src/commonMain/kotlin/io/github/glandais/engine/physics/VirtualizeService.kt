package io.github.glandais.engine.physics

import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.EngineConstants
import io.github.glandais.engine.path.Path
import io.github.glandais.engine.path.PointField

/**
 * Transforms a static GPS route into a physics-based virtual ride.
 *
 * Time-stepping loop : for each successive GPS waypoint, compute the time taken to travel
 * the segment given the current power balance and cyclist input. Enforces `speedMax`
 * (cornering + braking) from task 20. Output : a new [Path] with `speed`, `time`, `dx`,
 * `dt`, `virtSpeedCurrent` populated, plus the inverse cyclist-power computation in
 * `pComputedPower`.
 *
 * Reference : virtual-cyclist TS `VirtualizeService.ts`. Iteration cap : 100 000.
 *
 * Notes :
 * - The output [Path] has the same fixed size as the input. The simulation runs from
 *   `i = 1` to `i = n - 1` (inclusive) ; the last point is simulated like all the others
 *   so its `time` is consistent with the simulated `time(n-2)` instead of leaking the
 *   absolute source epoch (~1.7e12 ms in 2024) which would otherwise blow up
 *   downstream `PointPerSecond`.
 * - The trapezoidal identity `dx = (v_old + v_new) × dt / 2` gives `v_new = 2·dx/dt − v_old`.
 *   Valid when `dx, dt > 0`. In practice waypoints are distinct (`dx > 0`) and `getDt`
 *   returns a strictly positive `dt`, so no guard is needed.
 * - `path.computeDerivedData()` is called at the end to recompute `bearing/grade/dx/dt/speed`
 *   from `lat/lon/elevation/time`. This **overwrites** the `speed/dx/dt` slots we just
 *   wrote — kept for parity with the TS implementation. The aggregate speed profile remains
 *   consistent in the average since `time(i)` is preserved.
 */
object VirtualizeService {
    /** Iteration cap protecting against pathological inputs (cf. TS counterpart). */
    private const val MAX_ITERATIONS = 100_000

    /** Build a virtualized [Path] from `course.path`. */
    fun virtualizeTrack(course: CoursePhysics): Path {
        val input: Path = course.path
        val n = input.size

        if (n == 0) return Path(0)
        if (n == 1) {
            val singleton = Path(1)
            copyAllFields(input, 0, singleton, 0)
            return singleton
        }

        val mEq = PowerComputer.equivalentMass(course)

        // Output path mirrors the input size. The simulation writes every slot in [1, n-1] ;
        // index 0 is bootstrapped below with `time = 0` and `speed = MINIMAL_SPEED`. The last
        // point is simulated too, so its `time` is derived from the simulated `time(n-2)`
        // (not the source epoch).
        val out = Path(n)
        copyAllFields(input, 0, out, 0)

        var speed = EngineConstants.MINIMAL_SPEED
        val startTimeMs = 0.0
        var timeMs = startTimeMs
        out.setTime(0, timeMs)
        out.setElapsed(0, 0.0)
        out.setSpeed(0, speed)
        out.setVirtSpeedCurrent(0, speed)

        var i = 1
        var iter = 0
        while (i < n) {
            val dx = input.distance(i) - input.distance(i - 1)
            val pSum = PowerComputer.getNewPower(course, out, i - 1, withCyclist = true)
            var dt = PowerComputer.getDt(pSum, mEq, speed, dx)
            var speedNew = 2.0 * dx / dt - speed

            copyAllFields(input, i, out, i)

            val speedMax = input.speedMax(i)
            if (speedNew > speedMax) {
                speedNew = speedMax
                dt = 2.0 * dx / (speedNew + speed)
            }

            speed = speedNew
            timeMs += dt * 1000.0

            out.setTime(i, timeMs)
            out.setElapsed(i, timeMs - startTimeMs)
            out.setDx(i, dx)
            out.setDt(i, dt * 1000.0)
            out.setSpeed(i, speed)
            out.setVirtSpeedCurrent(i, speed)

            i++
            if (iter++ > MAX_ITERATIONS) break
        }

        // Inverse problem : back-calculate cyclist power from speed changes (all indices,
        // including the last point now that it is fully simulated).
        for (j in out.indices) {
            PowerComputer.computeCyclistPower(course, out, mEq, j)
        }

        out.computeDerivedData()
        return out
    }

    /** Copy every [PointField] slot from `src[i]` to `dst[j]`. */
    private fun copyAllFields(
        src: Path,
        i: Int,
        dst: Path,
        j: Int,
    ) {
        for (field in PointField.entries) {
            dst.set(j, field, src.get(i, field))
        }
    }
}
