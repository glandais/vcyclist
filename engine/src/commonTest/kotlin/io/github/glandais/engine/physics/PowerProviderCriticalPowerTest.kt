package io.github.glandais.engine.physics

import io.github.glandais.elevation.MathConstants
import io.github.glandais.engine.Course
import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.Cyclist
import io.github.glandais.engine.EnhanceOptions
import io.github.glandais.engine.Enhancer
import io.github.glandais.engine.path.Path
import io.github.glandais.engine.physiology.WPrimeBalanceComputer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A rider who spends a reserve and settles at CP (ledger R16). */
class PowerProviderCriticalPowerTest {
    private val cp = 250.0
    private val wPrime = 20_000.0

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
            for (i in 0 until n) setElapsed(i, i * stepS * 1000.0)
        }

    private fun provider(
        powerW: Double = 350.0,
        taper: Double = PowerProviderCriticalPower.DEFAULT_TAPER_START_FRACTION,
    ) = PowerProviderCriticalPower(powerW, cp, wPrime, taperStartFraction = taper)

    // ---- 1. The taper ----------------------------------------------------------

    @Test
    fun `a fresh rider gets the full target`() {
        val p = provider()
        assertEquals(1.0, p.reserveFraction, 0.0)
        assertEquals(350.0, p.ration(350.0), 1e-9)
    }

    @Test
    fun `the target bleeds to CP as the reserve empties`() {
        val p = provider()
        val path = clockPath(2000)
        val course = physics(path, p)

        // 100 W above CP: the reserve lasts 200 s, taper starts at 100 s.
        for (i in 0 until 100) p.powerAt(course, path, i)
        assertEquals(350.0, p.powerAt(course, path, 100), 1.0, "still full while above half a tank")

        for (i in 101 until 1000) p.powerAt(course, path, i)
        // The approach is asymptotic, not a cliff: below the taper point the depletion rate is
        // itself proportional to what is left, so `w` decays exponentially and power converges on
        // CP without ever quite arriving. After 15 minutes it is within 0.05 W.
        assertEquals(cp, p.powerAt(course, path, 1000), 0.05, "must have converged on CP")
        assertTrue(p.powerAt(course, path, 1001) > cp, "…without ever dropping to or below it")
        assertTrue(p.reserveFraction < 0.001, "reserve should be all but gone: ${p.reserveFraction}")
    }

    @Test
    fun `the approach to CP is exponential`() {
        val p = provider()
        val path = clockPath(4000)
        val course = physics(path, p)

        // Below the taper point: dW/dt = -(target-CP)*w/taper, so w decays with a time constant of
        // taper * W' / (target - CP) = 0.5 * 20000 / 100 = 100 s. Check two successive decades.
        for (i in 0 until 100) p.powerAt(course, path, i) // spend down to the taper point
        val start = p.reserveFraction
        for (i in 100 until 200) p.powerAt(course, path, i)
        val after100 = p.reserveFraction
        for (i in 200 until 300) p.powerAt(course, path, i)
        val after200 = p.reserveFraction

        val firstRatio = after100 / start
        val secondRatio = after200 / after100
        assertEquals(firstRatio, secondRatio, 0.02, "decay is not exponential")
        assertEquals(0.37, firstRatio, 0.05, "one time constant should leave ~1/e")
    }

    @Test
    fun `power never falls below CP nor rises above the target`() {
        val p = provider()
        val path = clockPath(3000)
        val course = physics(path, p)
        for (i in 0 until path.size) {
            val w = p.powerAt(course, path, i)
            assertTrue(w >= cp - 1e-9, "dropped below CP at $i: $w")
            assertTrue(w <= 350.0 + 1e-9, "exceeded the target at $i: $w")
        }
    }

    @Test
    fun `a sub-CP target is never rationed`() {
        val p = provider(powerW = 200.0)
        val path = clockPath(500)
        val course = physics(path, p)
        for (i in 0 until path.size) assertEquals(200.0, p.powerAt(course, path, i), 1e-9)
        assertEquals(1.0, p.reserveFraction, 1e-9, "riding under CP cannot deplete the reserve")
    }

    // ---- 2. Recovery -----------------------------------------------------------

    @Test
    fun `easing off refills the reserve and raises the ceiling again`() {
        // Deplete at 350 W, then let the same instance recover at 150 W by scripting the target.
        val p = provider()
        val path = clockPath(4000)
        val course = physics(path, p)
        for (i in 0 until 400) p.powerAt(course, path, i)
        val emptied = p.reserveFraction
        assertTrue(emptied < 0.05, "400 s at 100 W over CP must nearly empty the tank: $emptied")

        // The provider rations its own target, so recovery is exercised through `step` with the
        // power a rider would actually be doing while soft-pedalling.
        val recovered =
            WPrimeBalanceComputer.step(p.wPrimeBalanceJ, 150.0, cp, wPrime, 300.0)
        assertTrue(recovered > p.wPrimeBalanceJ, "five minutes under CP must refill something")
        assertTrue(recovered / wPrime > 0.5, "…and enough to lift the taper again: ${recovered / wPrime}")
    }

    @Test
    fun `the reserve uses the same step function as the wPrimeBalance field`() {
        val p = provider()
        val path = clockPath(3)
        val course = physics(path, p)

        p.powerAt(course, path, 0)
        val first = p.powerAt(course, path, 1)
        // One second at the point-0 power, by the same maths the post-pipeline pass applies.
        val expected = WPrimeBalanceComputer.step(wPrime, 350.0, cp, wPrime, 1.0)
        assertEquals(expected, p.wPrimeBalanceJ, 1e-9)
        assertTrue(first <= 350.0)
    }

    // ---- 3. Hygiene ------------------------------------------------------------

    @Test
    fun `re-reading a point is idempotent and going backwards resets`() {
        val p = provider()
        val path = clockPath(50)
        val course = physics(path, p)
        for (i in 0 until 10) p.powerAt(course, path, i)
        val balance = p.wPrimeBalanceJ
        p.powerAt(course, path, 9)
        assertEquals(balance, p.wPrimeBalanceJ, 1e-12, "re-reading spent the reserve twice")

        p.powerAt(course, path, 0)
        assertEquals(wPrime, p.wPrimeBalanceJ, 0.0, "a new run starts fresh")
    }

    @Test
    fun `invalid parameters are rejected`() {
        assertFailsWith<IllegalArgumentException> { PowerProviderCriticalPower(powerW = 0.0) }
        assertFailsWith<IllegalArgumentException> { PowerProviderCriticalPower(300.0, criticalPowerW = 0.0) }
        assertFailsWith<IllegalArgumentException> { PowerProviderCriticalPower(300.0, wPrimeJ = 0.0) }
        assertFailsWith<IllegalArgumentException> {
            PowerProviderCriticalPower(300.0, taperStartFraction = 1.5)
        }
    }

    // ---- 4. Through the pipeline -------------------------------------------------

    /** 5 km at a steady 6 % — long enough to empty a reserve on. */
    private fun climbPath(n: Int): Path {
        val p = Path(n)
        for (i in 0 until n) {
            p.setLatitude(i, 45.0 * MathConstants.DEG_TO_RAD)
            p.setLongitude(i, (3.0 + i * 1.27e-4) * MathConstants.DEG_TO_RAD)
            p.setElevation(i, 100.0 + i * 0.6) // ~10 m per point of run, 0.6 m of rise
            p.setTime(i, i * 1000.0)
        }
        p.computeDerivedData()
        return p
    }

    @Test
    fun `a rider who spends the reserve on a climb is overtaken by one who paces it`() =
        runTest {
            val options = EnhanceOptions(fixElevation = false)

            suspend fun ride(provider: CyclistPowerProvider): Path = Enhancer.enhanceCourse(physics(climbPath(500), provider), options)

            val rationed = ride(provider(powerW = 400.0))
            val flat = ride(PowerProviderConstant(400.0))

            val rationedS = (rationed.time(rationed.size - 1) - rationed.time(0)) / 1000.0
            val flatS = (flat.time(flat.size - 1) - flat.time(0)) / 1000.0

            // The rationed rider cannot hold 400 W once the tank is dry, so the climb takes longer
            // than for a rider who ignores physiology entirely.
            assertTrue(rationedS > flatS, "rationed $rationedS s vs unlimited $flatS s")
            // …but not absurdly: the floor is CP, not zero.
            assertTrue(rationedS < flatS * 2.0, "rationed rider collapsed: $rationedS vs $flatS")
        }

    @Test
    fun `the field agrees with the rider's own bookkeeping at the finish`() =
        runTest {
            val p = provider(powerW = 400.0)
            val out = Enhancer.enhanceCourse(physics(climbPath(500), p), EnhanceOptions(fixElevation = false))

            // Both start full and integrate the same ODE; the post-pipeline field reads the power
            // that was actually delivered, so it can only be more optimistic (coasting recovers).
            val field = out.wPrimeBalance(out.size - 1)
            assertTrue(field >= p.wPrimeBalanceJ - 1e-6, "field $field vs rider ${p.wPrimeBalanceJ}")
            assertTrue(p.reserveFraction < 0.5, "a 400 W climb must eat most of the reserve")
        }
}
