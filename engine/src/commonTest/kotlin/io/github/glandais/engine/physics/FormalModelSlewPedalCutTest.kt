package io.github.glandais.engine.physics

import io.github.glandais.engine.Course
import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.Cyclist
import io.github.glandais.engine.path.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Counterexample from the TLA+ model `formal/physics/SlewPedalCut.tla`: the slew limiter composed
 * with the pedal-strike cut-off of [MuscularPowerProvider], driven the way `VirtualizeService`
 * drives it (one query per point, `pointIndex` increasing, 1 s steps).
 *
 * Contract: [PowerProviderSlewLimited]'s KDoc and ledger R18 ("one emergent property worth
 * keeping") both say the cut-off drops power to zero instantly and that power then **"ramps back
 * up on the way out"** at `maxSlewWPerS`. The limiter, however, measures the next step against
 * what it *returned*, not against what was delivered: [MuscularPowerProvider] reports `0 W` through
 * `onDelivered`, the limiter only forwards it, so on the first pedalling point after the corner
 * the delivered power jumps from 0 straight back to the full target.
 */
class FormalModelSlewPedalCutTest {
    @Test
    fun power_ramps_back_up_after_a_pedal_strike_cut() {
        val n = 30
        val path =
            Path(n).apply {
                for (i in 0 until n) {
                    setElapsed(i, i.toDouble())
                    setTime(i, i * 1000.0)
                    setSpeed(i, 10.0)
                    // Points 15..19 are a 10 m radius corner at 10 m/s: tan(lean) = 1.02, far past
                    // the 20° pedalling clearance, so the pedals are up. Elsewhere: straight.
                    setRadius(i, if (i in 15..19) 10.0 else Double.NaN)
                }
            }
        val slew = PowerProviderSlewLimited(PowerProviderConstant(280.0), maxSlewWPerS = 50.0)
        val course =
            CoursePhysics(
                course = Course(path = path, cyclist = Cyclist.DEFAULT),
                rhoProvider = RhoProviderDefault,
                aeroProvider = AeroProviderConstant,
                windProvider = WindProviderNone,
                cyclistPowerProvider = slew,
            )

        val delivered = DoubleArray(n)
        for (i in 0 until n) {
            MuscularPowerProvider.powerAt(course, path, i)
            delivered[i] = path.pCyclistProvidedMuscular(i)
        }
        assertTrue(delivered[19] == 0.0, "precondition: the pedals are up in the corner")
        assertTrue(delivered[14] == 280.0, "precondition: full power before the corner")

        for (i in 1 until n) {
            val rise = delivered[i] - delivered[i - 1]
            assertTrue(
                rise <= 50.0 + 1e-9,
                "delivered power rose by $rise W in 1 s at point $i (${delivered[i - 1]} -> ${delivered[i]}); " +
                    "the slew limit promises a ramp back up at 50 W/s after the cut-off",
            )
        }
    }
}
