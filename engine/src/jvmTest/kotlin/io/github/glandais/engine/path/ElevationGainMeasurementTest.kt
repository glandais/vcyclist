package io.github.glandais.engine.path

import io.github.glandais.engine.EnhanceOptions
import io.github.glandais.engine.Enhancer
import io.github.glandais.engine.SimplifyPathOptions
import io.github.glandais.engine.gpx.GpxParser
import io.github.glandais.engine.gpx.firstTrackAsPath
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What cumulative ascent actually is, on the shipped fixtures, at every preset and on each of the
 * three profiles the pipeline produces. Prints a table; gated on `MEASURE=1`.
 *
 *     MEASURE=1 ./gradlew :engine:jvmTest --tests '*ElevationGainMeasurementTest*' --rerun-tasks -i
 *
 * The `raw` column is the A/B against the historical behaviour, and on the smoothed profile it must
 * reproduce [Path.elevationGain] bit-for-bit — asserted, not merely printed, because that is the
 * only thing keeping the new accumulator honest about what it changed.
 *
 * `demo/public/gpx/strava.gpx` carries **no** summary D+ of its own (its `<metadata>` holds only
 * `<time>`, and there is no `gpxx:TrackStatsExtension`). What it does carry is Strava's own `<ele>`
 * stream, which makes the `file` row a usable *proxy* reference — not a published figure.
 *
 * JVM-only by necessity: the fixtures live in the repository, and reading it from a test is not
 * portable. Skips silently when the directory is not found.
 */
class ElevationGainMeasurementTest {
    private val gpxDir: File? =
        generateSequence(File(".").absoluteFile) { it.parentFile }
            .map { File(it, "demo/public/gpx") }
            .firstOrNull { it.isDirectory }

    private val fixtures =
        listOf("strava", "sports-tracker", "stelvio", "sample", "movescount", "amazfit", "garmin")
            .mapNotNull { name -> gpxDir?.let { name to File(it, "$name.gpx") } }
            .filter { it.second.exists() }

    private val enabled: Boolean =
        System.getenv("MEASURE") == "1" || System.getProperty("measure") == "true"

    /** Geometry only — no DEM, no physics, no resampling, so the profiles stay comparable. */
    private fun geometryOnly(smoothWindowM: Double) =
        EnhanceOptions.DEFAULT.copy(
            fixElevation = false,
            computeMaxSpeeds = false,
            virtualizeTrack = false,
            computeOnePointPerSecond = false,
            simplifyPath = SimplifyPathOptions(enabled = false),
            elevationSmoothWindowM = smoothWindowM,
        )

    private fun enhanced(
        file: File,
        smoothWindowM: Double = ElevationStep.DEFAULT_SMOOTH_WINDOW_M,
    ): Path {
        val input = GpxParser.parse(file.readText()).firstTrackAsPath()
        return runBlocking { Enhancer.enhanceCourseDefault(input, null, geometryOnly(smoothWindowM)) }
    }

    private fun profileOf(
        path: Path,
        source: Boolean,
    ): Pair<DoubleArray, DoubleArray> {
        val d = DoubleArray(path.size) { path.distance(it) }
        val e =
            DoubleArray(path.size) {
                val s = path.sourceElevation(it)
                if (source && !s.isNaN()) s else path.elevation(it)
            }
        return d to e
    }

    private fun gain(
        profile: Pair<DoubleArray, DoubleArray>,
        preset: ElevationGainPreset,
    ) = ElevationGain.compute(profile.first, profile.second, ElevationGainOptions.of(preset))

    @Test
    fun measure() {
        if (!enabled) {
            println("[elevation-gain] set MEASURE=1 to run the fixture measurement")
            return
        }
        val presets = ElevationGainPreset.entries
        println("[elevation-gain] D+ in metres, by profile and preset")
        println("[elevation-gain] profile = file (as recorded) / source (densified, pre-smoothing) / smoothed (150 m)")
        for ((name, file) in fixtures) {
            val input = GpxParser.parse(file.readText()).firstTrackAsPath()
            val out = enhanced(file)
            println()
            println("$name — ${"%.1f".format(input.totalDistance / 1000.0)} km, ${input.size} pts in, ${out.size} out")
            println("  %-9s | %s".format("profile", presets.joinToString(" | ") { "%8s".format(it.id) } + " |    legs"))
            val rows =
                listOf(
                    "file" to (DoubleArray(input.size) { input.distance(it) } to DoubleArray(input.size) { input.elevation(it) }),
                    "source" to profileOf(out, source = true),
                    "smoothed" to profileOf(out, source = false),
                )
            for ((label, profile) in rows) {
                val results = presets.map { gain(profile, it) }
                println(
                    "  %-9s | %s".format(
                        label,
                        results.joinToString(" | ") { "%8.0f".format(it.gainM) } +
                            " | %7d".format(results.first { it.thresholdM > 0.0 }.legCount),
                    ),
                )
            }
        }
    }

    @Test
    fun the_raw_preset_reproduces_the_historical_sum_on_every_fixture() {
        if (gpxDir == null) {
            println("[elevation-gain] skipped: no demo/public/gpx found")
            return
        }
        for ((name, file) in fixtures) {
            val out = enhanced(file)
            val raw = gain(profileOf(out, source = false), ElevationGainPreset.RAW)
            assertEquals(out.elevationGain, raw.gainM, 1e-6, "$name gain")
            assertEquals(out.elevationLoss, raw.lossM, 1e-6, "$name loss")
        }
    }

    @Test
    fun a_wider_smoothing_window_never_reports_more_climbing() {
        if (gpxDir == null) return
        for ((name, file) in fixtures) {
            var previous = Double.MAX_VALUE
            for (window in listOf(10.0, 50.0, 150.0, 300.0)) {
                val out = enhanced(file, smoothWindowM = window)
                // Read the smoothed profile on purpose: this is the scale-dependence claim, and it
                // is invisible on `source`, which no window touches.
                val g = gain(profileOf(out, source = false), ElevationGainPreset.RAW).gainM
                assertTrue(
                    g <= previous + 0.5,
                    "$name at ${window}m reported $g, more than the previous $previous",
                )
                previous = g
            }
        }
    }

    @Test
    fun the_reported_figure_is_below_the_raw_sum_on_a_noisy_trace() {
        val file = fixtures.firstOrNull { it.first == "strava" }?.second ?: return
        val out = enhanced(file)
        val source = profileOf(out, source = true)
        val raw = gain(source, ElevationGainPreset.RAW).gainM
        val dem = gain(source, ElevationGainPreset.DEM).gainM
        assertTrue(dem < raw, "the dead band should remove something on a 1 Hz trace: $dem vs $raw")
        assertTrue(dem > raw * 0.5, "…but not half the ride: $dem vs $raw")
    }
}
