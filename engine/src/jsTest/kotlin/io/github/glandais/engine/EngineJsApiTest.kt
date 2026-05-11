package io.github.glandais.engine

import io.github.glandais.engine.gpx.GpxFixtures
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Smoke tests for the Kotlin/JS [@JsExport] façade. The pipeline logic is covered in detail
 * by the commonTest suite ; here we just verify that the bridge compiles and that each exported
 * function returns a sane value when invoked from the JS (Node) runtime.
 */
class EngineJsApiTest {
    @Test
    fun `parseGpx returns a non-empty path with first-point fields accessible`() {
        val path = parseGpx(GpxFixtures.SAMPLE_GPX)
        val size = pathSize(path)
        assertTrue(size > 0, "expected non-empty path, got size=$size")
        assertTrue(pathTotalDistance(path) > 0.0)
        assertTrue(pathDurationMs(path) > 0.0)

        val pt0 = pointAt(path, 0)
        // sample.gpx's first trackpoint is 45.680697 / 6.396115 / 350.1 m at 14:25:22.
        assertEquals(45.680697, pt0.latitudeDeg, absoluteTolerance = 1e-6)
        assertEquals(6.396115, pt0.longitudeDeg, absoluteTolerance = 1e-6)
        assertEquals(350.1, pt0.elevation, absoluteTolerance = 1e-6)
        assertTrue(pt0.timeMs > 0.0)
    }

    @Test
    fun `writeGpx round-trips a parsed path into a well-formed GPX string`() {
        val path = parseGpx(GpxFixtures.SAMPLE_GPX)
        val xml = writeGpx(path)
        assertTrue(xml.startsWith("<?xml"), "expected XML declaration, got: ${xml.take(40)}")
        assertTrue(xml.contains("<trkpt"), "expected at least one <trkpt> in output")
        val roundTrip = parseGpx(xml)
        assertEquals(pathSize(path), pathSize(roundTrip))
    }

    @Test
    fun `enhance with default options produces a virtualized path`() =
        runTest {
            val path = parseGpx(GpxFixtures.SAMPLE_GPX)
            val enhanced = enhance(path, options = null).await()
            assertTrue(pathSize(enhanced) > 0, "expected enhance to yield a non-empty path")
            assertTrue(pathTotalDistance(enhanced) > 0.0)
            val xml = writeGpx(enhanced)
            assertTrue(xml.contains("<trkpt"))
        }
}
