package io.github.glandais.engine.physics

import io.github.glandais.elevation.MathConstants
import io.github.glandais.engine.Course
import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.Cyclist
import io.github.glandais.engine.EnhanceOptions
import io.github.glandais.engine.Enhancer
import io.github.glandais.engine.path.Path
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Power fading with supra-CP work rather than elapsed time (ledger R17). */
class PowerProviderDurabilityTest {
    private val cp = 250.0
    private val mass = 80.0

    private fun course(
        path: Path,
        provider: CyclistPowerProvider,
    ): CoursePhysics =
        CoursePhysics(
            course = Course(path = path, cyclist = Cyclist(massKg = mass)),
            rhoProvider = RhoProviderDefault,
            aeroProvider = AeroProviderConstant,
            windProvider = WindProviderNone,
            cyclistPowerProvider = provider,
        )

    /** A path carrying nothing but an `elapsed` clock — all the provider reads. */
    private fun clockPath(
        n: Int,
        stepS: Double,
    ): Path =
        Path(n).apply {
            for (i in 0 until n) setElapsed(i, i * stepS * 1000.0)
        }

    // ---- 1. The dose is supra-CP work, not time --------------------------------

    @Test
    fun `riding at or below CP never fades`() {
        val provider = PowerProviderDurability(powerW = cp, criticalPowerW = cp)
        val path = clockPath(3601, 1.0)
        val physics = course(path, provider)

        for (i in 0 until path.size) provider.powerAt(physics, path, i)

        // An hour at exactly CP: no dose, no fade, whatever the elapsed time says.
        assertEquals(0.0, provider.supraCriticalWorkJ, 1e-9)
        assertEquals(0.0, provider.decline, 0.0)
        assertEquals(cp, provider.powerAt(physics, path, path.size - 1), 1e-9)
    }

    @Test
    fun `the dose is the integral of power above CP`() {
        // 300 W against CP 250 for 1000 s = 50 kJ of supra-CP work… less the fade itself, which
        // reduces the power slightly as it accumulates, so assert against the closed form loosely.
        val provider = PowerProviderDurability(powerW = 300.0, criticalPowerW = cp)
        val path = clockPath(1001, 1.0)
        val physics = course(path, provider)
        for (i in 0 until path.size) provider.powerAt(physics, path, i)

        assertTrue(provider.supraCriticalWorkJ > 45_000.0, "dose too small: ${provider.supraCriticalWorkJ}")
        assertTrue(provider.supraCriticalWorkJ <= 50_000.0, "dose above the un-faded ceiling")

        // 50 kJ / 80 kg = 0.625 kJ/kg → 0.625 × (0.10/15) ≈ 0.42 % — a deliberately small number.
        assertEquals(0.0042, provider.decline, 5e-4)
    }

    @Test
    fun `the same elapsed time at a higher intensity fades more`() {
        // The whole point of R17: time alone must not determine the fade.
        fun declineAt(powerW: Double): Double {
            val provider = PowerProviderDurability(powerW = powerW, criticalPowerW = cp)
            val path = clockPath(3601, 1.0)
            val physics = course(path, provider)
            for (i in 0 until path.size) provider.powerAt(physics, path, i)
            return provider.decline
        }

        val easy = declineAt(260.0)
        val hard = declineAt(320.0)
        assertTrue(easy > 0.0, "260 W is above CP, so it must fade a little")
        assertTrue(hard > 6.0 * easy, "an hour at +70 W must cost far more than an hour at +10 W")
    }

    // ---- 2. Bounds and monotonicity --------------------------------------------

    @Test
    fun `the fade is monotone and capped`() {
        val provider = PowerProviderDurability(powerW = 400.0, criticalPowerW = cp, maxDecline = 0.10)
        val path = clockPath(20_001, 1.0)
        val physics = course(path, provider)

        var previous = Double.MAX_VALUE
        for (i in 0 until path.size) {
            val p = provider.powerAt(physics, path, i)
            assertTrue(p <= previous + 1e-12, "power increased at $i")
            assertTrue(p >= 400.0 * 0.90 - 1e-9, "fade went past maxDecline at $i")
            previous = p
        }
        assertEquals(0.10, provider.decline, 1e-12, "a long enough ride must reach the cap")
    }

    // ---- 3. Accumulator hygiene ------------------------------------------------

    @Test
    fun `calling twice for the same point counts the interval once`() {
        val provider = PowerProviderDurability(powerW = 300.0, criticalPowerW = cp)
        val path = clockPath(101, 1.0)
        val physics = course(path, provider)

        for (i in 0 until 101) provider.powerAt(physics, path, i)
        val once = provider.supraCriticalWorkJ
        provider.powerAt(physics, path, 100)
        assertEquals(once, provider.supraCriticalWorkJ, 1e-9, "re-reading a point re-counted its work")
    }

    @Test
    fun `a point index moving backwards starts a new simulation`() {
        val provider = PowerProviderDurability(powerW = 300.0, criticalPowerW = cp)
        val path = clockPath(101, 1.0)
        val physics = course(path, provider)

        for (i in 0 until 101) provider.powerAt(physics, path, i)
        assertTrue(provider.supraCriticalWorkJ > 0.0)

        provider.powerAt(physics, path, 0)
        assertEquals(0.0, provider.supraCriticalWorkJ, 0.0, "a second run must not inherit the dose")
    }

    @Test
    fun `reset clears the dose`() {
        val provider = PowerProviderDurability(powerW = 300.0, criticalPowerW = cp)
        val path = clockPath(101, 1.0)
        val physics = course(path, provider)
        for (i in 0 until 101) provider.powerAt(physics, path, i)

        provider.reset()
        assertEquals(0.0, provider.supraCriticalWorkJ, 0.0)
        assertEquals(0.0, provider.decline, 0.0)
    }

    @Test
    fun `invalid parameters are rejected`() {
        assertFailsWith<IllegalArgumentException> { PowerProviderDurability(powerW = 0.0) }
        assertFailsWith<IllegalArgumentException> { PowerProviderDurability(powerW = 250.0, criticalPowerW = -1.0) }
        assertFailsWith<IllegalArgumentException> {
            PowerProviderDurability(powerW = 250.0, declinePerKjPerKg = -0.1)
        }
        assertFailsWith<IllegalArgumentException> { PowerProviderDurability(powerW = 250.0, maxDecline = 1.5) }
    }

    @Test
    fun `defaults reach ten percent at fifteen kJ per kg`() {
        val provider = PowerProviderDurability(powerW = 300.0)
        assertEquals(0.10 / 15.0, PowerProviderDurability.DEFAULT_DECLINE_PER_KJ_PER_KG, 1e-12)
        assertEquals(0.20, PowerProviderDurability.DEFAULT_MAX_DECLINE, 1e-12)
        assertEquals(0.0, provider.decline, 0.0, "a fresh rider is not tired")
    }

    // ---- 4. Through the pipeline ------------------------------------------------

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
    fun `a durable rider is slower than a fresh one over a long ride`() =
        runTest {
            val options = EnhanceOptions(fixElevation = false)

            suspend fun durationS(provider: CyclistPowerProvider): Double {
                val out = Enhancer.enhanceCourse(course(flatPath(2000), provider), options)
                return (out.time(out.size - 1) - out.time(0)) / 1000.0
            }

            // ~25 km at 400 W against CP 250: a dose of roughly 5 kJ/kg, so a few percent of fade.
            val constant = durationS(PowerProviderConstant(400.0))
            val fading = durationS(PowerProviderDurability(powerW = 400.0, criticalPowerW = cp))

            assertTrue(fading > constant, "fading rider must take longer: $fading vs $constant")
            // …but only slightly: the fade is a slow burn, not a cliff.
            assertTrue((fading - constant) / constant < 0.05, "fade is implausibly aggressive")
        }
}
