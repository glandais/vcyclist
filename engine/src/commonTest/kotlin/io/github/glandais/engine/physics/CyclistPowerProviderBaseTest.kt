package io.github.glandais.engine.physics

import io.github.glandais.engine.Course
import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.path.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CyclistPowerProviderBaseTest {
    /**
     * Subclass exposing the protected [getRealOptimalPower] method for direct testing. Its
     * [optimalPower] hook is unused for those tests (returns 0.0).
     */
    private class TestableBase(
        useHarmonics: Boolean = false,
        random: Random = Random.Default,
    ) : CyclistPowerProviderBase(useHarmonics, random) {
        override fun optimalPower(
            course: CoursePhysics,
            path: Path,
            pointIndex: Int,
        ): Double = 0.0

        fun exposeRealOptimal(
            optimal: Double,
            powerNeeded: Double,
        ): Double = getRealOptimalPower(optimal, powerNeeded)
    }

    @Test
    fun getRealOptimalPower_within_tolerance_returns_optimal() {
        // powerNeeded = 190 → 5 % below 200 → within ±5 % → returns 200 verbatim.
        val base = TestableBase()
        assertEquals(200.0, base.exposeRealOptimal(optimal = 200.0, powerNeeded = 190.0), 0.0)
    }

    @Test
    fun getRealOptimalPower_too_slow_boosts_above_optimal() {
        // powerNeeded = 100 (50 % below) → linear boost toward 3× = 600.
        val base = TestableBase()
        val out = base.exposeRealOptimal(optimal = 200.0, powerNeeded = 100.0)
        assertTrue(out > 200.0, "expected boosted power > optimal, got $out")
        assertTrue(out < 600.0, "expected boosted power < 3× optimal cap, got $out")
        // Formula check : 200×3 − (100 / 190) × 200 × 2 = 600 − 210.526… = 389.473…
        val expected = 200.0 * 3.0 - (100.0 / (200.0 * 0.95)) * 200.0 * 2.0
        assertEquals(expected, out, 1e-9)
    }

    @Test
    fun getRealOptimalPower_too_fast_reduces_below_optimal() {
        // powerNeeded = 300 (50 % above) → linear decrease.
        val base = TestableBase()
        val out = base.exposeRealOptimal(optimal = 200.0, powerNeeded = 300.0)
        assertTrue(out < 200.0, "expected reduced power < optimal, got $out")
        assertTrue(out >= 0.0, "expected non-negative power, got $out")
        // diff = 300 − 210 = 90 ; coef = clamp(90/210, 0, 1) = 0.428571… → 200 − 0.428…×200.
        val expected = 200.0 - ((300.0 - 200.0 * 1.05) / (200.0 * 1.05)) * 200.0
        assertEquals(expected, out, 1e-9)
    }

    @Test
    fun getRealOptimalPower_far_above_optimal_floors_at_zero() {
        // powerNeeded ≫ optimal → coef clamps to 1 → power = 0.
        val base = TestableBase()
        val out = base.exposeRealOptimal(optimal = 200.0, powerNeeded = 10_000.0)
        assertEquals(0.0, out, 0.0)
    }

    @Test
    fun rng_injectable_two_instances_with_same_seed_produce_identical_harmonics() {
        val a = TestableBase(useHarmonics = true, random = Random(123L))
        val b = TestableBase(useHarmonics = true, random = Random(123L))
        assertEquals(20, a.harmonics.size)
        assertEquals(20, b.harmonics.size)
        for (i in a.harmonics.indices) {
            assertEquals(a.harmonics[i].freqRadS, b.harmonics[i].freqRadS, 0.0)
            assertEquals(a.harmonics[i].phaseRad, b.harmonics[i].phaseRad, 0.0)
            assertEquals(a.harmonics[i].amp, b.harmonics[i].amp, 0.0)
        }
    }

    @Test
    fun rng_injectable_different_seeds_produce_different_harmonics() {
        val a = TestableBase(useHarmonics = true, random = Random(1L))
        val b = TestableBase(useHarmonics = true, random = Random(2L))
        // Probabilistically guaranteed : at least one harmonic differs in freq.
        val anyDiff =
            a.harmonics.zip(b.harmonics).any { (ha, hb) -> ha.freqRadS != hb.freqRadS }
        assertTrue(anyDiff, "expected at least one differing harmonic between seeds")
    }

    @Test
    fun harmonics_empty_when_disabled() {
        val base = TestableBase(useHarmonics = false, random = Random(123L))
        assertEquals(0, base.harmonics.size)
    }

    /**
     * Regression for task 19 : [powerAt] now writes `pCyclistPowerNeeded(i)` via
     * `-PowerComputer.getNewPower(course, path, i, withCyclist=false)`. At v=10 m/s on flat
     * ground the resistive sum is strictly negative, so `powerNeeded` must be strictly positive.
     */
    @Test
    fun powerAt_writes_pCyclistPowerNeeded_from_resistive_balance() {
        val path = Path(1)
        path.setSpeed(0, 10.0)
        val course = Course(path)
        val cp = CoursePhysics(course)
        val provider = TestableBase(useHarmonics = false)
        // Default value before invocation.
        assertEquals(0.0, path.pCyclistPowerNeeded(0), 0.0)

        provider.powerAt(cp, path, 0)

        val written = path.pCyclistPowerNeeded(0)
        assertTrue(written > 0.0, "expected positive pCyclistPowerNeeded after resistive balance, got $written")
        // Cross-check : should equal `-getNewPower(false)` on a fresh path.
        val fresh = Path(1)
        fresh.setSpeed(0, 10.0)
        val freshCp = CoursePhysics(Course(fresh))
        val expected = -PowerComputer.getNewPower(freshCp, fresh, 0, withCyclist = false)
        assertEquals(expected, written, 1e-12)
    }
}
