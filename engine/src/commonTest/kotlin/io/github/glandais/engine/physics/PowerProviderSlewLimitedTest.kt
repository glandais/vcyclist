package io.github.glandais.engine.physics

import io.github.glandais.elevation.MathConstants
import io.github.glandais.engine.Course
import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.Cyclist
import io.github.glandais.engine.EngineConstants
import io.github.glandais.engine.EnhanceOptions
import io.github.glandais.engine.Enhancer
import io.github.glandais.engine.path.Path
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Power cannot step instantaneously (ledger R18). */
class PowerProviderSlewLimitedTest {
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

    private fun clockPath(
        n: Int,
        stepS: Double = 1.0,
    ): Path =
        Path(n).apply {
            for (i in 0 until n) setElapsed(i, i * stepS)
        }

    /** A provider that returns whatever the test dictates, per point. */
    private class Scripted(
        val values: (Int) -> Double,
    ) : CyclistPowerProvider {
        override fun powerAt(
            course: CoursePhysics,
            path: Path,
            pointIndex: Int,
        ): Double = values(pointIndex)
    }

    // ---- 1. The limit ----------------------------------------------------------

    @Test
    fun `power ramps at the configured rate instead of stepping`() {
        val provider = PowerProviderSlewLimited(Scripted { 300.0 }, maxSlewWPerS = 50.0)
        val path = clockPath(10)
        val course = physics(path, provider)

        // From a standstill at 50 W/s and 1 s steps: 0, 50, 100, …
        assertEquals(0.0, provider.powerAt(course, path, 0), 1e-9)
        assertEquals(50.0, provider.powerAt(course, path, 1), 1e-9)
        assertEquals(100.0, provider.powerAt(course, path, 2), 1e-9)
        for (i in 3 until 6) provider.powerAt(course, path, i)
        assertEquals(300.0, provider.powerAt(course, path, 6), 1e-9, "…and then holds the target")
    }

    @Test
    fun `drops are limited too`() {
        val provider = PowerProviderSlewLimited(Scripted { if (it < 10) 400.0 else 0.0 }, maxSlewWPerS = 50.0)
        val path = clockPath(20)
        val course = physics(path, provider)

        for (i in 0 until 10) provider.powerAt(course, path, i)
        val atStep = provider.powerAt(course, path, 10)
        assertEquals(350.0, atStep, 1e-9, "a 400 W drop must take 8 s, not one point")
    }

    @Test
    fun `the budget scales with the time step`() {
        val provider = PowerProviderSlewLimited(Scripted { 1000.0 }, maxSlewWPerS = 50.0)
        val path = clockPath(3, stepS = 4.0)
        val course = physics(path, provider)

        provider.powerAt(course, path, 0)
        assertEquals(200.0, provider.powerAt(course, path, 1), 1e-9, "4 s at 50 W/s = 200 W")
    }

    @Test
    fun `no elapsed time means no change`() {
        val provider = PowerProviderSlewLimited(Scripted { 300.0 })
        val path = Path(3) // every elapsed is 0
        val course = physics(path, provider)

        for (i in 0 until 3) assertEquals(0.0, provider.powerAt(course, path, i), 0.0)
    }

    // ---- 2. Accumulator hygiene ------------------------------------------------

    @Test
    fun `re-reading a point is idempotent and going backwards resets`() {
        val provider = PowerProviderSlewLimited(Scripted { 300.0 }, maxSlewWPerS = 50.0)
        val path = clockPath(5)
        val course = physics(path, provider)

        provider.powerAt(course, path, 0)
        val first = provider.powerAt(course, path, 1)
        assertEquals(first, provider.powerAt(course, path, 1), 0.0, "re-reading advanced the ramp")

        provider.powerAt(course, path, 0)
        assertEquals(0.0, provider.lastPowerW, 0.0, "a new run must start from zero again")
    }

    @Test
    fun `reset clears the state and bad parameters are rejected`() {
        val provider = PowerProviderSlewLimited(Scripted { 300.0 })
        val path = clockPath(5)
        val course = physics(path, provider)
        for (i in 0 until 5) provider.powerAt(course, path, i)
        provider.reset()
        assertEquals(0.0, provider.lastPowerW, 0.0)

        assertFailsWith<IllegalArgumentException> {
            PowerProviderSlewLimited(PowerProviderConstant(250.0), maxSlewWPerS = 0.0)
        }
    }

    @Test
    fun `the default is Zignoli's 50 W per second`() {
        assertEquals(50.0, EngineConstants.DEFAULT_MAX_POWER_SLEW_W_PER_S, 0.0)
        assertEquals(50.0, PowerProviderSlewLimited(PowerProviderConstant(250.0)).maxSlewWPerS, 0.0)
    }

    // ---- 3. Composition --------------------------------------------------------

    @Test
    fun `it composes with the durability provider`() {
        val inner = PowerProviderDurability(powerW = 400.0, criticalPowerW = 250.0)
        val provider = PowerProviderSlewLimited(inner, maxSlewWPerS = 50.0)
        val path = clockPath(2001)
        val course = physics(path, provider)

        for (i in 0 until path.size) provider.powerAt(course, path, i)

        assertTrue(inner.supraCriticalWorkJ > 0.0, "the inner provider still accumulates its dose")
        assertTrue(provider.lastPowerW < 400.0, "and the fade still shows through the limiter")
        assertTrue(provider.lastPowerW > 350.0, "…as a fade, not as a collapse")
    }

    // ---- 4. Through the pipeline -------------------------------------------------

    private fun flatPath(n: Int): Path {
        val p = Path(n)
        for (i in 0 until n) {
            p.setLatitude(i, 45.0 * MathConstants.DEG_TO_RAD)
            p.setLongitude(i, (3.0 + i * 1.27e-4) * MathConstants.DEG_TO_RAD)
            p.setElevation(i, 100.0)
            p.setTime(i, i * 1000.0)
        }
        p.computeDerivedData()
        return p
    }

    @Test
    fun `no simulated point ever jumps by more than the budget`() =
        runTest {
            val provider = PowerProviderSlewLimited(PowerProviderConstant(280.0), maxSlewWPerS = 50.0)
            val out =
                Enhancer.enhanceCourse(
                    physics(flatPath(400), provider),
                    EnhanceOptions(
                        fixElevation = false,
                        computeOnePointPerSecond = false,
                        simplifyPath =
                            io.github.glandais.engine
                                .SimplifyPathOptions(enabled = false),
                    ),
                )

            for (i in 1 until out.size) {
                val dtS = out.elapsed(i) - out.elapsed(i - 1)
                if (dtS <= 0.0 || !dtS.isFinite()) continue
                val delta = abs(out.pCyclistProvidedMuscular(i) - out.pCyclistProvidedMuscular(i - 1))
                // The pedal-strike cut-off (R10) is applied downstream and is deliberately not
                // rate-limited, so only compare where both points are pedalling.
                if (out.pCyclistProvidedMuscular(i) == 0.0 || out.pCyclistProvidedMuscular(i - 1) == 0.0) continue
                assertTrue(
                    delta <= 50.0 * dtS + 1e-6,
                    "power jumped $delta W in $dtS s at $i",
                )
            }
        }
}
