package io.github.glandais.engine

import io.github.glandais.engine.gpx.GpxParser
import io.github.glandais.engine.gpx.firstTrackAsPath
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * JVM-only end-to-end smoke that runs the **full** [Enhancer] pipeline (every step enabled
 * except `fixElevation` since no HTTP `ElevationProvider` is available in the test JVM) on
 * the canonical `virtual-cyclist/gpx/sample.gpx` fixture.
 *
 * Goal : prove that the task 29 fix (VirtualizeService no longer leaks the source epoch into
 * `time(n-1)`) makes the downstream `PointPerSecond` + `PathSimplifier` steps survive on a
 * real GPX with absolute 2024 timestamps. Before the fix this would OOM ; after the fix it
 * completes in a few seconds and produces a non-empty `Path`.
 *
 * The fixture path lives outside the test resources (it is part of the sibling `virtual-cyclist`
 * project) — if it is missing locally the test is skipped (so this never breaks contributors
 * who clone only `vcyclist`).
 */
class FullPipelineSmokeTest {
    private val sampleGpx = File("/home/glandais/code/perso/vcyclist-all/virtual-cyclist/gpx/sample.gpx")

    @Test
    fun full_pipeline_on_sample_gpx_does_not_oom() {
        if (!sampleGpx.exists()) {
            // Sibling project not present locally ; skip silently.
            println("[FullPipelineSmokeTest] Skipped : ${sampleGpx.absolutePath} not found")
            return
        }
        val xml = sampleGpx.readText()
        val parsed = GpxParser.parse(xml)
        val inputPath = parsed.firstTrackAsPath()
        assertTrue(inputPath.size > 0, "Input GPX should contain points")

        // Full pipeline (every step enabled) except fixElevation (no HTTP available).
        val options = EnhanceOptions.DEFAULT.copy(fixElevation = false)

        val tStart = System.currentTimeMillis()
        val result =
            runBlocking {
                Enhancer.enhanceCourseDefault(
                    inputPath,
                    elevationProvider = null,
                    options = options,
                )
            }
        val wallMs = System.currentTimeMillis() - tStart
        println(
            "[FullPipelineSmokeTest] input=${inputPath.size} pts, " +
                "output=${result.size} pts, distance=${"%.1f".format(result.totalDistance)} m, " +
                "duration=${"%.1f".format(result.durationMs / 1000.0)} s, wall=$wallMs ms",
        )

        assertTrue(result.size > 0, "Pipeline output should be non-empty")
        // Sanity : the 1 Hz resample should produce a reasonable number of points (not zero
        // and not absurd) on a sample GPX whose virtualized duration is on the order of
        // minutes.
        assertTrue(
            result.size < 1_000_000,
            "Pipeline output size=${result.size} is unreasonably large (epoch leak?)",
        )
        // Output duration in ms : should be a sensible fraction of an hour, not ~1.7e12 ms.
        val durationMs = result.durationMs
        assertTrue(
            durationMs < 24.0 * 3600.0 * 1000.0,
            "Pipeline output duration $durationMs ms is unreasonably large (epoch leak?)",
        )
        // Phase 2bis budget : the full pipeline (incl. PointPerDistance densify to 1-2 m,
        // which can multiply the point count ~30×) must complete in < 10 s on a modern
        // JVM. Adjust if too tight on slow CI runners.
        assertTrue(
            wallMs < 10_000,
            "Pipeline took $wallMs ms, expected < 10000 (Phase 2bis budget)",
        )
    }
}
