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

    @Test
    fun `parseGpxTracks and parseGpxSegments expose every track and segment`() {
        val tracks = parseGpxTracks(GpxFixtures.MULTI_TRACK_GPX)
        assertEquals(2, tracks.size)
        assertEquals(3, pathSize(tracks[0]))
        assertEquals(2, pathSize(tracks[1]))

        val segments = parseGpxSegments(GpxFixtures.MULTI_SEGMENT_GPX)
        assertEquals(3, segments.size)
        assertEquals(listOf(3, 2, 4), segments.map { pathSize(it) })
        // The single-track façade still sees only the first track — no rupture.
        assertEquals(3, pathSize(parseGpx(GpxFixtures.MULTI_TRACK_GPX)))
    }

    @Test
    fun `writeGpxTracks round-trips a multi-track document`() {
        val tracks = parseGpxTracks(GpxFixtures.MULTI_TRACK_GPX)
        val xml = writeGpxTracks(tracks)
        assertTrue(xml.startsWith("<?xml"), "expected XML declaration, got: ${xml.take(40)}")
        val roundTrip = parseGpxTracks(xml)
        assertEquals(2, roundTrip.size)
        assertEquals(listOf(3, 2), roundTrip.map { pathSize(it) })
    }

    @Test
    fun `parseGpxWaypoints exposes every wpt with all fields populated`() {
        val waypoints = parseGpxWaypoints(GpxFixtures.WAYPOINTS_GPX)
        assertEquals(3, waypoints.size)
        assertEquals(45.5, waypoints[0].latitudeDeg)
        val full = waypoints[1]
        assertEquals(1200.5, full.elevationM)
        assertEquals("Col du Sommet", full.name)
        assertEquals("Ravitaillement au sommet", full.description)
        assertEquals("Summit", full.symbol)
        assertEquals("peak", full.type)
        assertTrue(full.timeEpochMs!! > 0.0)
    }

    @Test
    fun `writeGpxTracks forwards waypoints as wpt elements before trk`() {
        val waypoints = parseGpxWaypoints(GpxFixtures.WAYPOINTS_GPX)
        val tracks = parseGpxTracks(GpxFixtures.WAYPOINTS_GPX)
        val xml = writeGpxTracks(tracks, waypoints)
        assertTrue(xml.indexOf("<wpt") in 0 until xml.indexOf("<trk>"), xml)
        val roundTrip = parseGpxWaypoints(xml)
        assertEquals(3, roundTrip.size)
    }

    // ---- g07 : pathToJson must produce valid, directly usable JSON --------------

    @Test
    fun `pathToJson output is valid JSON directly usable by JSON-parse`() {
        val path = parseGpx(GpxFixtures.SAMPLE_GPX)
        val json = pathToJson(path, false)

        val parsed = kotlin.js.JSON.parse<dynamic>(json)
        assertEquals(pathSize(path), (parsed.size as Int))
        val elevationSeries = parsed.fields.elevation
        assertTrue(elevationSeries.length as Int == pathSize(path))
        assertTrue((parsed.meta.totalDistance as Double) > 0.0)
    }

    @Test
    fun `pathToJson pretty output also parses as valid JSON`() {
        val path = parseGpx(GpxFixtures.SAMPLE_GPX)
        val json = pathToJson(path, true)
        assertTrue(json.contains("\n"))

        val parsed = kotlin.js.JSON.parse<dynamic>(json)
        assertEquals(pathSize(path), (parsed.size as Int))
    }
}
