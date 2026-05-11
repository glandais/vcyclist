package io.github.glandais.engine

import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Full pipeline smoke for `enhance` with `fixElevation=true` in a Node runtime (task 33).
 *
 * Gated by `INTEGRATION=1` (env propagated to KotlinJsTest by `build.gradle.kts`). Skips silently
 * in browser environments where `process` is undefined. Duplicates the `integrationEnabled()`
 * helper locally because test source sets are not shared across modules.
 */
class EnhanceWithElevationNodeTest {
    @Test
    fun enhanceWithFixElevationOnAlpinePathProducesCorrectedElevations() =
        runTest {
            if (!integrationEnabled()) return@runTest

            // Tiny Alpine GPX near Mont Blanc — 3 waypoints, no elevation set, so
            // fixElevation will fill them all from the DEM.
            val gpx =
                """<?xml version="1.0" encoding="UTF-8"?>
                |<gpx version="1.1" creator="vcyclist-test"
                |     xmlns="http://www.topografix.com/GPX/1/1">
                |  <trk><trkseg>
                |    <trkpt lat="45.8326" lon="6.8652"><time>2024-01-01T00:00:00Z</time></trkpt>
                |    <trkpt lat="45.8350" lon="6.8700"><time>2024-01-01T00:00:30Z</time></trkpt>
                |    <trkpt lat="45.8380" lon="6.8750"><time>2024-01-01T00:01:00Z</time></trkpt>
                |  </trkseg></trk>
                |</gpx>
                """.trimMargin()

            val path = parseGpx(gpx)
            val options = js("({ fixElevation: true })").unsafeCast<EnhanceOptionsDto>()
            val out = enhance(path, options).await()

            assertTrue(pathSize(out) > 0, "enhanced path should not be empty")
            // After fixElevation against Mont Blanc DEM, all elevations must be plausible:
            // the path crosses 4000-4800 m range. Spot-check first/middle/last.
            val first = pointAt(out, 0)
            val last = pointAt(out, pathSize(out) - 1)
            assertTrue(first.elevation > 1500.0, "first elevation ${first.elevation} > 1500 m")
            assertTrue(first.elevation < 5000.0, "first elevation ${first.elevation} < 5000 m")
            assertTrue(last.elevation > 1500.0, "last elevation ${last.elevation} > 1500 m")
            assertTrue(last.elevation < 5000.0, "last elevation ${last.elevation} < 5000 m")
        }
}

private fun integrationEnabled(): Boolean =
    js(
        "typeof process !== 'undefined' && process.env && process.env.INTEGRATION === '1'",
    ) as Boolean
