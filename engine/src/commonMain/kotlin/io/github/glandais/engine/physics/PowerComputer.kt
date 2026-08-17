package io.github.glandais.engine.physics

import io.github.glandais.engine.Course
import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.EngineConstants
import io.github.glandais.engine.path.Path
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Stateless power-energy bridge. Sums [PowerProvider] outputs into a total power balance,
 * integrates it into a kinematic speed (`v_new² = v_old² + 2·dt·P / m_eq`), and provides
 * the inverse (`P` from a measured Δv).
 *
 * Singleton-style `object` since there's no state. All methods take an explicit [Path].
 */
object PowerComputer {
    /**
     * Sum of resistive powers (always 4 providers) ± cyclist muscular power.
     *
     * Side-effects : every sub-provider mutates the path at [pointIndex] with its own
     * intermediate value (cf. tasks 17/18).
     */
    fun getNewPower(
        course: CoursePhysics,
        path: Path,
        pointIndex: Int,
        withCyclist: Boolean,
    ): Double {
        var pSum = 0.0
        pSum += WheelBearingsPowerProvider.powerAt(course, path, pointIndex)
        pSum += RollingResistancePowerProvider.powerAt(course, path, pointIndex)
        pSum += AeroPowerProvider.powerAt(course, path, pointIndex)
        pSum += GravPowerProvider.powerAt(course, path, pointIndex)
        if (withCyclist) {
            pSum += MuscularPowerProvider.powerAt(course, path, pointIndex)
        }
        return pSum
    }

    /**
     * Energy-conservation integrator. Computes the distance travelled during [dt] s given
     * the current power balance [pSum] and current [currentSpeed].
     *
     * `v_new = max(√(v_old² + 2·dt·P / m_eq), MINIMAL_SPEED)`, `Δx = (v_old + v_new)·dt/2`.
     */
    fun getDx(
        pSum: Double,
        equivalentMass: Double,
        currentSpeed: Double,
        dt: Double,
    ): Double {
        val newSpeed =
            max(
                sqrt((dt * pSum) / (0.5 * equivalentMass) + currentSpeed * currentSpeed),
                EngineConstants.MINIMAL_SPEED,
            )
        return (currentSpeed + newSpeed) * dt / 2.0
    }

    /** `P = 0.5 × m_eq × (s2² − s1²) / dt`. Inverse of [getDx]. */
    fun getTotPower(
        equivalentMass: Double,
        s1: Double,
        s2: Double,
        dt: Double,
    ): Double = (0.5 * equivalentMass * (s2 * s2 - s1 * s1)) / dt

    /**
     * Find the time step that produces [dx] meters given the current power balance.
     *
     * Two-step search :
     * 1. Linear walk : start `dt = 0.1 s`, increment by `0.1` until `getDx(dt) > dx`.
     * 2. Binary search between `[dt − 0.1, dt]` until `dt2 − dt1 < dx / 10_000_000`.
     *
     * Used by `VirtualizeService` (task 21) to align computed waypoints with GPS source.
     */
    fun getDt(
        pSum: Double,
        equivalentMass: Double,
        currentSpeed: Double,
        dx: Double,
    ): Double {
        var dt = 0.1
        while (getDx(pSum, equivalentMass, currentSpeed, dt) <= dx) {
            dt += 0.1
        }
        return getDtInner(pSum, equivalentMass, currentSpeed, dx, dt - 0.1, dt)
    }

    /**
     * Floor on the binary-search tolerance. `dx / 10_000_000` is zero when `dx` is, and a
     * tolerance of zero makes `dt2 - dt1 >= tol` true even after the two doubles converge — an
     * infinite loop. Callers should not pass `dx == 0` (see [VirtualizeService]'s zero-length
     * segment handling), but a solver that hangs on bad input is not an acceptable failure mode.
     */
    private const val MIN_DT_TOLERANCE = 1e-12

    private fun getDtInner(
        pSum: Double,
        equivalentMass: Double,
        currentSpeed: Double,
        dx: Double,
        dt1Init: Double,
        dt2Init: Double,
    ): Double {
        var dt1 = dt1Init
        var dt2 = dt2Init
        val tol = max(dx / 10_000_000.0, MIN_DT_TOLERANCE)
        while (dt2 - dt1 >= tol) {
            val dtMid = (dt1 + dt2) / 2.0
            val dxMid = getDx(pSum, equivalentMass, currentSpeed, dtMid)
            if (dxMid < dx) dt1 = dtMid else dt2 = dtMid
        }
        return (dt1 + dt2) / 2.0
    }

    /**
     * Inverse problem : compute the cyclist power that explains the measured Δv between
     * point `i-1` and `i`. Writes `pComputedTotalPower`, `pComputedWheelPower`,
     * `pComputedPower` and `pBrake`.
     *
     * ## `pBrake`
     *
     * A negative `pComputedWheelPower` means the trace lost more kinetic energy than the
     * resistive forces can account for — the only way that happens is that
     * [VirtualizeService][io.github.glandais.engine.physics.VirtualizeService] clipped the speed
     * to `speedMax`, i.e. **the rider braked**. That deficit used to vanish into the
     * `max(0.0, powerWheel)` below, which is why a simulated ride's power trace contained no
     * braking at all while showing the corner-exit accelerations that follow it.
     *
     * Note what this does **not** count: a speed cap the rider merely sits at (a 3 m/s limit on
     * the flat, say) needs no braking at all — resistance alone explains the steady speed, and the
     * inverse problem attributes the segment to a rider soft-pedalling. Only a deceleration the
     * resistive forces cannot account for is recorded, which is the conservative reading and the
     * one that matches the corner-entry spikes reported in the literature.
     *
     * It is recorded as-is, at the wheel : braking acts on the rims/rotors, so it is **not**
     * divided by the drivetrain efficiency the way cyclist power is. Sign follows the
     * resistive-power convention — negative removes energy.
     *
     * For `i == 0`, sets `pComputedPower(0) = 0.0`, `pBrake(0) = 0.0` and returns.
     */
    fun computeCyclistPower(
        course: CoursePhysics,
        path: Path,
        equivalentMass: Double,
        i: Int,
    ) {
        if (i == 0) {
            path.setPComputedPower(i, 0.0)
            path.setPBrake(i, 0.0)
            return
        }
        val dtSeconds = path.dt(i) / 1000.0
        if (dtSeconds <= 0.0) {
            // Zero-length segment (duplicate GPS point). No time passed, so no power was
            // delivered — dividing by it would write ±Infinity into four fields.
            path.setPComputedTotalPower(i, 0.0)
            path.setPComputedWheelPower(i, 0.0)
            path.setPBrake(i, 0.0)
            path.setPComputedPower(i, 0.0)
            return
        }
        val resistive = getNewPower(course, path, i - 1, withCyclist = false)
        val s1 = path.speed(i - 1)
        val s2 = path.speed(i)
        val totPower = getTotPower(equivalentMass, s1, s2, dtSeconds)
        path.setPComputedTotalPower(i, totPower)

        val powerWheel = totPower - resistive
        path.setPComputedWheelPower(i, powerWheel)
        path.setPBrake(i, min(0.0, powerWheel))

        val computed = max(0.0, powerWheel) / course.bike.efficiency
        path.setPComputedPower(i, computed)
    }

    /** `m_eq = m_kg + (I_front + I_rear) / r²`. Accounts for rotational inertia of wheels. */
    fun equivalentMass(course: Course): Double {
        val m = course.cyclist.massKg
        val totalInertia = course.bike.inertiaFront + course.bike.inertiaRear
        return m + totalInertia / (course.bike.wheelRadiusM * course.bike.wheelRadiusM)
    }

    /** Convenience overload — accepts [CoursePhysics] for symmetry. */
    fun equivalentMass(course: CoursePhysics): Double = equivalentMass(course.course)
}
