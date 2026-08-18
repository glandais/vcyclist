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
 * Iteration cap : 100 000.
 *
 * The `speedMax` clip below is the simulation's **braking model** : the excess kinetic energy is
 * simply dropped. It is not lost from the *output* though — `PowerComputer.computeCyclistPower`
 * recovers it as `pBrake`, since a negative wheel power is exactly the energy this clip removed.
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
 *   wrote — deliberately, since `time(i)` is preserved and the aggregate speed profile stays
 *   consistent in the average.
 */
object VirtualizeService {
    /** Iteration cap protecting against pathological inputs. */
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

            if (dx <= 0.0) {
                // Duplicate GPS point : no distance covered, so no time passes and the state
                // carries over unchanged. `PointPerDistance(1, 2)` removes these before the
                // simulation in the normal pipeline, but `virtualizeTrack` is public and a raw
                // GPX can hold repeated fixes — and `getDt` cannot answer "how long to cover
                // 0 m" (its search tolerance is `dx / 10_000_000`, i.e. 0, and never converges).
                copyAllFields(input, i, out, i)
                out.setTime(i, timeMs)
                out.setElapsed(i, (timeMs - startTimeMs) / 1000.0)
                out.setDx(i, 0.0)
                out.setDt(i, 0.0)
                out.setSpeed(i, speed)
                out.setVirtSpeedCurrent(i, speed)
                i++
                continue
            }

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
            out.setElapsed(i, (timeMs - startTimeMs) / 1000.0)
            out.setDx(i, dx)
            out.setDt(i, dt)
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
