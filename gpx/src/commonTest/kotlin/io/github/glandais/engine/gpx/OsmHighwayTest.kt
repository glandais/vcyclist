package io.github.glandais.engine.gpx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Road width inferred from the OSM `highway` class.
 *
 * The fixture shape here is the one a real router emits — `gpx.studio`'s Stelvio export nests
 * `<highway>` two containers deep inside `gpxtpx:TrackPointExtension`, and carries no `width` or
 * `lanes` at all. That nesting is the whole reason the parser recurses, so it is what gets tested.
 */
class OsmHighwayTest {
    /** Exactly the element shape a gpx.studio OSM export produces. */
    private fun routerGpx(
        highway: String,
        surface: String = "asphalt",
        extraPointExtensions: String = "",
    ): String =
        """
        <?xml version="1.0"?>
        <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1"
             xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1">
          <trk><name>t</name><trkseg>
            <trkpt lat="46.5318" lon="10.4439"><ele>2625</ele>
              <extensions><gpxtpx:TrackPointExtension><gpxtpx:Extensions>
                <highway>$highway</highway>
                <surface>$surface</surface>
              </gpxtpx:Extensions></gpxtpx:TrackPointExtension>$extraPointExtensions</extensions>
            </trkpt>
            <trkpt lat="46.5316" lon="10.4444"><ele>2627</ele></trkpt>
          </trkseg></trk>
        </gpx>
        """.trimIndent()

    @Test
    fun `the highway class reaches the path as a width`() {
        val doc = GpxParser.parse(routerGpx("secondary"))
        assertEquals("secondary", doc.tracks[0].points[0].highway)
        assertEquals(6.0, doc.firstTrackAsPath().roadWidth(0))
    }

    @Test
    fun `narrower classes give narrower roads`() {
        fun widthOf(highway: String) = GpxParser.parse(routerGpx(highway)).firstTrackAsPath().roadWidth(0)
        val primary = widthOf("primary")
        val secondary = widthOf("secondary")
        val residential = widthOf("residential")
        val track = widthOf("track")
        assertTrue(primary > secondary, "primary $primary should exceed secondary $secondary")
        assertTrue(secondary > residential, "secondary $secondary should exceed residential $residential")
        assertTrue(residential > track, "residential $residential should exceed track $track")
    }

    /**
     * Inference must never invent a width for a class it does not know. `NaN` hands the decision to
     * the engine's own default, which is at least one visible number a user can override.
     */
    @Test
    fun `an unknown class infers nothing`() {
        val path = GpxParser.parse(routerGpx("teleporter")).firstTrackAsPath()
        assertTrue(path.roadWidth(0).isNaN(), "unknown class produced ${path.roadWidth(0)}")
        assertNull(OsmHighway.defaultWidthM("teleporter"))
        assertNull(OsmHighway.defaultWidthM(null))
        assertNull(OsmHighway.defaultWidthM("  "))
    }

    @Test
    fun `an explicit road width beats the inferred class`() {
        val doc =
            GpxParser.parse(
                routerGpx("track", extraPointExtensions = "<roadwidth>8</roadwidth>"),
            )
        val path = doc.firstTrackAsPath()
        assertEquals(8.0, path.roadWidth(0), "explicit data must beat inference")
    }

    @Test
    fun `a track-level default beats the inferred class`() {
        val xml =
            """
            <?xml version="1.0"?>
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <trk><name>t</name>
                <extensions><roadwidth>9</roadwidth></extensions>
                <trkseg>
                  <trkpt lat="46.5318" lon="10.4439"><ele>2625</ele>
                    <extensions><highway>track</highway></extensions>
                  </trkpt>
                  <trkpt lat="46.5316" lon="10.4444"><ele>2627</ele></trkpt>
                </trkseg>
              </trk>
            </gpx>
            """.trimIndent()
        assertEquals(9.0, GpxParser.parse(xml).firstTrackAsPath().roadWidth(0))
    }

    @Test
    fun `the class is case and whitespace tolerant`() {
        assertEquals(6.0, OsmHighway.defaultWidthM(" Secondary "))
        assertEquals(6.0, OsmHighway.defaultWidthM("SECONDARY"))
    }

    /**
     * Every inferred width has to survive the same plausibility gate an explicit one does, or the
     * mapping could smuggle in a value the parser would have rejected from a file.
     */
    @Test
    fun `every inferred width is inside the plausible range`() {
        for (highway in listOf(
            "motorway",
            "trunk",
            "primary",
            "secondary",
            "tertiary",
            "unclassified",
            "residential",
            "living_street",
            "service",
            "road",
            "track",
            "cycleway",
            "path",
            "bridleway",
            "footway",
            "pedestrian",
            "steps",
        )) {
            val w = OsmHighway.defaultWidthM(highway)
            assertTrue(w != null, "no width for $highway")
            assertTrue(w >= 2.5 && w <= 20.0, "$highway maps to $w, outside the plausible range")
            // And it must actually reach a Path rather than being filtered on the way.
            val path = GpxParser.parse(routerGpx(highway)).firstTrackAsPath()
            assertEquals(w, path.roadWidth(0), "$highway did not reach the path")
        }
    }

    /**
     * `surface` is parsed past, not ingested — nothing reads grip per point yet, and a field no
     * consumer reads is a claim the API cannot keep. Pinned so that adding one is a deliberate act.
     */
    @Test
    fun `surface is not silently turned into a width`() {
        val gravel = GpxParser.parse(routerGpx("secondary", surface = "gravel")).firstTrackAsPath()
        val asphalt = GpxParser.parse(routerGpx("secondary", surface = "asphalt")).firstTrackAsPath()
        assertEquals(asphalt.roadWidth(0), gravel.roadWidth(0), "surface must not affect width")
    }
}
