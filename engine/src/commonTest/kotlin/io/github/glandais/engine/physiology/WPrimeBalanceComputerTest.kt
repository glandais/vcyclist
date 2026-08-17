package io.github.glandais.engine.physiology

import io.github.glandais.elevation.MathConstants
import io.github.glandais.engine.EngineConstants
import io.github.glandais.engine.EnhanceOptions
import io.github.glandais.engine.Enhancer
import io.github.glandais.engine.WPrimeBalanceOptions
import io.github.glandais.engine.path.Path
import io.github.glandais.engine.path.PointField
import kotlinx.coroutines.test.runTest
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WPrimeBalanceComputerTest {
    private val cp = 250.0
    private val wPrime = 20_000.0
    private val options = WPrimeBalanceOptions(criticalPowerW = cp, wPrimeJ = wPrime)

    /** Path of [n] points, all at [power] W, one second apart. */
    private fun constantPowerPath(
        n: Int,
        power: Double,
        dtMs: Double = 1000.0,
    ): Path =
        Path(n).apply {
            for (i in 0 until n) {
                setDt(i, if (i == 0) 0.0 else dtMs)
                setPComputedPower(i, power)
            }
        }

    // ---- 1. The two branches, against hand-computed values ---------------------

    @Test
    fun `depletion above CP is linear in time`() {
        // 100 W above CP for 10 s = 1000 J out of the reserve.
        val path = constantPowerPath(11, cp + 100.0)
        WPrimeBalanceComputer.compute(path, options)

        assertEquals(wPrime, path.wPrimeBalance(0), 1e-9, "point 0 starts full")
        assertEquals(wPrime - 100.0, path.wPrimeBalance(1), 1e-9)
        assertEquals(wPrime - 1000.0, path.wPrimeBalance(10), 1e-9)
    }

    @Test
    fun `recovery below CP follows the closed-form exponential`() {
        // Start depleted, then recover at 100 W below CP for 60 s.
        val path = Path(2)
        path.setDt(1, 60_000.0)
        path.setPComputedPower(1, cp - 100.0)
        WPrimeBalanceComputer.compute(path, options)

        // Point 0 is full, so there is nothing to recover — the exact form must be a no-op.
        assertEquals(wPrime, path.wPrimeBalance(1), 1e-9)

        // Same expression from a genuinely depleted state.
        val start = 5_000.0
        val expected = wPrime - (wPrime - start) * exp(-100.0 * 60.0 / wPrime)
        assertEquals(
            expected,
            WPrimeBalanceComputer.step(start, cp - 100.0, cp, wPrime, 60.0),
            1e-9,
        )
        assertTrue(expected > start, "recovery must refill the reserve")
    }

    @Test
    fun `recovery is asymptotic and never exceeds W prime`() {
        // 60 s stopped (P = 0) from empty : exp(−250·60/20000) = exp(−0.75), so ~53 % refilled.
        val oneMinute = WPrimeBalanceComputer.step(0.0, 0.0, cp, wPrime, 60.0)
        assertEquals(wPrime * (1.0 - exp(-0.75)), oneMinute, 1e-9)
        assertTrue(oneMinute < wPrime, "exponential recovery approaches W′ without reaching it")

        // An hour : exp(−45) ≈ 3e−20, below the ulp of 20 000, so the double *is* W′. The model is
        // asymptotic, the arithmetic is not — the clamp is what guarantees it never overshoots.
        val oneHour = WPrimeBalanceComputer.step(0.0, 0.0, cp, wPrime, 3600.0)
        assertEquals(wPrime, oneHour, 0.0)
    }

    @Test
    fun `depletion clamps at zero rather than going negative`() {
        // 750 W (500 above CP) for 60 s would spend 30 000 J out of a 20 000 J reserve.
        val path = constantPowerPath(61, 750.0)
        WPrimeBalanceComputer.compute(path, options)

        assertEquals(0.0, path.wPrimeBalance(60), 1e-9)
        for (i in path.indices) {
            assertTrue(path.wPrimeBalance(i) >= 0.0, "no negative balance at $i")
        }
    }

    @Test
    fun `power exactly at CP holds the balance steady`() {
        val path = constantPowerPath(10, cp)
        WPrimeBalanceComputer.compute(path, options)
        assertEquals(wPrime, path.wPrimeBalance(9), 1e-9)
    }

    // ---- 2. dt handling — the trap in GoldenCheetah's 1 Hz recursion ------------

    @Test
    fun `depletion scales with dt, not with point count`() {
        // Same 10 s of the same effort, sampled at 1 Hz and at 0.1 Hz : same reserve spent.
        val oneHz = constantPowerPath(11, cp + 100.0, dtMs = 1000.0)
        val tenSecond = constantPowerPath(2, cp + 100.0, dtMs = 10_000.0)
        WPrimeBalanceComputer.compute(oneHz, options)
        WPrimeBalanceComputer.compute(tenSecond, options)

        assertEquals(oneHz.wPrimeBalance(10), tenSecond.wPrimeBalance(1), 1e-9)
    }

    @Test
    fun `recovery composes across steps`() {
        // The closed form is exact over a constant-power interval, so 2 x 30 s == 1 x 60 s.
        val split =
            WPrimeBalanceComputer.step(
                WPrimeBalanceComputer.step(5_000.0, 150.0, cp, wPrime, 30.0),
                150.0,
                cp,
                wPrime,
                30.0,
            )
        val single = WPrimeBalanceComputer.step(5_000.0, 150.0, cp, wPrime, 60.0)
        assertEquals(single, split, 1e-9)
    }

    // ---- 3. Gaps carry forward rather than poisoning the trace -----------------

    @Test
    fun `non-finite power or dt carries the previous balance forward`() {
        val path = Path(4)
        for (i in 1 until 4) path.setDt(i, 1000.0)
        path.setPComputedPower(1, cp + 1000.0) // −1000 J
        path.setPComputedPower(2, Double.NaN) // ignored
        path.setDt(3, Double.NaN) // ignored
        path.setPComputedPower(3, cp + 1000.0)

        WPrimeBalanceComputer.compute(path, options)

        assertEquals(wPrime - 1000.0, path.wPrimeBalance(1), 1e-9)
        assertEquals(wPrime - 1000.0, path.wPrimeBalance(2), 1e-9)
        assertEquals(wPrime - 1000.0, path.wPrimeBalance(3), 1e-9)
        for (i in path.indices) assertTrue(path.wPrimeBalance(i).isFinite(), "finite at $i")
    }

    @Test
    fun `empty path is a no-op`() {
        WPrimeBalanceComputer.compute(Path(0), options)
    }

    @Test
    fun `single point starts full`() {
        val path = Path(1)
        WPrimeBalanceComputer.compute(path, options)
        assertEquals(wPrime, path.wPrimeBalance(0), 1e-9)
    }

    // ---- 4. Options ------------------------------------------------------------

    @Test
    fun `defaults are GoldenCheetah's shipped fallbacks`() {
        val defaults = WPrimeBalanceOptions()
        assertEquals(EngineConstants.DEFAULT_CRITICAL_POWER_W, defaults.criticalPowerW, 1e-12)
        assertEquals(EngineConstants.DEFAULT_W_PRIME_J, defaults.wPrimeJ, 1e-12)
        assertEquals(250.0, defaults.criticalPowerW, 1e-12)
        assertEquals(20_000.0, defaults.wPrimeJ, 1e-12)
        assertTrue(defaults.enabled)
    }

    @Test
    fun `non-positive parameters are rejected`() {
        assertFailsWith<IllegalArgumentException> { WPrimeBalanceOptions(criticalPowerW = 0.0) }
        assertFailsWith<IllegalArgumentException> { WPrimeBalanceOptions(wPrimeJ = -1.0) }
    }

    // ---- 5. Through the pipeline ----------------------------------------------

    /** A 5-point ~50 m-spaced path in France, the `EnhancerTest` fixture shape. */
    private fun samplePath(): Path {
        val lonDeg = doubleArrayOf(2.0000, 2.0007, 2.0014, 2.0021, 2.0028)
        val eleM = doubleArrayOf(100.0, 102.0, 104.0, 103.0, 101.0)
        val p = Path(lonDeg.size)
        for (i in lonDeg.indices) {
            p.setLatitude(i, 48.0 * MathConstants.DEG_TO_RAD)
            p.setLongitude(i, lonDeg[i] * MathConstants.DEG_TO_RAD)
            p.setElevation(i, eleM[i])
            p.setTime(i, i * 5_000.0)
        }
        p.computeDerivedData()
        return p
    }

    @Test
    fun `enhance populates the field and leaves every other field untouched`() =
        runTest {
            val options = EnhanceOptions(fixElevation = false)
            val withPass = Enhancer.enhanceCourseDefault(samplePath(), options = options)
            val without =
                Enhancer.enhanceCourseDefault(
                    samplePath(),
                    options = options.copy(wPrimeBalance = WPrimeBalanceOptions(enabled = false)),
                )

            assertEquals(without.size, withPass.size, "the pass must not change the point count")
            for (field in PointField.entries) {
                if (field == PointField.W_PRIME_BALANCE) continue
                for (i in 0 until withPass.size) {
                    assertEquals(
                        without.get(i, field),
                        withPass.get(i, field),
                        0.0,
                        "field ${field.prop} differs at $i",
                    )
                }
            }

            // Disabled : the slot keeps its zero initialisation. Enabled : a real, bounded trace.
            for (i in 0 until without.size) assertEquals(0.0, without.wPrimeBalance(i), 0.0)
            assertEquals(EngineConstants.DEFAULT_W_PRIME_J, withPass.wPrimeBalance(0), 1e-9)
            for (i in 0 until withPass.size) {
                val w = withPass.wPrimeBalance(i)
                assertTrue(w.isFinite(), "non-finite W′bal at $i")
                assertTrue(w in 0.0..EngineConstants.DEFAULT_W_PRIME_J, "W′bal out of range at $i : $w")
            }
        }

    @Test
    fun `a ride under CP never depletes the reserve`() =
        runTest {
            // The default provider rides at 280 W, above the 250 W default CP — so raise CP well
            // clear of it and the reserve must stay full for the whole ride.
            val out =
                Enhancer.enhanceCourseDefault(
                    samplePath(),
                    options =
                        EnhanceOptions(
                            fixElevation = false,
                            wPrimeBalance = WPrimeBalanceOptions(criticalPowerW = 1_000.0),
                        ),
                )
            for (i in 0 until out.size) {
                assertEquals(EngineConstants.DEFAULT_W_PRIME_J, out.wPrimeBalance(i), 1e-6)
            }
        }

    // ---- 6. Agreement with GoldenCheetah's Euler recursion at 1 Hz --------------

    @Test
    fun `matches GoldenCheetah's 1 Hz recursion to first order`() {
        // GC : if (P < CP) W += (CP − P)·(W′ − W)/W′ else W += (CP − P)
        var gc = wPrime
        var ours = wPrime
        val profile = List(120) { if (it % 2 == 0) 400.0 else 150.0 }
        for (p in profile) {
            gc += if (p < cp) (cp - p) * (wPrime - gc) / wPrime else (cp - p)
            ours = WPrimeBalanceComputer.step(ours, p, cp, wPrime, 1.0)
        }
        // Euler vs closed form over 60 recovery seconds : same trajectory, sub-percent apart.
        assertEquals(gc, ours, 0.01 * wPrime)
        // `1 − exp(−x) < x`, so the exact form credits slightly less recovery per step than Euler.
        assertTrue(ours <= gc, "exact recovery cannot exceed the Euler approximation")
    }
}
