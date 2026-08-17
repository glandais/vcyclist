package io.github.glandais.engine.parity

import io.github.glandais.engine.EnhanceOptions
import io.github.glandais.engine.Enhancer
import io.github.glandais.engine.gpx.GpxFixtures
import io.github.glandais.engine.gpx.GpxParser
import io.github.glandais.engine.gpx.firstTrackAsPath
import io.github.glandais.engine.path.Path
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **Self-referential** parity tests : freeze the Kotlin pipeline output on real GPX
 * fixtures (`SAMPLE_GPX`, `GARMIN_GPX`) as a regression baseline.
 *
 * Tolerances :
 * - distance : ±0.5 %
 * - durationMs : ±0.5 %
 * - elevation gain/loss : ±0.5 %, with a 1e-6 m absolute floor for the zero case
 *
 * The elevation band used to be an absolute ±1 m, justified by Terrarium tile resolution.
 * That justification does not apply here : these fixtures run `fixElevation = false`, so no
 * DEM is consulted and the gains are sub-metre by construction (SAMPLE gains 0.22 m). A ±1 m
 * band on a 0.22 m value asserts nothing — it let a 5.5e-03 relative drift through when the
 * physics constants were corrected, and `tools/wasi/test_engine.py`, which reads the same
 * fixture at ±0.5 % relative, is what caught it. Tightened to match.
 *
 * See `docs/parity.md` for the rationale.
 */
class EnhancerParityTest {
    private companion object {
        /** Relative band shared by every metric below. */
        const val REL_TOLERANCE = 0.005

        /**
         * Absolute floor for the elevation metrics, so a expected-zero value (GARMIN gains
         * nothing) stays assertable instead of demanding exact equality against float noise.
         */
        const val ELEVATION_FLOOR_M = 1e-6
    }

    // ---------------------------------------------------------------------- Helpers

    /**
     * Parse [gpxXml] and shift the timestamps so that `time(0) == 0`. Historically this
     * worked around a `VirtualizeService` bug where the last point kept the raw GPX epoch ;
     * the bug was fixed in task 29 but the normalisation is kept here for parity stability
     * (output durations now reflect only the simulated ride time, independent of the source
     * epoch offset).
     */
    private fun parsePathNormalized(gpxXml: String): Path {
        val raw = GpxParser.parse(gpxXml).firstTrackAsPath()
        if (raw.size == 0) return raw
        val t0 = raw.time(0)
        for (i in 0 until raw.size) {
            raw.setTime(i, raw.time(i) - t0)
        }
        raw.computeDerivedData()
        return raw
    }

    private suspend fun runPipeline(gpxXml: String): Path {
        val path = parsePathNormalized(gpxXml)
        return Enhancer.enhanceCourseDefault(
            path,
            elevationProvider = null,
            options = EnhanceOptions.DEFAULT.copy(fixElevation = false),
        )
    }

    private fun sanityChecks(
        label: String,
        out: Path,
        sourceDistance: Double,
        sourceGain: Double,
    ) {
        assertTrue(out.totalDistance > 0.0, "[$label] totalDistance not positive : ${out.totalDistance}")
        assertTrue(out.size > 0, "[$label] empty path : ${out.size}")
        assertTrue(out.durationMs > 0.0, "[$label] durationMs not positive : ${out.durationMs}")
        // virtualization can drift the simulated distance vs the raw input ; allow a generous band.
        val ratio = out.totalDistance / sourceDistance
        assertTrue(
            ratio in 0.5..2.0,
            "[$label] distance drift out of band : kt=${out.totalDistance}, src=$sourceDistance, ratio=$ratio",
        )
        // elevation gain after smoothing : ±50 % of raw source gain (smoother removes spikes).
        val gainDelta = abs(out.elevationGain - sourceGain)
        val gainBand = maxOf(sourceGain * 0.5, 5.0)
        assertTrue(
            gainDelta <= gainBand,
            "[$label] elevation gain drift : kt=${out.elevationGain}, src=$sourceGain (Δ=$gainDelta, band=$gainBand)",
        )
        // No NaN on the main outputs.
        for (i in 0 until out.size) {
            assertTrue(out.latitude(i).isFinite(), "[$label] NaN latitude at $i")
            assertTrue(out.longitude(i).isFinite(), "[$label] NaN longitude at $i")
            assertTrue(out.elevation(i).isFinite(), "[$label] NaN elevation at $i")
            assertTrue(out.time(i).isFinite(), "[$label] NaN time at $i")
        }
    }

    private fun assertDistanceMatches(
        label: String,
        out: Path,
        ref: ParityMetrics,
    ) {
        val rel = abs(out.totalDistance - ref.totalDistance) / ref.totalDistance
        assertTrue(
            rel < REL_TOLERANCE,
            "[$label] totalDistance drift ${rel * 100}% (kt=${out.totalDistance}, ref=${ref.totalDistance})",
        )
    }

    private fun assertDurationMatches(
        label: String,
        out: Path,
        ref: ParityMetrics,
    ) {
        val rel = abs(out.durationMs - ref.durationMs) / ref.durationMs
        assertTrue(
            rel < REL_TOLERANCE,
            "[$label] durationMs drift ${rel * 100}% (kt=${out.durationMs}, ref=${ref.durationMs})",
        )
    }

    private fun assertElevationGainMatches(
        label: String,
        out: Path,
        ref: ParityMetrics,
    ) = assertElevationMatches(label, "gain", out.elevationGain, ref.totalElevationGain)

    private fun assertElevationLossMatches(
        label: String,
        out: Path,
        ref: ParityMetrics,
    ) = assertElevationMatches(label, "loss", out.elevationLoss, ref.totalElevationLoss)

    /** ±0.5 % relative, with an absolute floor so an expected 0.0 stays assertable. */
    private fun assertElevationMatches(
        label: String,
        what: String,
        actual: Double,
        expected: Double,
    ) {
        val delta = abs(actual - expected)
        val allowed = maxOf(abs(expected) * REL_TOLERANCE, ELEVATION_FLOOR_M)
        assertTrue(
            delta <= allowed,
            "[$label] elevation $what drift $delta m > $allowed m (kt=$actual, ref=$expected)",
        )
    }

    // ----------------------------------------------------------------------- SAMPLE

    @Test
    fun sample_pipeline_sanity_checks() =
        runTest {
            val srcPath = parsePathNormalized(GpxFixtures.SAMPLE_GPX)
            val out = runPipeline(GpxFixtures.SAMPLE_GPX)
            sanityChecks("sample", out, srcPath.totalDistance, srcPath.elevationGain)
        }

    @Test
    fun sample_total_distance_within_tolerance() =
        runTest {
            val out = runPipeline(GpxFixtures.SAMPLE_GPX)
            assertDistanceMatches("sample", out, ParityFixtures.SAMPLE)
        }

    @Test
    fun sample_duration_within_tolerance() =
        runTest {
            val out = runPipeline(GpxFixtures.SAMPLE_GPX)
            assertDurationMatches("sample", out, ParityFixtures.SAMPLE)
        }

    @Test
    fun sample_elevation_gain_within_tolerance() =
        runTest {
            val out = runPipeline(GpxFixtures.SAMPLE_GPX)
            assertElevationGainMatches("sample", out, ParityFixtures.SAMPLE)
        }

    @Test
    fun sample_elevation_loss_within_tolerance() =
        runTest {
            val out = runPipeline(GpxFixtures.SAMPLE_GPX)
            assertElevationLossMatches("sample", out, ParityFixtures.SAMPLE)
        }

    // ----------------------------------------------------------------------- GARMIN

    @Test
    fun garmin_pipeline_sanity_checks() =
        runTest {
            val srcPath = parsePathNormalized(GpxFixtures.GARMIN_GPX)
            val out = runPipeline(GpxFixtures.GARMIN_GPX)
            sanityChecks("garmin", out, srcPath.totalDistance, srcPath.elevationGain)
        }

    @Test
    fun garmin_total_distance_within_tolerance() =
        runTest {
            val out = runPipeline(GpxFixtures.GARMIN_GPX)
            assertDistanceMatches("garmin", out, ParityFixtures.GARMIN)
        }

    @Test
    fun garmin_duration_within_tolerance() =
        runTest {
            val out = runPipeline(GpxFixtures.GARMIN_GPX)
            assertDurationMatches("garmin", out, ParityFixtures.GARMIN)
        }

    @Test
    fun garmin_elevation_gain_within_tolerance() =
        runTest {
            val out = runPipeline(GpxFixtures.GARMIN_GPX)
            assertElevationGainMatches("garmin", out, ParityFixtures.GARMIN)
        }

    @Test
    fun garmin_elevation_loss_within_tolerance() =
        runTest {
            val out = runPipeline(GpxFixtures.GARMIN_GPX)
            assertElevationLossMatches("garmin", out, ParityFixtures.GARMIN)
        }

    // ----------------------------------------------------- Diagnostics (one-shot)

    /**
     * Prints the current pipeline output. Run manually when refreshing
     * [ParityFixtures] — the assertion is intentionally trivial so the test does not
     * regress when values change. Not part of the parity assertions.
     */
    @Test
    fun printMeasured() =
        runTest {
            for ((name, xml) in listOf("SAMPLE" to GpxFixtures.SAMPLE_GPX, "GARMIN" to GpxFixtures.GARMIN_GPX)) {
                val out = runPipeline(xml)
                println(
                    "PARITY[$name] totalDistance=${out.totalDistance} " +
                        "gain=${out.elevationGain} loss=${out.elevationLoss} " +
                        "size=${out.size} durationMs=${out.durationMs}",
                )
            }
            assertTrue(true)
        }
}
