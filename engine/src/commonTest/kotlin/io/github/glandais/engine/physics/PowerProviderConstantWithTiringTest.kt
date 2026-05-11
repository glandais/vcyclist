package io.github.glandais.engine.physics

import io.github.glandais.engine.Course
import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PowerProviderConstantWithTiringTest {
    private val tol = 1e-12

    private fun buildCp(provider: CyclistPowerProvider): Pair<CoursePhysics, Path> {
        val path = Path(1)
        val course = Course(path)
        val cp = CoursePhysics(course = course, cyclistPowerProvider = provider)
        return cp to path
    }

    @Test
    fun at_elapsed_zero_returns_base_power() {
        val provider = PowerProviderConstantWithTiring(power = 200.0, durationSeconds = 3600.0)
        val (cp, path) = buildCp(provider)
        path.setElapsed(0, 0.0)
        val p = provider.powerAt(cp, path, 0)
        assertEquals(200.0, p, tol)
    }

    @Test
    fun at_half_duration_returns_70_percent_of_base() {
        // c = max(0.5, 1 - 0.6 × 0.5) = max(0.5, 0.7) = 0.7
        val provider = PowerProviderConstantWithTiring(power = 200.0, durationSeconds = 3600.0)
        val (cp, path) = buildCp(provider)
        path.setElapsed(0, 1800.0 * 1000.0) // 1800 s in ms
        val p = provider.powerAt(cp, path, 0)
        assertEquals(200.0 * 0.7, p, tol)
    }

    @Test
    fun at_full_duration_returns_50_percent_floor() {
        // c = max(0.5, 1 - 0.6 × 1) = max(0.5, 0.4) = 0.5
        val provider = PowerProviderConstantWithTiring(power = 200.0, durationSeconds = 3600.0)
        val (cp, path) = buildCp(provider)
        path.setElapsed(0, 3600.0 * 1000.0)
        val p = provider.powerAt(cp, path, 0)
        assertEquals(100.0, p, tol)
    }

    @Test
    fun beyond_duration_clamps_to_50_percent_floor() {
        val provider = PowerProviderConstantWithTiring(power = 200.0, durationSeconds = 3600.0)
        val (cp, path) = buildCp(provider)
        path.setElapsed(0, 7200.0 * 1000.0) // 2× duration
        val p = provider.powerAt(cp, path, 0)
        assertEquals(100.0, p, tol)
    }

    @Test
    fun duration_zero_or_negative_throws() {
        assertFailsWith<IllegalArgumentException> {
            PowerProviderConstantWithTiring(power = 200.0, durationSeconds = 0.0)
        }
        assertFailsWith<IllegalArgumentException> {
            PowerProviderConstantWithTiring(power = 200.0, durationSeconds = -10.0)
        }
    }

    @Test
    fun side_effect_optimal_power_matches_returned_value() {
        val provider = PowerProviderConstantWithTiring(power = 200.0, durationSeconds = 3600.0)
        val (cp, path) = buildCp(provider)
        path.setElapsed(0, 1800.0 * 1000.0)
        val p = provider.powerAt(cp, path, 0)
        assertEquals(p, path.pCyclistProvidedOptimalPower(0), 0.0)
        // Without harmonics, harmonics slot equals optimal slot.
        assertEquals(p, path.pCyclistProvidedOptimalPowerWithHarmonics(0), 0.0)
    }
}
