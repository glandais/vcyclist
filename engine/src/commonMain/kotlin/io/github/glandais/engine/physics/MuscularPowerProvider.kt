package io.github.glandais.engine.physics

import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.path.Path

/**
 * Bridge `muscular → wheel` : reads the cyclist's muscular power from
 * `course.cyclistPowerProvider`, applies the bike's drivetrain efficiency, and returns the
 * resulting wheel power.
 *
 * Side-effects written :
 * - `pCyclistProvidedMuscular(i)` : raw cyclist power before drivetrain losses.
 * - `pCyclistProvidedWheel(i)` : after multiplication by `bike.efficiency`.
 */
object MuscularPowerProvider : PowerProvider {
    override fun powerAt(
        course: CoursePhysics,
        path: Path,
        pointIndex: Int,
    ): Double {
        val muscular = course.cyclistPowerProvider.powerAt(course, path, pointIndex)
        path.setPCyclistProvidedMuscular(pointIndex, muscular)

        val wheel = muscular * course.bike.efficiency
        path.setPCyclistProvidedWheel(pointIndex, wheel)
        return wheel
    }
}
