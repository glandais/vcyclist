package io.github.glandais.engine.physics

import io.github.glandais.engine.Course
import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.path.Path
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PowerProviderConstantTest {
    private fun buildCp(provider: CyclistPowerProvider): Pair<CoursePhysics, Path> {
        val path = Path(1)
        path.setTime(0, 12_345.0)
        val course = Course(path)
        val cp = CoursePhysics(course = course, cyclistPowerProvider = provider)
        return cp to path
    }

    @Test
    fun returns_constant_power_without_harmonics() {
        val provider = PowerProviderConstant(250.0, useHarmonics = false)
        val (cp, path) = buildCp(provider)
        val p = provider.powerAt(cp, path, 0)
        assertEquals(250.0, p, 0.0)
    }

    @Test
    fun side_effect_optimal_power_matches_input() {
        val provider = PowerProviderConstant(250.0, useHarmonics = false)
        val (cp, path) = buildCp(provider)
        provider.powerAt(cp, path, 0)
        assertEquals(250.0, path.pCyclistProvidedOptimalPower(0), 0.0)
    }

    @Test
    fun side_effect_optimal_with_harmonics_equals_optimal_when_disabled() {
        val provider = PowerProviderConstant(250.0, useHarmonics = false)
        val (cp, path) = buildCp(provider)
        provider.powerAt(cp, path, 0)
        assertEquals(250.0, path.pCyclistProvidedOptimalPowerWithHarmonics(0), 0.0)
    }

    @Test
    fun with_harmonics_seed_42_yields_deterministic_value_different_from_base() {
        val provider = PowerProviderConstant(250.0, useHarmonics = true, random = Random(42L))
        val (cp, path) = buildCp(provider)
        val p = provider.powerAt(cp, path, 0)
        assertNotEquals(250.0, p, "harmonics should perturb the base power")
        // Side-effect slot reflects the perturbed value.
        assertEquals(p, path.pCyclistProvidedOptimalPowerWithHarmonics(0), 0.0)
        // Optimal slot still holds the raw base.
        assertEquals(250.0, path.pCyclistProvidedOptimalPower(0), 0.0)
    }

    @Test
    fun harmonics_deviation_is_bounded_below_25_percent() {
        val provider = PowerProviderConstant(250.0, useHarmonics = true, random = Random(7L))
        val (cp, path) = buildCp(provider)
        // Sample across several timestamps.
        for (tMs in longArrayOf(0L, 1_000L, 5_000L, 12_345L, 60_000L, 120_000L)) {
            path.setTime(0, tMs.toDouble())
            val p = provider.powerAt(cp, path, 0)
            val deviation = abs(p - 250.0) / 250.0
            assertTrue(
                deviation < 0.25,
                "deviation=$deviation at t=$tMs ms must stay below 25 %, got p=$p",
            )
        }
    }

    @Test
    fun pCyclistPowerNeeded_slot_remains_zero_at_this_stage() {
        // Slot is intentionally left at its init default (0.0) until task 19.
        val provider = PowerProviderConstant(250.0, useHarmonics = false)
        val (cp, path) = buildCp(provider)
        provider.powerAt(cp, path, 0)
        assertEquals(0.0, path.pCyclistPowerNeeded(0), 0.0)
    }
}
