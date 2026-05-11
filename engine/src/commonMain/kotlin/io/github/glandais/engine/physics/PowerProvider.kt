package io.github.glandais.engine.physics

import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.path.Path

/**
 * A source of power (positive or negative) at a point on the [Path].
 *
 * Conventions :
 * - **Resistive forces** (drag, rolling, gravity climbing, bearings) return **negative** values.
 * - **Assistive forces** (gravity descending) return **positive** values.
 * - **Cyclist input power** (task 18) returns positive values.
 *
 * Implementations may write debug/intermediate values into the [Path] at `pointIndex` as a
 * side-effect (e.g. `path.setPAero(i, p)`). This is intentional — the engine consumes these
 * stored values during virtualization (task 21).
 *
 * `fun interface` enables SAM construction : `PowerProvider { _, _, _ -> 0.0 }` for tests/mocks.
 */
fun interface PowerProvider {
    fun powerAt(
        course: CoursePhysics,
        path: Path,
        pointIndex: Int,
    ): Double
}
