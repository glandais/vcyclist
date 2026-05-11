package io.github.glandais.engine.physics

import io.github.glandais.engine.Course
import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.Cyclist
import io.github.glandais.engine.EngineConstants
import io.github.glandais.engine.path.Path
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GravPowerProviderTest {
    private val tol = 1e-3

    private fun cp(
        speed: Double,
        grade: Double,
        mass: Double = 80.0,
    ): Pair<CoursePhysics, Path> {
        val path = Path(1)
        path.setSpeed(0, speed)
        path.setGrade(0, grade)
        val course = Course(path = path, cyclist = Cyclist(massKg = mass))
        return CoursePhysics(course) to path
    }

    @Test
    fun flat_grade_yields_zero_power() {
        val (cp, path) = cp(speed = 10.0, grade = 0.0)
        assertEquals(0.0, GravPowerProvider.powerAt(cp, path, 0), 0.0)
    }

    @Test
    fun five_percent_climb_at_5ms_80kg_matches_reference() {
        // P = -80 × 9.8 × 5 × sin(atan(0.05)) ≈ -195.626 W
        val (cp, path) = cp(speed = 5.0, grade = 0.05, mass = 80.0)
        val p = GravPowerProvider.powerAt(cp, path, 0)
        val expected = -80.0 * EngineConstants.G * 5.0 * sin(atan(0.05))
        assertEquals(expected, p, tol)
        // Sanity : magnitude ~ 195.6 W.
        assertTrue(abs(p + 195.6) < 0.5, "expected ~ -195.6 W, got $p")
    }

    @Test
    fun ten_percent_climb_at_5ms_80kg_matches_reference() {
        // P = -80 × 9.8 × 5 × sin(atan(0.10)) ≈ -390.05 W
        val (cp, path) = cp(speed = 5.0, grade = 0.10, mass = 80.0)
        val p = GravPowerProvider.powerAt(cp, path, 0)
        val expected = -80.0 * EngineConstants.G * 5.0 * sin(atan(0.10))
        assertEquals(expected, p, tol)
        assertTrue(abs(p + 390.05) < 1.0, "expected ~ -390.05 W, got $p")
    }

    @Test
    fun descent_yields_positive_power() {
        // sin(atan(-0.05)) = -sin(atan(0.05)) → gravity assists.
        val (cp, path) = cp(speed = 5.0, grade = -0.05)
        val p = GravPowerProvider.powerAt(cp, path, 0)
        assertTrue(p > 0.0, "expected P > 0 on descent, got $p")
    }

    @Test
    fun ascent_descent_magnitudes_are_symmetric() {
        val (cpUp, pUp) = cp(speed = 5.0, grade = 0.07)
        val (cpDown, pDown) = cp(speed = 5.0, grade = -0.07)
        val up = GravPowerProvider.powerAt(cpUp, pUp, 0)
        val down = GravPowerProvider.powerAt(cpDown, pDown, 0)
        assertEquals(-up, down, tol)
    }

    @Test
    fun side_effect_path_pGravity_matches_returned_value() {
        val (cp, path) = cp(speed = 6.0, grade = 0.08)
        val returned = GravPowerProvider.powerAt(cp, path, 0)
        assertEquals(returned, path.pGravity(0), 0.0)
    }

    @Test
    fun sentinel_five_percent_climb_magnitude_around_195w() {
        // Spec sentinel : grade=0.05, v=5, m=80 → magnitude attendue ~195 W.
        val (cp, path) = cp(speed = 5.0, grade = 0.05, mass = 80.0)
        val p = GravPowerProvider.powerAt(cp, path, 0)
        assertTrue(p < 0.0, "expected resistive (negative)")
        assertTrue(abs(p + 195.0) < 1.0, "expected magnitude ≈ 195 W, got |p|=${abs(p)}")
    }
}
