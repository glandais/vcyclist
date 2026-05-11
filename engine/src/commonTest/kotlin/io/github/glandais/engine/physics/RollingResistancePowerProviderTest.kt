package io.github.glandais.engine.physics

import io.github.glandais.engine.Bike
import io.github.glandais.engine.Course
import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.Cyclist
import io.github.glandais.engine.EngineConstants
import io.github.glandais.engine.path.Path
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RollingResistancePowerProviderTest {
    private val tol = 1e-12
    private val tolTrig = 1e-3

    private fun cp(
        speed: Double,
        grade: Double,
        mass: Double = 80.0,
        crr: Double = 0.004,
    ): Pair<CoursePhysics, Path> {
        val path = Path(1)
        path.setSpeed(0, speed)
        path.setGrade(0, grade)
        val course =
            Course(
                path = path,
                cyclist = Cyclist(massKg = mass),
                bike = Bike(crr = crr),
            )
        return CoursePhysics(course) to path
    }

    @Test
    fun at_zero_speed_power_is_zero() {
        val (cp, path) = cp(speed = 0.0, grade = 0.0)
        assertEquals(0.0, RollingResistancePowerProvider.powerAt(cp, path, 0), 0.0)
    }

    @Test
    fun flat_road_matches_analytic_value() {
        // P = -1 × 80 × 9.8 × 10 × 0.004 = -31.36
        val (cp, path) = cp(speed = 10.0, grade = 0.0, mass = 80.0, crr = 0.004)
        val p = RollingResistancePowerProvider.powerAt(cp, path, 0)
        assertEquals(-31.36, p, tol)
    }

    @Test
    fun on_climb_power_magnitude_is_smaller_than_on_flat() {
        // cos(atan(0.1)) ≈ 0.99504 < 1 → |P_climb| < |P_flat|
        val (cpFlat, pFlat) = cp(speed = 10.0, grade = 0.0)
        val (cpClimb, pClimb) = cp(speed = 10.0, grade = 0.1)
        val flat = RollingResistancePowerProvider.powerAt(cpFlat, pFlat, 0)
        val climb = RollingResistancePowerProvider.powerAt(cpClimb, pClimb, 0)
        assertTrue(abs(climb) < abs(flat), "expected |P_climb|=${abs(climb)} < |P_flat|=${abs(flat)}")
    }

    @Test
    fun grade_symmetry_descent_matches_ascent_magnitude() {
        // cos(atan(grade)) is an even function of grade.
        val (cpUp, pUp) = cp(speed = 10.0, grade = 0.1)
        val (cpDown, pDown) = cp(speed = 10.0, grade = -0.1)
        val up = RollingResistancePowerProvider.powerAt(cpUp, pUp, 0)
        val down = RollingResistancePowerProvider.powerAt(cpDown, pDown, 0)
        assertEquals(up, down, tol)
    }

    @Test
    fun side_effect_path_pRollingResistance_matches_returned_value() {
        val (cp, path) = cp(speed = 8.0, grade = 0.05)
        val returned = RollingResistancePowerProvider.powerAt(cp, path, 0)
        assertEquals(returned, path.pRollingResistance(0), 0.0)
    }

    @Test
    fun custom_mass_and_crr_matches_analytic_value() {
        // P = -cos(atan(0)) × 85 × 9.8 × 5 × 0.005 = -1 × 85 × 9.8 × 5 × 0.005 = -20.825
        val (cp, path) = cp(speed = 5.0, grade = 0.0, mass = 85.0, crr = 0.005)
        val p = RollingResistancePowerProvider.powerAt(cp, path, 0)
        assertEquals(-20.825, p, tol)
    }

    @Test
    fun matches_compositional_formula_on_arbitrary_grade() {
        // Sentinel : v=12, grade=0.07, m=72, crr=0.0045 ; ensure full formula composes correctly.
        val (cp, path) = cp(speed = 12.0, grade = 0.07, mass = 72.0, crr = 0.0045)
        val p = RollingResistancePowerProvider.powerAt(cp, path, 0)
        val expected = -cos(atan(0.07)) * 72.0 * EngineConstants.G * 12.0 * 0.0045
        assertEquals(expected, p, tolTrig)
    }
}
