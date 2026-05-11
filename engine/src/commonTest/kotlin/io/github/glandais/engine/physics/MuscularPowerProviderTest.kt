package io.github.glandais.engine.physics

import io.github.glandais.engine.Bike
import io.github.glandais.engine.Course
import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class MuscularPowerProviderTest {
    private val tol = 1e-12

    private fun buildCp(
        muscularW: Double,
        efficiency: Double,
    ): Pair<CoursePhysics, Path> {
        val path = Path(1)
        val course = Course(path = path, bike = Bike(efficiency = efficiency))
        val cyclistProvider = PowerProviderConstant(muscularW, useHarmonics = false)
        val cp =
            CoursePhysics(
                course = course,
                cyclistPowerProvider = cyclistProvider,
            )
        return cp to path
    }

    @Test
    fun wheel_power_equals_muscular_times_efficiency_default() {
        // 200 W × 0.976 = 195.2 W
        val (cp, path) = buildCp(muscularW = 200.0, efficiency = 0.976)
        val wheel = MuscularPowerProvider.powerAt(cp, path, 0)
        assertEquals(195.2, wheel, tol)
    }

    @Test
    fun side_effect_pCyclistProvidedMuscular_matches_input() {
        val (cp, path) = buildCp(muscularW = 200.0, efficiency = 0.976)
        MuscularPowerProvider.powerAt(cp, path, 0)
        assertEquals(200.0, path.pCyclistProvidedMuscular(0), tol)
    }

    @Test
    fun side_effect_pCyclistProvidedWheel_matches_return() {
        val (cp, path) = buildCp(muscularW = 200.0, efficiency = 0.976)
        val wheel = MuscularPowerProvider.powerAt(cp, path, 0)
        assertEquals(wheel, path.pCyclistProvidedWheel(0), 0.0)
        assertEquals(195.2, path.pCyclistProvidedWheel(0), tol)
    }

    @Test
    fun lossless_drivetrain_yields_identity() {
        val (cp, path) = buildCp(muscularW = 300.0, efficiency = 1.0)
        val wheel = MuscularPowerProvider.powerAt(cp, path, 0)
        assertEquals(300.0, wheel, tol)
        assertEquals(300.0, path.pCyclistProvidedMuscular(0), 0.0)
        assertEquals(300.0, path.pCyclistProvidedWheel(0), 0.0)
    }

    @Test
    fun cyclist_provider_pipeline_side_effects_also_written() {
        // MuscularPowerProvider delegates to PowerProviderConstant → its pipeline (optimal /
        // optimalWithHarmonics) must also fire as a side-effect.
        val (cp, path) = buildCp(muscularW = 200.0, efficiency = 0.976)
        MuscularPowerProvider.powerAt(cp, path, 0)
        assertEquals(200.0, path.pCyclistProvidedOptimalPower(0), 0.0)
        assertEquals(200.0, path.pCyclistProvidedOptimalPowerWithHarmonics(0), 0.0)
    }
}
