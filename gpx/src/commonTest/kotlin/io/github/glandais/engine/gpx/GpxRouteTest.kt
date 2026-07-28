package io.github.glandais.engine.gpx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Task g24: `<rte>` / `<rtept>` are read and written.
 *
 * Before this, a GPX made only of routes parsed into an **empty document, without error** — the
 * worst kind of failure. Routes are the normal output of several route planners, and gpx2web
 * has always read them (`GPXFileReader.java:153`).
 */
class GpxRouteTest {
    private val twoRoutes =
        """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="planner" xmlns="http://www.topografix.com/GPX/1/1">
  <metadata><name>planned</name></metadata>
  <rte>
    <name>day one</name>
    <rtept lat="45.0" lon="6.0"><ele>1000.0</ele></rtept>
    <rtept lat="45.01" lon="6.01"><ele>1100.0</ele></rtept>
    <rtept lat="45.02" lon="6.02"><ele>1200.0</ele></rtept>
  </rte>
  <rte>
    <name>day two</name>
    <rtept lat="46.0" lon="7.0"></rtept>
    <rtept lat="46.01" lon="7.01"></rtept>
  </rte>
</gpx>"""

    private val mixed =
        """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="mixed" xmlns="http://www.topografix.com/GPX/1/1">
  <trk><name>recorded</name><trkseg>
    <trkpt lat="45.0" lon="6.0"><ele>1000.0</ele></trkpt>
    <trkpt lat="45.01" lon="6.01"><ele>1010.0</ele></trkpt>
  </trkseg></trk>
  <rte><name>planned</name>
    <rtept lat="46.0" lon="7.0"><ele>500.0</ele></rtept>
    <rtept lat="46.01" lon="7.01"><ele>510.0</ele></rtept>
  </rte>
  <trk><name>recorded 2</name><trkseg>
    <trkpt lat="47.0" lon="8.0"></trkpt>
    <trkpt lat="47.01" lon="8.01"></trkpt>
  </trkseg></trk>
</gpx>"""

    @Test
    fun `case 01 — a route-only GPX is no longer parsed as empty`() {
        val doc = GpxParser.parse(twoRoutes)

        assertEquals(2, doc.tracks.size, "two <rte> means two entries")
        assertTrue(doc.tracks.all { it.kind == GpxPathKind.ROUTE })
        assertEquals(listOf("day one", "day two"), doc.tracks.map { it.name })
        assertEquals(3, doc.tracks[0].points.size)
        assertEquals(2, doc.tracks[1].points.size)
        assertEquals(45.0, doc.tracks[0].points[0].latitudeDeg, 1e-9)
        assertEquals(1000.0, doc.tracks[0].points[0].elevationM!!, 1e-9)
    }

    @Test
    fun `case 02 — a mixed file keeps document order and marks each entry`() {
        val doc = GpxParser.parse(mixed)

        assertEquals(3, doc.tracks.size)
        assertEquals(
            listOf(GpxPathKind.TRACK, GpxPathKind.ROUTE, GpxPathKind.TRACK),
            doc.tracks.map { it.kind },
            "parse order must follow the document, not the container type",
        )
        assertEquals(listOf("recorded", "planned", "recorded 2"), doc.tracks.map { it.name })
    }

    @Test
    fun `case 03 — an rtept without lat fails exactly like a trkpt without lat`() {
        val broken =
            """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
  <rte><rtept lon="6.0"><ele>1000</ele></rtept></rte>
</gpx>"""

        val thrown = assertFailsWith<IllegalArgumentException> { GpxParser.parse(broken, repairOnFailure = false) }
        assertTrue(
            thrown.message!!.contains("latitude or longitude"),
            "expected the shared wptType message, was: ${thrown.message}",
        )
    }

    @Test
    fun `case 04 — a timestamped route feeds GpxDocument startTime`() {
        val timed =
            """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
  <rte><rtept lat="45.0" lon="6.0"><ele>1000</ele><time>2024-05-01T08:00:00Z</time></rtept></rte>
</gpx>"""

        val doc = GpxParser.parse(timed)
        assertEquals(1_714_550_400_000L, doc.tracks[0].points[0].timeEpochMs)
        assertEquals(1_714_550_400_000L, doc.startTime!!.toEpochMilliseconds())
    }

    @Test
    fun `case 05 — a route round-trips as rte, not as trk`() {
        val xml = GpxWriter.write(GpxParser.parse(twoRoutes))

        assertTrue(xml.contains("<rte>"), xml)
        assertTrue(xml.contains("<rtept"), xml)
        assertFalse(xml.contains("<trk>"), xml)
        assertFalse(xml.contains("<trkpt"), xml)

        val reparsed = GpxParser.parse(xml)
        assertEquals(2, reparsed.tracks.size)
        assertTrue(reparsed.tracks.all { it.kind == GpxPathKind.ROUTE })
        assertEquals(listOf("day one", "day two"), reparsed.tracks.map { it.name })
        assertEquals(3, reparsed.tracks[0].points.size)
    }

    @Test
    fun `case 06 — a mixed round-trip keeps every kind, in document order`() {
        val xml = GpxWriter.write(GpxParser.parse(mixed))

        // Written where they were read: trk, rte, trk — not regrouped by container.
        assertTrue(xml.indexOf("<trk>") < xml.indexOf("<rte>"), "input order not preserved: $xml")

        val reparsed = GpxParser.parse(xml)
        assertEquals(
            listOf(GpxPathKind.TRACK, GpxPathKind.ROUTE, GpxPathKind.TRACK),
            reparsed.tracks.map { it.kind },
        )
        assertEquals(listOf("recorded", "planned", "recorded 2"), reparsed.tracks.map { it.name })
    }

    @Test
    fun `case 06b — a mixed file survives two round-trips unchanged`() {
        val once = GpxWriter.write(GpxParser.parse(mixed))
        val twice = GpxWriter.write(GpxParser.parse(once))

        assertEquals(once, twice, "writing is a fixed point, so order cannot drift over time")
    }

    @Test
    fun `case 07 — tracksAsPaths can exclude routes`() {
        val doc = GpxParser.parse(mixed)

        assertEquals(3, doc.tracksAsPaths().size, "the default takes both containers")
        assertEquals(2, doc.tracksAsPaths(kinds = setOf(GpxPathKind.TRACK)).size)
        assertEquals(1, doc.tracksAsPaths(kinds = setOf(GpxPathKind.ROUTE)).size)
        assertEquals(1, doc.segmentsAsPaths(kinds = setOf(GpxPathKind.ROUTE)).size)
    }

    @Test
    fun `case 08 — a track-only file is unaffected by g24`() {
        val trackOnly =
            """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
  <trk><name>t</name><trkseg>
    <trkpt lat="45.0" lon="6.0"><ele>1000.0</ele></trkpt>
    <trkpt lat="45.01" lon="6.01"><ele>1010.0</ele></trkpt>
  </trkseg></trk>
</gpx>"""

        val doc = GpxParser.parse(trackOnly)
        assertEquals(GpxPathKind.TRACK, doc.tracks[0].kind, "TRACK is the default")
        assertEquals(1, doc.tracksAsPaths().size)
        assertEquals(GpxWriter.write(doc), GpxWriter.write(doc, writeExtensions = true))
        assertFalse(GpxWriter.write(doc).contains("<rte>"))
    }

    @Test
    fun `case 09 — a written route has no trkseg`() {
        val xml = GpxWriter.write(GpxParser.parse(twoRoutes))

        assertFalse(xml.contains("trkseg"), "a route has no segment concept: $xml")
    }

    @Test
    fun `case 10 — an empty rte parses to an entry with zero points`() {
        val empty =
            """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
  <rte><name>nothing</name></rte>
</gpx>"""

        val doc = GpxParser.parse(empty)
        assertEquals(1, doc.tracks.size)
        assertEquals(0, doc.tracks[0].points.size)
        assertEquals(0, doc.tracksAsPaths().size, "no parasitic Path(0)")
    }

    @Test
    fun `case 11 — route extensions obey the g23 flag`() {
        val withPower =
            """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
  <rte><rtept lat="45.0" lon="6.0"><ele>1000</ele><extensions><power>210</power></extensions></rtept></rte>
</gpx>"""

        val doc = GpxParser.parse(withPower)
        assertEquals(210.0, doc.tracks[0].points[0].powerW!!, 1e-9)
        assertTrue(GpxWriter.write(doc).contains("<power>"))
        assertFalse(GpxWriter.write(doc, writeExtensions = false).contains("<power>"))
    }

    @Test
    fun `case 12 — a route converts to a Path like any other`() {
        val paths = GpxParser.parse(twoRoutes).tracksAsPaths()

        assertEquals(2, paths.size)
        assertEquals(3, paths[0].size)
        assertTrue(paths[0].totalDistance > 0.0, "derived data is computed for routes too")
    }
}
