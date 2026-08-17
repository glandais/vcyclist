package io.github.glandais.engine.physics

import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.EngineConstants
import io.github.glandais.engine.path.Path

/**
 * Bridge `muscular → wheel` : reads the cyclist's muscular power from
 * `course.cyclistPowerProvider`, applies the bike's drivetrain efficiency, and returns the
 * resulting wheel power.
 *
 * ## Pedal-strike clearance (ledger R10)
 *
 * Past a lean angle of [io.github.glandais.engine.Bike.maxPedalingLeanAngleDeg] the inside pedal
 * would hit the road, so **no power is delivered at all** — the rider coasts through the corner
 * and re-accelerates on the way out, which is what produces the corner-exit power spikes the
 * literature reports and what a rider actually does.
 *
 * Lean comes from the state already on the path : a bike cornering at `v` on radius `R` leans at
 * `atan(v² / (g·R))`, so the test is `v² / (g·R) > tan(θ_max)` — no `atan` per point.
 *
 * Two guards worth knowing:
 * - `radius` is written by [MaxSpeedComputer]. If it is absent (zero, negative or not finite —
 *   which happens when the simulation is driven directly without computing max speeds), the point
 *   is treated as **straight** and power flows. Failing open matters : failing closed would zero
 *   the rider's power for a whole ride.
 * - At a cornering-limited point the simulation rides at exactly `speedMax`, i.e. exactly
 *   [io.github.glandais.engine.Cyclist.maxLeanAngleDeg] (35° by default). So with the 20° default
 *   this cuts power in **every** grip-limited corner, not in some of them. That is intended — no
 *   one pedals at the limit of grip — but it is a real behavioural change, not a rounding.
 *
 * Side-effects written :
 * - `pCyclistProvidedMuscular(i)` : raw cyclist power before drivetrain losses, **zero while the
 *   pedals are up**. The rider's *intent* stays readable in `pCyclistProvidedOptimalPower(i)`,
 *   which the provider writes either way — the difference between the two is the cut-off.
 * - `pCyclistProvidedWheel(i)` : after multiplication by `bike.efficiency`.
 *
 * One accounting nit: a stateful provider ([PowerProviderDurability]) accumulates its dose from
 * the value it returned, not from what survives this cut-off, so it slightly over-counts work in
 * corners. Corners are a small fraction of ride time and the alternative is threading the bike's
 * geometry into every provider.
 */
object MuscularPowerProvider : PowerProvider {
    override fun powerAt(
        course: CoursePhysics,
        path: Path,
        pointIndex: Int,
    ): Double {
        // The provider is always asked, even when the pedals are up : its answer is the rider's
        // *intent*, which stays visible in `pCyclistProvidedOptimalPower`, and a stateful provider
        // keeps seeing every point. Only what reaches the chain is zeroed.
        val intent = course.cyclistPowerProvider.powerAt(course, path, pointIndex)
        val muscular = if (pedalsClear(course, path, pointIndex)) intent else 0.0
        path.setPCyclistProvidedMuscular(pointIndex, muscular)

        val wheel = muscular * course.bike.efficiency
        path.setPCyclistProvidedWheel(pointIndex, wheel)
        return wheel
    }

    /** `false` when the bike is leaned over far enough that the inside pedal would strike. */
    internal fun pedalsClear(
        course: CoursePhysics,
        path: Path,
        pointIndex: Int,
    ): Boolean {
        val radius = path.radius(pointIndex)
        if (!radius.isFinite() || radius <= 0.0) return true

        val speed = path.speed(pointIndex)
        if (!speed.isFinite()) return true

        val tanLean = (speed * speed) / (EngineConstants.G * radius)
        return tanLean <= course.bike.tanMaxPedalingLeanAngle
    }
}
