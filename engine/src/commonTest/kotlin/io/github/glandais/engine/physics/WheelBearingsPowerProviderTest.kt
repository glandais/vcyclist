package io.github.glandais.engine.physics

import io.github.glandais.engine.Course
import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WheelBearingsPowerProviderTest {
    private val tol = 1e-12

    private fun cp(speed: Double): Pair<CoursePhysics, Path> {
        val path = Path(1)
        path.setSpeed(0, speed)
        return CoursePhysics(Course(path)) to path
    }

    @Test
    fun at_zero_speed_power_is_zero() {
        val (cp, path) = cp(0.0)
        assertEquals(0.0, WheelBearingsPowerProvider.powerAt(cp, path, 0), 0.0)
    }

    @Test
    fun at_10ms_matches_analytic_value() {
        // P = -10 × (91 + 87) / 1000 = -1.78
        val (cp, path) = cp(10.0)
        val p = WheelBearingsPowerProvider.powerAt(cp, path, 0)
        assertEquals(-1.78, p, tol)
    }

    @Test
    fun at_15ms_matches_analytic_value() {
        // P = -15 × (91 + 130.5) / 1000 = -3.3225
        val (cp, path) = cp(15.0)
        val p = WheelBearingsPowerProvider.powerAt(cp, path, 0)
        assertEquals(-3.3225, p, tol)
    }

    @Test
    fun power_is_non_positive_across_speed_grid() {
        for (vInt in 0..30) {
            val (cp, path) = cp(vInt.toDouble())
            val p = WheelBearingsPowerProvider.powerAt(cp, path, 0)
            assertTrue(p <= 0.0, "expected P ≤ 0 at v=$vInt, got $p")
        }
    }

    @Test
    fun side_effect_path_pWheelBearings_matches_returned_value() {
        val (cp, path) = cp(12.5)
        val returned = WheelBearingsPowerProvider.powerAt(cp, path, 0)
        assertEquals(returned, path.pWheelBearings(0), 0.0)
    }

    @Test
    fun sam_constructed_provider_yields_same_value() {
        // Validate that PowerProvider can be used as a fun interface (SAM lambda).
        val asLambda: PowerProvider =
            PowerProvider { c, p, i -> WheelBearingsPowerProvider.powerAt(c, p, i) }
        val (cp, path) = cp(7.0)
        assertEquals(
            WheelBearingsPowerProvider.powerAt(cp, path, 0),
            asLambda.powerAt(cp, path, 0),
            0.0,
        )
    }
}
