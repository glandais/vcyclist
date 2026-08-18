package io.github.glandais.engine.path

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the properties [ElevationGain] is worth having, not just its arithmetic. Each test names one
 * of the claims in `docs/guides/elevation.md` / ledger R27.
 */
class ElevationGainTest {
    // ---- Helpers ------------------------------------------------------------

    /** Evenly spaced profile at [spacingM] metres. */
    private fun profile(
        spacingM: Double,
        vararg elevation: Double,
    ): Pair<DoubleArray, DoubleArray> {
        val d = DoubleArray(elevation.size) { it * spacingM }
        return d to elevation.copyOf()
    }

    private fun gainOf(
        spacingM: Double,
        thresholdM: Double,
        vararg elevation: Double,
    ): ElevationGainResult {
        val (d, e) = profile(spacingM, *elevation)
        return ElevationGain.compute(
            d,
            e,
            ElevationGainOptions(thresholdM = thresholdM, smoothWindowM = 0.0),
        )
    }

    /** A linear ramp from [from] to [to] over [n] samples at [spacingM]. */
    private fun ramp(
        n: Int,
        from: Double,
        to: Double,
        spacingM: Double,
    ): Pair<DoubleArray, DoubleArray> {
        val d = DoubleArray(n) { it * spacingM }
        val e = DoubleArray(n) { from + (to - from) * it / (n - 1).toDouble() }
        return d to e
    }

    // ---- 1. The failure mode a per-delta filter has -------------------------

    @Test
    fun a_smooth_climb_sampled_finely_is_counted_in_full() {
        // 500 m of climb over 5 km at 2 m spacing: 0.2 m per sample, so no single delta ever
        // reaches a 3 m threshold. `if (dEle > threshold)` would report 0 here — this is the whole
        // reason the accumulator tracks turning points instead.
        val (d, e) = ramp(n = 2501, from = 100.0, to = 600.0, spacingM = 2.0)
        val r = ElevationGain.compute(d, e, ElevationGainOptions(thresholdM = 3.0, smoothWindowM = 0.0))
        assertEquals(500.0, r.gainM, 1e-9)
        assertEquals(0.0, r.lossM, 1e-9)
        assertEquals(1, r.legCount)
    }

    // ---- 2. No double counting ----------------------------------------------

    @Test
    fun a_staircase_climb_banks_one_leg_not_one_per_step() {
        // 20 rises of 1 m, each followed by a 0.5 m sub-summit dip, netting a 10 m climb. The plain
        // sum sees 20 m of ascent; the accumulator banks the single 10 m leg the rider actually
        // climbed, because no dip ever confirms a reversal.
        val e = DoubleArray(41)
        for (i in 0 until 20) {
            e[2 * i + 1] = e[2 * i] + 1.0
            e[2 * i + 2] = e[2 * i + 1] - 0.5
        }
        val d = DoubleArray(41) { it * 10.0 }
        val r = ElevationGain.compute(d, e, ElevationGainOptions(thresholdM = 3.0, smoothWindowM = 0.0))
        assertEquals(20.0, r.rawGainM, 1e-9)
        // 10.5, not 10.0: the profile's high point is the last sub-summit, and the 0.5 m dip after
        // it never confirms a reversal, so the open leg flushes to the peak.
        assertEquals(10.5, r.gainM, 1e-9)
        assertEquals(1, r.legCount)
    }

    // ---- 3. All-or-nothing, and gain/loss stay balanced ---------------------

    @Test
    fun a_bump_of_exactly_the_threshold_counts_in_full() {
        val r = gainOf(spacingM = 10.0, thresholdM = 3.0, 0.0, 3.0, 0.0)
        assertEquals(3.0, r.gainM, 1e-9)
        assertEquals(-3.0, r.lossM, 1e-9)
    }

    @Test
    fun a_bump_just_below_the_threshold_is_dropped_with_its_matching_descent() {
        val r = gainOf(spacingM = 10.0, thresholdM = 3.0, 0.0, 2.99, 0.0)
        assertEquals(0.0, r.gainM, 1e-9)
        assertEquals(0.0, r.lossM, 1e-9)
        // The control still sees it, which is what makes the pair a useful diagnostic.
        assertEquals(2.99, r.rawGainM, 1e-9)
    }

    @Test
    fun sawtooth_below_the_threshold_contributes_nothing() {
        val e = DoubleArray(201) { if (it % 2 == 0) 100.0 else 101.0 }
        val d = DoubleArray(201) { it * 5.0 }
        val r = ElevationGain.compute(d, e, ElevationGainOptions(thresholdM = 3.0, smoothWindowM = 0.0))
        assertEquals(0.0, r.gainM, 1e-9)
        assertEquals(0.0, r.lossM, 1e-9)
        assertEquals(100.0, r.rawGainM, 1e-9)
    }

    // ---- 4. Closure ----------------------------------------------------------

    @Test
    fun gain_plus_loss_telescopes_to_the_net_change() {
        val threshold = 3.0
        val e = doubleArrayOf(100.0, 180.0, 120.0, 260.0, 200.0, 240.0)
        val d = DoubleArray(e.size) { it * 500.0 }
        val r = ElevationGain.compute(d, e, ElevationGainOptions(thresholdM = threshold, smoothWindowM = 0.0))
        val net = e.last() - e.first()
        assertTrue(
            abs(r.gainM + r.lossM - net) <= threshold,
            "gain ${r.gainM} + loss ${r.lossM} should telescope to $net within $threshold",
        )
    }

    @Test
    fun a_closed_loop_nets_to_zero() {
        val e = doubleArrayOf(0.0, 50.0, 10.0, 90.0, 30.0, 0.0)
        val d = DoubleArray(e.size) { it * 400.0 }
        val r = ElevationGain.compute(d, e, ElevationGainOptions(thresholdM = 3.0, smoothWindowM = 0.0))
        assertEquals(0.0, r.gainM + r.lossM, 3.0)
        assertEquals(130.0, r.gainM, 1e-9)
        assertEquals(-130.0, r.lossM, 1e-9)
    }

    // ---- 5. Resample invariance ---------------------------------------------

    @Test
    fun the_same_terrain_at_2m_and_at_10m_spacing_agree() {
        // One 300 m climb then a 200 m descent, sampled 5x apart. The accumulator depends only on
        // turning points, so densifying must not move the answer.
        fun build(spacingM: Double): ElevationGainResult {
            val up = (5000.0 / spacingM).toInt()
            val down = (3000.0 / spacingM).toInt()
            val n = up + down + 1
            val d = DoubleArray(n) { it * spacingM }
            val e =
                DoubleArray(n) { i ->
                    if (i <= up) 300.0 * i / up else 300.0 - 200.0 * (i - up) / down
                }
            return ElevationGain.compute(d, e, ElevationGainOptions(thresholdM = 3.0, smoothWindowM = 0.0))
        }
        val fine = build(2.0)
        val coarse = build(10.0)
        assertEquals(coarse.gainM, fine.gainM, 0.01 * coarse.gainM)
        assertEquals(coarse.lossM, fine.lossM, 0.01 * abs(coarse.lossM))
    }

    // ---- 6. Monotone in the threshold ---------------------------------------

    @Test
    fun a_larger_threshold_never_reports_more_gain() {
        val e = DoubleArray(500) { i -> 100.0 + 40.0 * kotlin.math.sin(i / 7.0) + 0.05 * i }
        val d = DoubleArray(500) { it * 20.0 }
        var previous = Double.MAX_VALUE
        for (t in listOf(0.0, 1.0, 2.0, 3.0, 5.0, 10.0, 25.0)) {
            val g = ElevationGain.compute(d, e, ElevationGainOptions(thresholdM = t, smoothWindowM = 0.0)).gainM
            assertTrue(g <= previous + 1e-9, "gain at threshold $t ($g) exceeded the previous ($previous)")
            previous = g
        }
    }

    // ---- 7. The RAW preset is the control -----------------------------------

    @Test
    fun the_raw_preset_reproduces_the_plain_sum_of_positive_deltas() {
        val path = Path(5)
        val elevations = doubleArrayOf(100.0, 150.0, 120.0, 200.0, 180.0)
        for (i in 0 until 5) {
            path.setLatitude(i, 0.0)
            path.setLongitude(i, i * 0.001)
            path.setElevation(i, elevations[i])
        }
        path.computeDerivedData()

        val r = ElevationGain.compute(path, ElevationGainOptions.RAW)
        assertEquals(path.elevationGain, r.gainM, 1e-9)
        assertEquals(path.elevationLoss, r.lossM, 1e-9)
        assertEquals(path.elevationGain, r.rawGainM, 1e-9)
    }

    // ---- 8. Sign convention and degenerate inputs ---------------------------

    @Test
    fun loss_is_negative_and_gain_is_positive() {
        val r = gainOf(spacingM = 100.0, thresholdM = 3.0, 500.0, 400.0, 450.0)
        assertTrue(r.gainM >= 0.0, "gain ${r.gainM} must be >= 0")
        assertTrue(r.lossM <= 0.0, "loss ${r.lossM} must be <= 0")
    }

    @Test
    fun a_descent_only_profile_reports_no_gain() {
        val (d, e) = ramp(n = 501, from = 1000.0, to = 200.0, spacingM = 10.0)
        val r = ElevationGain.compute(d, e, ElevationGainOptions(thresholdM = 3.0, smoothWindowM = 0.0))
        assertEquals(0.0, r.gainM, 1e-9)
        assertEquals(-800.0, r.lossM, 1e-9)
    }

    @Test
    fun a_route_that_opens_with_a_dip_does_not_book_the_dip_as_a_climb() {
        val r = gainOf(spacingM = 100.0, thresholdM = 3.0, 100.0, 80.0, 90.0)
        assertEquals(-20.0, r.lossM, 1e-9)
        assertEquals(10.0, r.gainM, 1e-9)
    }

    @Test
    fun paths_shorter_than_two_points_are_empty() {
        assertEquals(0.0, ElevationGain.compute(Path(0)).gainM)
        assertEquals(0.0, ElevationGain.compute(Path(1)).gainM)
    }

    // ---- 9. Options ----------------------------------------------------------

    @Test
    fun negative_options_are_rejected() {
        assertFailsWith<IllegalArgumentException> { ElevationGainOptions(thresholdM = -1.0) }
        assertFailsWith<IllegalArgumentException> { ElevationGainOptions(smoothWindowM = -1.0) }
    }

    @Test
    fun a_preset_supplies_both_knobs_and_either_can_be_overridden() {
        val dem = ElevationGainOptions.of(ElevationGainPreset.DEM)
        assertEquals(3.0, dem.thresholdM)
        assertEquals(30.0, dem.smoothWindowM)

        val tighter = ElevationGainOptions(preset = ElevationGainPreset.DEM, thresholdM = 1.0)
        assertEquals(1.0, tighter.thresholdM)
        assertEquals(30.0, tighter.smoothWindowM, "overriding the threshold must not reset the window")
    }

    @Test
    fun preset_ids_round_trip_and_unknown_ids_name_the_alternatives() {
        for (preset in ElevationGainPreset.entries) {
            assertEquals(preset, ElevationGainPreset.byId(preset.id))
            assertEquals(preset, ElevationGainPreset.byId(preset.name))
        }
        assertEquals(ElevationGainPreset.DEM, ElevationGainPreset.byId("DEM"))
        val failure = assertFailsWith<IllegalArgumentException> { ElevationGainPreset.byId("strava") }
        assertTrue(failure.message!!.contains("barometric"), "message should list the options: ${failure.message}")
    }

    @Test
    fun strava_and_goldencheetah_thresholds_are_the_documented_ones() {
        assertEquals(2.0, ElevationGainPreset.BAROMETRIC.thresholdM)
        assertEquals(10.0, ElevationGainPreset.GPS.thresholdM)
        assertEquals(3.0, ElevationGainPreset.DEM.thresholdM)
        assertEquals(0.0, ElevationGainPreset.RAW.thresholdM)
        assertEquals(0.0, ElevationGainPreset.RAW.smoothWindowM)
    }

    // ---- 10. The cached scalars on Path --------------------------------------

    private fun hillPath(): Path {
        val elevations = doubleArrayOf(100.0, 150.0, 120.0, 200.0, 180.0)
        val path = Path(elevations.size)
        for (i in elevations.indices) {
            path.setLatitude(i, 0.0)
            path.setLongitude(i, i * 0.001)
            path.setElevation(i, elevations[i])
        }
        path.computeDerivedData()
        return path
    }

    @Test
    fun the_filtered_figures_are_absent_until_the_stage_runs() {
        val path = hillPath()
        assertTrue(path.elevationGainFiltered.isNaN())
        assertTrue(path.elevationLossFiltered.isNaN())
        assertEquals(path.elevationGain, path.reportedElevationGain)
        assertEquals(path.elevationLoss, path.reportedElevationLoss)

        ElevationGain.annotate(path, ElevationGainOptions(thresholdM = 3.0, smoothWindowM = 0.0))
        assertEquals(130.0, path.elevationGainFiltered, 1e-9)
        assertEquals(130.0, path.reportedElevationGain, 1e-9)
    }

    @Test
    fun recomputing_derived_data_invalidates_the_filtered_figures() {
        // Anything that rewrites elevations makes the cached figure a lie, and "not computed" must
        // not be readable as "flat".
        val path = hillPath()
        ElevationGain.annotate(path)
        assertFalse(path.elevationGainFiltered.isNaN())

        path.setElevation(2, 90.0)
        path.computeDerivedData()
        assertTrue(path.elevationGainFiltered.isNaN(), "the cached gain survived a recompute")
        assertEquals(path.elevationGain, path.reportedElevationGain)
    }

    @Test
    fun copy_carries_the_filtered_figures() {
        val path = hillPath()
        ElevationGain.annotate(path)
        val clone = path.copy()
        assertEquals(path.elevationGainFiltered, clone.elevationGainFiltered)
        assertEquals(path.elevationLossFiltered, clone.elevationLossFiltered)
    }

    // ---- 11. The smoothing is the dominant knob -----------------------------

    @Test
    fun smoothing_removes_noise_that_no_dead_band_can_reach() {
        // A 100 m climb over 2 km with a 20 m-wavelength, 4 m-amplitude ripple on it. The ripple
        // exceeds any usable dead band, so only the kernel can remove it — this is the
        // `sports-tracker.gpx` situation in miniature.
        val n = 1001
        val d = DoubleArray(n) { it * 2.0 }
        val e = DoubleArray(n) { i -> 100.0 * i / (n - 1) + 4.0 * kotlin.math.sin(d[i] * 2.0 * kotlin.math.PI / 20.0) }

        val banded = ElevationGain.compute(d, e, ElevationGainOptions(thresholdM = 3.0, smoothWindowM = 0.0))
        val smoothed = ElevationGain.compute(d, e, ElevationGainOptions(thresholdM = 3.0, smoothWindowM = 30.0))

        assertTrue(banded.gainM > 500.0, "the dead band alone should leave the ripple in: ${banded.gainM}")
        assertEquals(100.0, smoothed.gainM, 5.0)
    }
}
