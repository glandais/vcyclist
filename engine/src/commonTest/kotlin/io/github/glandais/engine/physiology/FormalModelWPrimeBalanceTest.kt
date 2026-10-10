package io.github.glandais.engine.physiology

import io.github.glandais.engine.WPrimeBalanceOptions
import io.github.glandais.engine.path.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins a finding of the Lean model `WPrime.lean` (B7): a non-finite W′ is accepted by
 * [WPrimeBalanceOptions] and turns the W′bal trace into NaN after the first sub-CP interval.
 */
class FormalModelWPrimeBalanceTest {
    @Test
    fun a_non_finite_w_prime_never_yields_a_nan_balance() {
        val options =
            try {
                WPrimeBalanceOptions(criticalPowerW = 250.0, wPrimeJ = Double.POSITIVE_INFINITY)
            } catch (_: IllegalArgumentException) {
                return // rejected at construction, as ElevationGainOptions does: contract met
            }
        // 0 W then 100 W, both below CP, one second apart.
        val path =
            Path(3).apply {
                setTime(0, 0.0)
                setTime(1, 1000.0)
                setTime(2, 2000.0)
                setPComputedPower(0, 0.0)
                setPComputedPower(1, 100.0)
                setPComputedPower(2, 100.0)
            }
        WPrimeBalanceComputer.compute(path, options)
        for (i in 0 until path.size) {
            assertTrue(
                !path.wPrimeBalance(i).isNaN(),
                "wPrimeBalance($i) is NaN with wPrimeJ = +Infinity accepted by the options",
            )
        }
    }
}
