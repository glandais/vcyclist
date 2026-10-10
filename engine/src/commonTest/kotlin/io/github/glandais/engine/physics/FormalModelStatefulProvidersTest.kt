package io.github.glandais.engine.physics

import io.github.glandais.engine.Course
import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.Cyclist
import io.github.glandais.engine.path.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Counterexamples found by model-checking the stateful cyclist power providers (TLA+ models
 * `TerrainPacing.tla` and `CriticalPowerChain.tla`), replayed against the real classes with the call
 * pattern `VirtualizeService` uses: one query per point, `pointIndex` strictly increasing, `elapsed`
 * non-decreasing.
 */
class FormalModelStatefulProvidersTest {
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

    /** 10 m spacing, 1 s per point (10 m/s), grade chosen per point. */
    private fun ridePath(
        n: Int,
        grade: (Int) -> Double,
    ): Path =
        Path(n).apply {
            for (i in 0 until n) {
                setDistance(i, i * 10.0)
                setElapsed(i, i * 1.0)
                setTime(i, i * 1000.0)
                setGrade(i, grade(i))
                setSpeed(i, 10.0)
            }
        }

    @Test
    fun `pacing never asks for more than maxMultiplier times the target`() {
        // 5 min descent (multiplier 0.5 -> the energy account goes into credit), then a 20 % wall.
        val path = ridePath(700) { i -> if (i < 300) -0.20 else 0.20 }
        val pacing = PowerProviderTerrainPacing(PowerProviderConstant(250.0))
        val course = physics(path, pacing)
        var worst = 0.0
        var worstAt = -1
        for (i in 0 until path.size) {
            val ratio = pacing.powerAt(course, path, i) / 250.0
            if (ratio > worst) {
                worst = ratio
                worstAt = i
            }
        }
        assertTrue(
            worst <= pacing.maxMultiplier + 1e-9,
            "KDoc: 'A wall never asks for more than +30 %' — delivered/target reached $worst at point " +
                "$worstAt (multiplier ${pacing.maxMultiplier} x energy correction > 1)",
        )
    }

    @Test
    fun `pacing never drops the rider below minMultiplier times the target`() {
        // 10 min on a 20 % wall (the account goes into debt), then a descent.
        val path = ridePath(900) { i -> if (i < 600) 0.20 else -0.20 }
        val pacing = PowerProviderTerrainPacing(PowerProviderConstant(250.0))
        val course = physics(path, pacing)
        var worst = Double.MAX_VALUE
        var worstAt = -1
        for (i in 0 until path.size) {
            val ratio = pacing.powerAt(course, path, i) / 250.0
            if (ratio < worst) {
                worst = ratio
                worstAt = i
            }
        }
        assertTrue(
            worst >= pacing.minMultiplier - 1e-9,
            "KDoc: 'A descent never drops the rider below half the target' — delivered/target fell to " +
                "$worst at point $worstAt",
        )
    }

    /**
     * `CriticalPowerChain.tla`: the critical-power rider books its W′ reserve against its own
     * rationed output, which is never below CP, so the reserve cannot refill.
     */
    @Test
    fun `riding below CP refills the critical-power rider's reserve`() {
        // 300 s climbing at 6 %, then 600 s descending at -6 %, 1 s and 5 m per point.
        val climbS = 300
        val descentS = 600
        val n = climbS + descentS + 1
        val path =
            Path(n).apply {
                for (i in 0 until n) {
                    setElapsed(i, i.toDouble())
                    setDistance(i, i * 5.0)
                    setGrade(i, if (i < climbS) 0.06 else -0.06)
                }
            }
        val base = PowerProviderCriticalPower(400.0, cp, wPrime)
        val pacing = PowerProviderTerrainPacing(base)
        val course = physics(path, pacing)

        for (i in 0..climbS) pacing.powerAt(course, path, i)
        val afterClimb = base.wPrimeBalanceJ

        // The precondition covers the first 100 s of the descent only. Once the reserve refills,
        // ration() rises again (the KDoc's "raises the ceiling again") and pacing's energy credit
        // lifts the paced power back above CP late in the 600 s descent (~254 W near point 693).
        // That is the recovery working, not a defect. Requiring the whole descent below CP would
        // demand a reserve that never refills, which is the bug this test pins.
        val earlyDescentS = 100
        var maxDeliveredEarlyDescent = 0.0
        for (i in climbS + 1 until n) {
            val delivered = pacing.powerAt(course, path, i)
            if (i <= climbS + earlyDescentS) {
                maxDeliveredEarlyDescent = maxOf(maxDeliveredEarlyDescent, delivered)
            }
        }
        assertTrue(
            maxDeliveredEarlyDescent < cp,
            "precondition: the early descent is ridden below CP, got $maxDeliveredEarlyDescent W",
        )
        // KDoc: "ride below CP and the reserve refills, which raises the ceiling again".
        assertTrue(
            base.wPrimeBalanceJ > afterClimb,
            "10 min under CP must refill the reserve: $afterClimb J -> ${base.wPrimeBalanceJ} J",
        )
    }
}
