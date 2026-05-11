package io.github.glandais.engine.physics

import io.github.glandais.engine.Course
import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.EngineConstants
import io.github.glandais.engine.path.Path
import kotlin.math.max
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
        val tol = dx / 10_000_000.0
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
     * `pComputedPower`.
     *
     * For `i == 0`, sets `pComputedPower(0) = 0.0` and returns.
     */
    fun computeCyclistPower(
        course: CoursePhysics,
        path: Path,
        equivalentMass: Double,
        i: Int,
    ) {
        if (i == 0) {
            path.setPComputedPower(i, 0.0)
            return
        }
        val resistive = getNewPower(course, path, i - 1, withCyclist = false)
        val s1 = path.speed(i - 1)
        val s2 = path.speed(i)
        val dtSeconds = path.dt(i) / 1000.0
        val totPower = getTotPower(equivalentMass, s1, s2, dtSeconds)
        path.setPComputedTotalPower(i, totPower)

        val powerWheel = totPower - resistive
        path.setPComputedWheelPower(i, powerWheel)

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
