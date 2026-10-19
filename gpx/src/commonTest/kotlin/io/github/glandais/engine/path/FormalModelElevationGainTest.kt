package io.github.glandais.engine.path

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Counterexamples found by model checking [ElevationGain] (Lean 4 executable model of the triangular
 * smoother and of the hysteresis accumulator). The tests assert the documented contracts, so they
 * fail on the current code.
 */
class FormalModelElevationGainTest {
    /**
     * `docs/guides/elevation.md`: "`ElevationSmoother` weights by `1 − d / windowSize` over path
     * distance, so the window is metric and the stage is resample-invariant", and the
     * [ElevationGain] KDoc: "This reports a figure that is stable under resampling". The kernel
     * weights *samples*, not distance, so inserting points exactly on the piecewise-linear profile
     * (same terrain) moves D+. Lean model: 40.0 m sparse vs 38.43 m dense.
     */
    @Test
    fun inserting_collinear_points_does_not_move_the_default_preset_gain() {
        // A 40 m summit over 1 km, described by its three vertices...
        val sparseD = doubleArrayOf(0.0, 500.0, 1000.0)
        val sparseE = doubleArrayOf(0.0, 40.0, 0.0)
        // ...and the very same terrain with collinear points every metre.
        val denseD = DoubleArray(1001) { it.toDouble() }
        val denseE = DoubleArray(1001) { i -> if (i <= 500) 0.08 * i else 0.08 * (1000 - i) }

        val sparse = ElevationGain.compute(sparseD, sparseE, ElevationGainOptions.DEFAULT)
        val dense = ElevationGain.compute(denseD, denseE, ElevationGainOptions.DEFAULT)

        assertEquals(
            sparse.gainM,
            dense.gainM,
            0.005 * sparse.gainM,
            "same terrain, different sampling density: sparse ${sparse.gainM} m vs dense ${dense.gainM} m",
        )
    }

    /**
     * Closure is only within 2·threshold, not the documented one threshold: an opening
     * sub-threshold dip is dropped while its recovery is banked.
     */
    @Test
    fun gain_plus_loss_telescopes_to_the_net_change_within_one_threshold() {
        val threshold = 3.0
        val e = doubleArrayOf(0.0, -2.9, 100.0, 97.1)
        val d = DoubleArray(e.size) { it * 200.0 }
        val r = ElevationGain.compute(d, e, ElevationGainOptions(thresholdM = threshold, smoothWindowM = 0.0))
        val net = e.last() - e.first()
        assertTrue(
            abs(r.gainM + r.lossM - net) <= threshold,
            "gain ${r.gainM} + loss ${r.lossM} = ${r.gainM + r.lossM} should telescope to net $net " +
                "within one threshold ($threshold), off by ${abs(r.gainM + r.lossM - net)}",
        )
    }

    // ---- cumulativeGain ----------------------------------------------------

    private fun pathOf(
        elevations: DoubleArray,
        stepM: Double = 10.0,
    ): Path {
        val p = Path(elevations.size)
        for (i in elevations.indices) {
            p.setElevation(i, elevations[i])
            p.setDistance(i, i * stepM)
        }
        return p
    }

    private fun noSmoothing(thresholdM: Double = 3.0) = ElevationGainOptions(thresholdM = thresholdM, smoothWindowM = 0.0)

    private fun assertInvariants(
        path: Path,
        options: ElevationGainOptions,
    ): DoubleArray {
        val c = ElevationGain.cumulativeGain(path, options)
        assertEquals(path.size, c.size)
        assertEquals(0.0, c[0])
        for (i in 1 until c.size) assertTrue(c[i] >= c[i - 1], "not non-decreasing at $i: ${c[i - 1]} -> ${c[i]}")
        assertEquals(ElevationGain.compute(path, options).gainM, c.last(), 1e-9)
        return c
    }

    @Test
    fun cumulativeGain_random_profiles_end_on_gainM_and_never_decrease() {
        val rnd = Random(42)
        repeat(300) { trial ->
            val n = 2 + rnd.nextInt(300)
            var e = 0.0
            val elevations =
                DoubleArray(n) {
                    e += (rnd.nextDouble() - 0.5) * 2 * (1 + trial % 7)
                    e
                }
            val options =
                ElevationGainOptions(
                    thresholdM = if (trial % 10 == 0) 0.0 else 0.5 + rnd.nextDouble() * 6,
                    smoothWindowM = if (trial % 2 == 0) 0.0 else 30.0,
                )
            assertInvariants(pathOf(elevations), options)
        }
    }

    @Test
    fun cumulativeGain_telescopes_over_counted_legs() {
        // Two counted climbs of 10 and 20 m separated by a counted descent.
        val c = assertInvariants(pathOf(doubleArrayOf(0.0, 5.0, 10.0, 0.0, 10.0, 20.0, 30.0)), noSmoothing())
        assertContentEquals(doubleArrayOf(0.0, 5.0, 10.0, 10.0, 20.0, 30.0, 40.0), c)
    }

    @Test
    fun cumulativeGain_opening_climb_leg_counts_from_the_first_point() {
        // Sets off upward by 2 m (below the 3 m threshold), then drops 10 m: opening leg [0, 1].
        val c = assertInvariants(pathOf(doubleArrayOf(0.0, 2.0, -8.0, -8.0, 2.0)), noSmoothing())
        assertEquals(2.0, c[1], 1e-12)
        assertEquals(2.0, c[2], 1e-12)
        assertEquals(12.0, c[4], 1e-12)
    }

    @Test
    fun cumulativeGain_opening_descent_adds_nothing_then_climb_counts() {
        val c = assertInvariants(pathOf(doubleArrayOf(0.0, -2.0, -10.0, 0.0, 5.0)), noSmoothing())
        assertEquals(0.0, c[2], 1e-12)
        assertEquals(15.0, c.last(), 1e-12)
    }

    @Test
    fun cumulativeGain_last_leg_is_flushed() {
        val c = assertInvariants(pathOf(doubleArrayOf(0.0, 10.0, 0.0, 1.0, 2.0, 8.0)), noSmoothing())
        assertEquals(10.0 + 8.0, c.last(), 1e-12)
    }

    @Test
    fun cumulativeGain_flat_profile_is_all_zeros() {
        val c = assertInvariants(pathOf(DoubleArray(20) { 100.0 }), ElevationGainOptions.DEFAULT)
        assertTrue(c.all { it == 0.0 })
    }

    @Test
    fun cumulativeGain_bump_of_exactly_the_threshold_counts_and_just_under_does_not() {
        val atThreshold = assertInvariants(pathOf(doubleArrayOf(0.0, 0.0, 3.0, 0.0, 0.0, 0.0)), noSmoothing(3.0))
        assertEquals(3.0, atThreshold.last(), 1e-12)
        // Middle bump, so it is neither the opening nor the last leg.
        val under = assertInvariants(pathOf(doubleArrayOf(0.0, 10.0, 7.5, 10.0, 0.0, 0.0)), noSmoothing(3.0))
        assertEquals(10.0, under.last(), 1e-12)
        val bumpUnder = pathOf(doubleArrayOf(10.0, 10.0, 12.9, 10.0, 10.0, 10.0))
        val c = assertInvariants(bumpUnder, noSmoothing(3.0))
        assertTrue(c.all { it == 0.0 })
    }

    @Test
    fun cumulativeGain_opening_dip_below_threshold_then_climb_counts_from_the_dip() {
        // Branch iHi > iLo with lo < h[0]: the 2 m dip is the opening loss, the climb counts from it.
        val c = assertInvariants(pathOf(doubleArrayOf(0.0, -2.0, 8.0, 8.0)), noSmoothing())
        assertContentEquals(doubleArrayOf(0.0, 0.0, 10.0, 10.0), c)
    }

    @Test
    fun cumulativeGain_dip_of_exactly_the_threshold_in_the_middle_of_a_climb_is_counted() {
        val c = assertInvariants(pathOf(doubleArrayOf(0.0, 10.0, 7.0, 10.0, 0.0)), noSmoothing(3.0))
        assertEquals(13.0, c.last(), 1e-12)
    }

    @Test
    fun cumulativeGain_never_rises_faster_than_the_profile_and_only_where_it_rises() {
        // Pointwise, not just at the end: with no smoothing h is the input, a step of c is at most
        // the step of h (equal on the running maximum, smaller when catching up after a dip) and
        // is zero wherever h does not go up. A wrong intermediate value that still ends on gainM
        // and stays non-decreasing breaks one of these.
        val rnd = Random(7)
        repeat(300) {
            val n = 3 + rnd.nextInt(120)
            var e = 0.0
            val h =
                DoubleArray(n) {
                    e += (rnd.nextDouble() - 0.5) * 8
                    e
                }
            val c = assertInvariants(pathOf(h), noSmoothing(1.0 + rnd.nextDouble() * 5))
            for (i in 1 until n) {
                val step = c[i] - c[i - 1]
                assertTrue(step <= maxOf(0.0, h[i] - h[i - 1]) + 1e-9, "c rises by $step at $i, h by ${h[i] - h[i - 1]}")
            }
        }
    }
}
