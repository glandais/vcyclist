package io.github.glandais.engine

import io.github.glandais.engine.gpx.GpxFixtures
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Task g29: the JS façade catches up with g23 (`writeExtensions`), g24 (route filtering) and g25
 * (multi-path FIT) — plus g31, which exports the worst-case wind.
 *
 * The behaviour of each feature is already covered in `commonTest` by its own task. What is
 * verified here is the **bridge**: that the parameter exists on the exported function, defaults
 * the way the Kotlin API does, and actually reaches it.
 */
class EngineJsApiCatchupTest {
    private val routeGpx =
        """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
  <trk><name>recorded</name><trkseg>
    <trkpt lat="45.0" lon="6.0"><ele>1000</ele></trkpt>
    <trkpt lat="45.001" lon="6.001"><ele>1010</ele></trkpt>
  </trkseg></trk>
  <rte><name>planned</name>
    <rtept lat="46.0" lon="7.0"><ele>500</ele></rtept>
    <rtept lat="46.001" lon="7.001"><ele>510</ele></rtept>
  </rte>
</gpx>"""

    // --- g23 ---------------------------------------------------------------------------

    @Test
    fun `writeGpx defaults to writing extensions`() {
        val path = parseGpx(GpxFixtures.SAMPLE_GPX)

        assertEquals(writeGpx(path, true), writeGpx(path))
    }

    @Test
    fun `writeGpx can drop the extensions`() {
        val path = parseGpx(GpxFixtures.SAMPLE_GPX)

        val bare = writeGpx(path, writeExtensions = false)

        assertFalse(bare.contains("<extensions>"), bare.take(400))
        assertFalse(bare.contains("gpxtpx"), bare.take(400))
        assertTrue(bare.contains("<trkpt"), "geometry must survive")
    }

    @Test
    fun `writeGpxTracks and writeGpxAt take the flag too`() {
        val path = parseGpx(GpxFixtures.SAMPLE_GPX)

        val tracks = writeGpxTracks(arrayOf(path), emptyArray(), false)
        val stamped = writeGpxAt(path, 1_767_225_600_000.0, false)

        assertFalse(tracks.contains("<extensions>"), "writeGpxTracks")
        assertFalse(stamped.contains("<extensions>"), "writeGpxAt")
        assertTrue(stamped.contains("<time>"), "an absolute timestamp is not an extension")
    }

    // --- g24 ---------------------------------------------------------------------------

    @Test
    fun `parseGpxTracks includes routes, and the two filtered forms partition it`() {
        val all = parseGpxTracks(routeGpx)
        val tracksOnly = parseGpxTracksOnly(routeGpx)
        val routesOnly = parseGpxRoutesOnly(routeGpx)

        assertEquals(2, all.size, "a track and a route")
        assertEquals(1, tracksOnly.size)
        assertEquals(1, routesOnly.size)
        assertEquals(all.size, tracksOnly.size + routesOnly.size, "the two forms must partition the whole")
    }

    @Test
    fun `a route-only file is empty through parseGpxTracksOnly and not through parseGpxTracks`() {
        val routeOnly =
            """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
  <rte><rtept lat="46.0" lon="7.0"/><rtept lat="46.001" lon="7.001"/></rte>
</gpx>"""

        assertEquals(1, parseGpxTracks(routeOnly).size)
        assertEquals(0, parseGpxTracksOnly(routeOnly).size)
    }

    // --- g25 ---------------------------------------------------------------------------

    @Test
    fun `pathsToFit encodes several paths and agrees with pathToFit on one`() {
        val path = parseGpx(GpxFixtures.SAMPLE_GPX)
        val start = 1_767_225_600_000.0

        val single = pathToFit(path, "one", start)
        val viaList = pathsToFit(arrayOf(path), "one", start)
        val two = pathsToFit(arrayOf(path, path), "two", start)

        assertContentEquals(single, viaList, "a one-element array must encode exactly like the single path")
        assertTrue(two.size > single.size, "two paths cannot fit in the bytes of one")
    }

    @Test
    fun `pathsToFit accepts an inter-path gap`() {
        val path = parseGpx(GpxFixtures.SAMPLE_GPX)
        val start = 1_767_225_600_000.0

        val contiguous = pathsToFit(arrayOf(path, path), "two", start)
        val spaced = pathsToFit(arrayOf(path, path), "two", start, interPathGapMs = 300_000.0)

        assertEquals(contiguous.size, spaced.size, "a gap shifts timestamps, it does not add records")
        assertFalse(contiguous.contentEquals(spaced), "the gap must actually change the bytes")
    }

    // --- g31 ---------------------------------------------------------------------------

    @Test
    fun `dominantHeadwindAzimuth is a usable number on a real trace`() {
        val path = parseGpx(GpxFixtures.SAMPLE_GPX)

        val azimuth = dominantHeadwindAzimuth(path)

        assertFalse(azimuth.isNaN(), "sample.gpx has enough points to have a dominant direction")
        assertTrue(azimuth >= 0.0 && azimuth < 360.0, "azimuth out of range: $azimuth")
    }

    @Test
    fun `dominantHeadwindAzimuth reports NaN rather than a plausible zero`() {
        val threePoints =
            """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
  <trk><trkseg>
    <trkpt lat="45.0" lon="6.0"/><trkpt lat="45.001" lon="6.0"/><trkpt lat="45.002" lon="6.0"/>
  </trkseg></trk>
</gpx>"""

        assertTrue(dominantHeadwindAzimuth(parseGpx(threePoints)).isNaN())
    }

    @Test
    fun `the multi-path form agrees with the single one`() {
        val path = parseGpx(GpxFixtures.SAMPLE_GPX)

        assertEquals(dominantHeadwindAzimuth(path), dominantHeadwindAzimuthOfTracks(arrayOf(path)))
    }
}
