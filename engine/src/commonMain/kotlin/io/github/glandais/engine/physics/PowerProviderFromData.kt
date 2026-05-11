package io.github.glandais.engine.physics

import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.path.Path

/**
 * Cyclist power provider that reads `path.pInputPower(i)` verbatim. Bypasses the
 * harmonics / optimal pipeline — use this for replaying recorded power data.
 */
object PowerProviderFromData : CyclistPowerProvider {
    override fun powerAt(
        course: CoursePhysics,
        path: Path,
        pointIndex: Int,
    ): Double = path.pInputPower(pointIndex)
}
