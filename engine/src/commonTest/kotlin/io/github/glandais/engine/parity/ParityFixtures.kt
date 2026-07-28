package io.github.glandais.engine.parity

/**
 * Reference metrics for `Enhancer.enhanceCourseDefault` outputs. Used by
 * [EnhancerParityTest] as a regression baseline.
 *
 * ## Source
 *
 * **TS-corroborated** (2026-07, see `docs/parity.md`) : the asserted values are still the
 * Kotlin pipeline's own output, but each one has been measured against the TypeScript
 * reference on identical input, and every gap is quantified and explained below.
 *
 * The values are deliberately **not** replaced by the TS numbers. The TS reference carries
 * two defects the Kotlin port fixes on purpose :
 *
 * 1. `VirtualizeService.ts` defaults its simulation clock to `new Date().getTime()`, so its
 *    output is not reproducible and its time axis loses ~7 significant digits (float64 ULP
 *    is 2.44e-4 ms at 1.785e12, versus 1.16e-10 ms starting from 0). Since `virtual-cyclist`
 *    1.3.0 a caller can override it via `EnhanceOptions.startTime`, but the default is
 *    unchanged, so the defect still applies to the reference's own output.
 * 2. TS does not simulate the last point, emitting N-1 points on all 7 sample GPX files.
 *
 * Adopting the TS numbers would bake those bugs into this suite. On fixtures this short a
 * single missing ~1.2 m segment is a large *relative* error — 7.9 % of `GARMIN_GPX`'s 14 m.
 *
 * Everything upstream of `VirtualizeService` matches the TS reference at ULP level on all
 * 7 sample traces, with identical point counts ; the unit-level sweep is 229/255
 * bit-identical with nothing beyond 1e-9. The port is faithful.
 *
 * ## Pipeline used
 *
 * ```
 * val path = GpxParser.parse(GpxFixtures.<FIXTURE>).firstTrackAsPath()
 * Enhancer.enhanceCourseDefault(
 *     path,
 *     elevationProvider = null,
 *     options = EnhanceOptions.DEFAULT.copy(fixElevation = false),
 * )
 * ```
 *
 * ## When to update
 *
 * If you intentionally change the physics or the resampling/simplification defaults,
 * re-run [EnhancerParityTest.printMeasured] (temporary diagnostics method) on a clean
 * build and copy the new values here. Add a comment explaining the deviation.
 *
 * **Measured at task 26 commit (Kotlin pipeline, JVM target, May 2026).** Cross-target
 * stability is within 0.5 % per [EnhancerParityTest] tolerances.
 *
 * **Refreshed at task 29 (May 2026)** : durations dropped (SAMPLE 89 000 → 52 000 ms ;
 * GARMIN 18 000 → 5 000 ms) because `VirtualizeService` no longer leaves `time(n - 1)`
 * at the raw source epoch — the last point is fully simulated now, so the reported
 * duration reflects only the simulated ride time instead of `simulated + epoch offset`.
 *
 * **Refreshed at task 31 (May 2026, Phase 2bis)** : `PointPerDistance` is now wired into
 * `Enhancer` (pre-fix densify at 30 m max, post-fix refine at 1-2 m). On the short fixtures
 * this densifies the 7-trkpt `SAMPLE_GPX` (~70 m mean gap → ~210 segments of ~2 m) before
 * physics. Distance/gain/loss/duration shift slightly (denser MaxSpeedComputer + Virtualize
 * grid), but Douglas-Peucker still collapses to 3 (SAMPLE) / 2 (GARMIN) points. SAMPLE
 * durationMs : 52 000 → 49 000 ms ; SAMPLE gain : 0.268 → 0.219 m ; GARMIN loss : -0.0086
 * → -0.0048 m.
 */
data class ParityMetrics(
    val totalDistance: Double,
    val totalElevationGain: Double,
    val totalElevationLoss: Double,
    val pointCount: Int,
    val durationMs: Double,
)

object ParityFixtures {
    /**
     * Measured for `GpxFixtures.SAMPLE_GPX` (étape du Tour 2025 excerpt, 7 trkpts spanning
     * ~89 s of riding). Output is simplified down to 3 points by Douglas-Peucker.
     *
     * Measured Kotlin pipeline at task 26 commit.
     */
    val SAMPLE =
        ParityMetrics(
            // updated for Phase 2bis (task 31): PointPerDistance integrated.
            //
            // TS reference measured 2026-07-27 (clock-pinned), and the gap to each value:
            //   totalDistance  418.20859360948475   rel 4.4e-03  (one missing ~1.8 m segment)
            //   durationMs      48000               rel 2.0e-02  (one missing 1 Hz sample)
            //   elevationGain    0.21591593782602558 rel 1.4e-02 (one missing segment)
            //   elevationLoss   -0.3083313825662799  rel 9.8e-12 (ULP — the smoother agrees)
            totalDistance = 420.04525064910683,
            totalElevationGain = 0.2189461508746149,
            totalElevationLoss = -0.3083313825632672,
            pointCount = 3,
            durationMs = 49_000.0,
        )

    /**
     * Measured for `GpxFixtures.GARMIN_GPX` (3 trkpts, Garmin-style extensions, ~18 s span).
     * Trivially short ride : output collapses to 2 points (start + end) after simplify.
     *
     * Measured Kotlin pipeline at task 26 commit.
     */
    val GARMIN =
        ParityMetrics(
            // updated for Phase 2bis (task 31): PointPerDistance integrated.
            //
            // TS reference measured 2026-07-27 (clock-pinned), and the gap to each value:
            //   totalDistance   13.75769637229516     rel 7.9e-02  (one missing 1.17 m segment
            //                                                       — 7.9 % of a 14 m trace)
            //   durationMs       5000                 rel 0        (exact)
            //   elevationGain       0.0               rel 0        (exact)
            //   elevationLoss   -0.004688886304762718 rel 3.0e-02  (one missing segment)
            totalDistance = 14.929920010888091,
            totalElevationGain = 0.0,
            totalElevationLoss = -0.004834919456122577,
            pointCount = 2,
            durationMs = 5_000.0,
        )
}
