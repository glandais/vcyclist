package io.github.glandais.engine.physics

import io.github.glandais.elevation.MathConstants
import io.github.glandais.engine.Course
import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.Cyclist
import io.github.glandais.engine.EnhanceOptions
import io.github.glandais.engine.Enhancer
import io.github.glandais.engine.path.Path
import kotlinx.coroutines.test.runTest
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Harder uphill, easier downhill — as a heuristic, not an optimiser (ledger R19). */
class PowerProviderTerrainPacingTest {
    private fun physics(
        path: Path,
        provider: CyclistPowerProvider,
    ): CoursePhysics =
        CoursePhysics(
            course = Course(path = path, cyclist = Cyclist.DEFAULT),
            rhoProvider = RhoProviderDefault,
            aeroProvider = AeroProviderConstant,
            windProvider = WindProviderNone,
            cyclistPowerProvider = provider,
        )

    /** A path carrying only what the rule reads: distance, grade, wind. */
    private fun terrainPath(
        n: Int,
        spacingM: Double = 10.0,
        grade: (Int) -> Double = { 0.0 },
        windSpeedMS: Double = 0.0,
        windAlphaRad: Double = 0.0,
    ): Path =
        Path(n).apply {
            for (i in 0 until n) {
                setDistance(i, i * spacingM)
                setGrade(i, grade(i))
                setWindSpeed(i, windSpeedMS)
                setWindAlpha(i, windAlphaRad)
            }
        }

    private fun pacing(delegate: CyclistPowerProvider = PowerProviderConstant(250.0)) = PowerProviderTerrainPacing(delegate)

    // ---- 1. The rule -----------------------------------------------------------

    @Test
    fun `flat road leaves the target alone`() {
        val p = pacing()
        val path = terrainPath(50)
        val course = physics(path, p)
        for (i in 0 until path.size) assertEquals(250.0, p.powerAt(course, path, i), 1e-9)
    }

    @Test
    fun `the raw multiplier rises with grade and headwind`() {
        val p = pacing()
        val climb = terrainPath(2, grade = { 0.05 })
        assertEquals(1.15, p.rawMultiplier(climb, 0), 1e-9, "5 % grade at gain 3 is +15 %")

        val descent = terrainPath(2, grade = { -0.05 })
        assertEquals(0.85, p.rawMultiplier(descent, 0), 1e-9)

        // cos(0) = 1, so this is 5 m/s straight into the wind: +10 %.
        val headwind = terrainPath(2, windSpeedMS = 5.0, windAlphaRad = 0.0)
        assertEquals(1.10, p.rawMultiplier(headwind, 0), 1e-9)

        // …and the same wind from behind gives it back.
        val tailwind = terrainPath(2, windSpeedMS = 5.0, windAlphaRad = PI)
        assertEquals(0.90, p.rawMultiplier(tailwind, 0), 1e-9)
    }

    @Test
    fun `the multiplier is clamped at both ends`() {
        val p = pacing()
        assertEquals(1.3, p.rawMultiplier(terrainPath(2, grade = { 0.25 }), 0), 1e-9)
        assertEquals(0.5, p.rawMultiplier(terrainPath(2, grade = { -0.25 }), 0), 1e-9)
    }

    // ---- 2. The asymmetry — the one thing the sources are specific about --------

    @Test
    fun `an increase is dispersed over hundreds of metres`() {
        val p = pacing()
        // One ramp distance of travel closes 1 - 1/e of the gap, not all of it.
        assertEquals(1.0 + 0.3 * 0.6321, p.smooth(1.0, 1.3, 300.0), 1e-3)
        assertEquals(1.0 + 0.3 * 0.2835, p.smooth(1.0, 1.3, 100.0), 1e-3)
        assertTrue(p.smooth(1.0, 1.3, 10.0) < 1.02, "a 10 m step barely moves the target")
    }

    @Test
    fun `a decrease applies immediately`() {
        val p = pacing()
        assertEquals(0.5, p.smooth(1.3, 0.5, 1.0), 0.0, "cresting a summit must drop power at once")
        assertEquals(0.5, p.smooth(1.3, 0.5, 300.0), 0.0)
    }

    @Test
    fun `climbing power ramps up over distance, descending power drops at the crest`() {
        val p = pacing()
        // 100 points, 10 m apart: flat, then a 10 % climb, then a 10 % descent.
        val path =
            terrainPath(100, grade = { i ->
                when {
                    i < 20 -> 0.0
                    i < 60 -> 0.10
                    else -> -0.10
                }
            })
        val course = physics(path, p)

        val powers = (0 until path.size).map { p.powerAt(course, path, it) }
        assertEquals(250.0, powers[19], 1e-9, "still flat")
        assertTrue(powers[21] < 250.0 * 1.1, "the ramp must not step: ${powers[21]}")
        assertTrue(powers[59] > powers[25], "power should still be building 300 m into the climb")
        // -10 % grade is a raw multiplier of 0.7, and the fall is not smoothed at all, so the
        // first descending point is exactly there — no ramp, no overshoot.
        assertEquals(250.0 * 0.7, powers[60], 1e-9, "the drop at the crest must be immediate")
    }

    // ---- 3. Hygiene and composition --------------------------------------------

    @Test
    fun `re-reading a point is idempotent and going backwards resets`() {
        val p = pacing()
        val path = terrainPath(30, grade = { 0.08 })
        val course = physics(path, p)
        for (i in 0 until 10) p.powerAt(course, path, i)
        val at9 = p.multiplier
        p.powerAt(course, path, 9)
        assertEquals(at9, p.multiplier, 0.0, "re-reading advanced the smoother")

        p.powerAt(course, path, 0)
        assertEquals(1.0, p.multiplier, 1e-9, "a new run starts unmodulated")
    }

    @Test
    fun `it composes with the critical-power rider`() {
        val inner = PowerProviderCriticalPower(powerW = 350.0, criticalPowerW = 250.0)
        val p = PowerProviderTerrainPacing(inner)
        val path =
            Path(200).apply {
                for (i in 0 until 200) {
                    setDistance(i, i * 10.0)
                    setElapsed(i, i * 1.0)
                    setGrade(i, 0.08)
                }
            }
        val course = physics(path, p)
        for (i in 0 until path.size) p.powerAt(course, path, i)

        assertTrue(inner.reserveFraction < 1.0, "the inner rider still spends its reserve")
        assertTrue(p.multiplier > 1.0, "and the climb still asks for more")
    }

    @Test
    fun `invalid parameters are rejected`() {
        val delegate = PowerProviderConstant(250.0)
        assertFailsWith<IllegalArgumentException> {
            PowerProviderTerrainPacing(delegate, gradientGain = -1.0)
        }
        assertFailsWith<IllegalArgumentException> {
            PowerProviderTerrainPacing(delegate, minMultiplier = 0.0)
        }
        assertFailsWith<IllegalArgumentException> {
            PowerProviderTerrainPacing(delegate, minMultiplier = 1.0, maxMultiplier = 0.5)
        }
        assertFailsWith<IllegalArgumentException> {
            PowerProviderTerrainPacing(delegate, rampDistanceM = 0.0)
        }
    }

    // ---- 4. Through the pipeline -------------------------------------------------

    /** Rolling terrain: 2 km of 6 % up, 2 km of 6 % down, repeated. */
    private fun rollingPath(): Path {
        val n = 800
        val p = Path(n)
        var ele = 100.0
        for (i in 0 until n) {
            val climbing = (i / 200) % 2 == 0
            ele += if (climbing) 0.6 else -0.6
            p.setLatitude(i, 45.0 * MathConstants.DEG_TO_RAD)
            p.setLongitude(i, (3.0 + i * 1.27e-4) * MathConstants.DEG_TO_RAD)
            p.setElevation(i, ele)
            p.setTime(i, i * 1000.0)
        }
        p.computeDerivedData()
        return p
    }

    @Test
    fun `on rolling terrain it redistributes power without inventing any`() =
        runTest {
            val options = EnhanceOptions(fixElevation = false)

            suspend fun ride(provider: CyclistPowerProvider): Double {
                val out = Enhancer.enhanceCourse(physics(rollingPath(), provider), options)
                return (out.time(out.size - 1) - out.time(0)) / 1000.0
            }

            val paced = PowerProviderTerrainPacing(PowerProviderConstant(280.0))
            val flatS = ride(PowerProviderConstant(280.0))
            val pacedS = ride(paced)

            // The heuristic is not allowed to be a power cheat. Without the energy account this
            // rule came out 10 % faster on 11 % more power on real fixtures, which is not pacing —
            // it is just riding harder, and it is the specific failure the account exists to
            // prevent. Assert it on the account itself: the closing debt must be a small fraction
            // of the work a 280 W rider does over the same ride.
            val totalWorkJ = 280.0 * pacedS
            assertTrue(
                kotlin.math.abs(paced.energyDebtJ) < 0.05 * totalWorkJ,
                "energy account closed at ${paced.energyDebtJ} J on $totalWorkJ J of riding",
            )
            // Redistribution should not *cost* time on rolling terrain…
            assertTrue(pacedS <= flatS * 1.01, "paced $pacedS s vs constant $flatS s")
            // …and cannot plausibly gain a lot: the whole optimum is worth 1-3 % by the
            // literature's own account, so anything dramatic means a bug, not a breakthrough.
            assertTrue(
                (flatS - pacedS) / flatS < 0.10,
                "implausible pacing gain: paced $pacedS s vs constant $flatS s",
            )
        }
}
