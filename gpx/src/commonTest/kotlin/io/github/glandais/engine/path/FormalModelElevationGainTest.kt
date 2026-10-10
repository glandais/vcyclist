package io.github.glandais.engine.path

import kotlin.math.abs
import kotlin.test.Test
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
}
