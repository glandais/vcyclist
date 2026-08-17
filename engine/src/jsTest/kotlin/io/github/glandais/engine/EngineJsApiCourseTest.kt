@file:OptIn(kotlin.js.ExperimentalJsExport::class)

package io.github.glandais.engine

import io.github.glandais.engine.gpx.GpxFixtures
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests covering the task-34 expansion of the Kotlin/JS [@JsExport] façade : the
 * [enhanceWithCourse] entry point with custom DTO inputs, the generic [getField] accessor and
 * the [fieldDefinitions] catalog.
 */
class EngineJsApiCourseTest {
    @Test
    fun `enhanceWithCourse uses the custom cyclist + power provider`() =
        runTest {
            val path = parseGpx(GpxFixtures.SAMPLE_GPX)

            // Heavy cyclist with poor aero + low constant power : should ride slower than the
            // engine defaults (80 kg / 250 W) and therefore take longer to complete the same
            // sample track.
            val heavyCyclist =
                js(
                    "({ massKg: 110, cd: 0.9, frontalAreaM2: 0.55, " +
                        "maxLeanAngleDeg: 25, maxBrakeG: 0.4, maxSpeedKmH: 60 })",
                ).unsafeCast<CyclistDto>()
            val lowPower =
                js("({ type: 'constant', power: 150, useHarmonics: false })")
                    .unsafeCast<PowerProviderDto>()

            val slow =
                enhanceWithCourse(path, heavyCyclist, null, null, lowPower, null).await()
            val fast =
                enhanceWithCourse(path, null, null, null, null, null).await()

            assertTrue(pathSize(slow) > 0, "slow run should produce a non-empty path")
            assertTrue(pathSize(fast) > 0, "fast run should produce a non-empty path")
            assertTrue(
                pathDurationMs(slow) > pathDurationMs(fast),
                "heavy cyclist with 150 W should take longer than default 250 W " +
                    "(slow=${pathDurationMs(slow)} fast=${pathDurationMs(fast)})",
            )
        }

    @Test
    fun `getField by prop matches the strongly-typed pointAt accessor`() {
        val path = parseGpx(GpxFixtures.SAMPLE_GPX)
        val i = 5
        val pt = pointAt(path, i)
        assertEquals(pt.elevation, getField(path, i, "elevation"), absoluteTolerance = 1e-9)
        assertEquals(pt.distance, getField(path, i, "distance"), absoluteTolerance = 1e-9)
    }

    @Test
    fun `fieldDefinitions exposes the 38 entries with the expected props`() {
        val defs = fieldDefinitions()
        assertEquals(38, defs.size)
        assertTrue(defs.any { it.prop == "elevation" }, "missing 'elevation' entry")
        assertTrue(defs.any { it.prop == "speed" }, "missing 'speed' entry")
        // Sanity-check the carried metadata for one known entry.
        val ele = defs.first { it.prop == "elevation" }
        assertEquals("meters", ele.unit)
        assertEquals("elevation", ele.categoryId)
    }

    @Test
    fun `pathLatitudeDeg + pathLongitudeDeg match pointAt`() {
        val path = parseGpx(GpxFixtures.SAMPLE_GPX)
        val i = 3
        val pt = pointAt(path, i)
        assertEquals(pt.latitudeDeg, pathLatitudeDeg(path, i), absoluteTolerance = 1e-12)
        assertEquals(pt.longitudeDeg, pathLongitudeDeg(path, i), absoluteTolerance = 1e-12)
    }
}
