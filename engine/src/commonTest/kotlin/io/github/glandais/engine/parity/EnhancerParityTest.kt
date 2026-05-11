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
 * - elevation gain/loss : ±1 m (Terrarium tile resolution)
 *
 * See `docs/parity.md` for the rationale.
 */
class EnhancerParityTest {
    // ---------------------------------------------------------------------- Helpers

    /**
     * Parse [gpxXml] and shift the timestamps so that `time(0) == 0`. This works around an
     * existing pipeline quirk : `VirtualizeService` rewrites `time(0..n-2)` from `t=0` but
     * leaves `time(n-1)` at its raw GPX value (epoch ms). When the source uses real epoch
     * times, `PointPerSecond` then tries to expand ~1.7 billion 1 Hz buckets between
     * `time(n-2)` and `time(n-1)` → OOM. Normalising to a 0-relative axis isolates the parity
     * check from this orthogonal bug ; see `docs/parity.md`.
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
            rel < 0.005,
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
            rel < 0.005,
            "[$label] durationMs drift ${rel * 100}% (kt=${out.durationMs}, ref=${ref.durationMs})",
        )
    }

    private fun assertElevationGainMatches(
        label: String,
        out: Path,
        ref: ParityMetrics,
    ) {
        val delta = abs(out.elevationGain - ref.totalElevationGain)
        assertTrue(
            delta < 1.0,
            "[$label] elevation gain drift $delta m (kt=${out.elevationGain}, ref=${ref.totalElevationGain})",
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
