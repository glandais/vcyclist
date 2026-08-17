package io.github.glandais.engine.gpx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Road width, from `<roadwidth>` extensions to `path.roadWidth(i)` and back out again.
 *
 * Width is not decoration: the racing-line corridor half-width is linear in it, so every value
 * that reaches a `Path` has to be one a file actually asserted. Hence the emphasis here on what
 * must *not* be picked up.
 */
class GpxRoadWidthTest {
    private fun gpx(
        trackExtensions: String = "",
        pointExtensions: String = "",
    ): String =
        """
        <?xml version="1.0"?>
        <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
          <trk><name>t</name>$trackExtensions<trkseg>
            <trkpt lat="45.0" lon="6.0"><ele>100</ele>$pointExtensions</trkpt>
            <trkpt lat="45.0001" lon="6.0"><ele>101</ele></trkpt>
          </trkseg></trk>
        </gpx>
        """.trimIndent()

    @Test
    fun `a point-level roadwidth reaches the path`() {
        val doc = GpxParser.parse(gpx(pointExtensions = "<extensions><roadwidth>4.5</roadwidth></extensions>"))
        assertEquals(4.5, doc.tracks[0].points[0].roadWidthM)
        val path = doc.firstTrackAsPath()
        assertEquals(4.5, path.roadWidth(0))
    }

    @Test
    fun `an absent roadwidth leaves NaN, not zero`() {
        val path = GpxParser.parse(gpx()).firstTrackAsPath()
        assertTrue(path.roadWidth(0).isNaN(), "absent width must be NaN, was ${path.roadWidth(0)}")
        assertTrue(path.roadWidth(1).isNaN())
    }

    @Test
    fun `a track-level roadwidth applies to points that lack one`() {
        val doc = GpxParser.parse(gpx(trackExtensions = "<extensions><roadwidth>7</roadwidth></extensions>"))
        assertEquals(7.0, doc.tracks[0].roadWidthM)
        val path = doc.firstTrackAsPath()
        assertEquals(7.0, path.roadWidth(0))
        assertEquals(7.0, path.roadWidth(1))
    }

    @Test
    fun `a point-level roadwidth beats the track default`() {
        val doc =
            GpxParser.parse(
                gpx(
                    trackExtensions = "<extensions><roadwidth>7</roadwidth></extensions>",
                    pointExtensions = "<extensions><roadwidth>3</roadwidth></extensions>",
                ),
            )
        val path = doc.firstTrackAsPath()
        assertEquals(3.0, path.roadWidth(0), "the point's own width must win")
        assertEquals(7.0, path.roadWidth(1), "the other point still takes the track default")
    }

    /**
     * The reason the bare leaf `width` is not claimed.
     *
     * `parseExtensions` matches on local name and recurses into unknown containers, so a parser
     * that accepted `width` would also read `<gpx_style:line><width>`, where the value is a
     * **rendering line width in pixels**. A 3 px line would arrive as a 3 m road and halve the
     * corridor on any styled file, silently. This is the single most likely way for a wrong width
     * to enter the system, so it gets its own test.
     */
    @Test
    fun `a gpx_style line width is not mistaken for a road width`() {
        val styled =
            """
            <?xml version="1.0"?>
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1"
                 xmlns:gpx_style="http://www.topografix.com/GPX/gpx_style/0/2">
              <trk><name>t</name>
                <extensions><gpx_style:line><color>ff0000</color><width>3</width></gpx_style:line></extensions>
                <trkseg>
                  <trkpt lat="45.0" lon="6.0"><ele>100</ele></trkpt>
                  <trkpt lat="45.0001" lon="6.0"><ele>101</ele></trkpt>
                </trkseg>
              </trk>
            </gpx>
            """.trimIndent()
        val doc = GpxParser.parse(styled)
        assertNull(doc.tracks[0].roadWidthM, "a styling line width is not a road width")
        val path = doc.firstTrackAsPath()
        assertTrue(path.roadWidth(0).isNaN())
    }

    /**
     * Implausible values are rejected to NaN rather than clamped into range. Clamping a `0` to
     * 2.5 m would invent a corridor the file never claimed; NaN makes the reader fall back to its
     * own documented default, which is the honest answer to "this file is wrong".
     */
    @Test
    fun `implausible widths are rejected rather than clamped`() {
        for (bad in listOf("0", "-1", "0.5", "2000")) {
            val path =
                GpxParser
                    .parse(gpx(pointExtensions = "<extensions><roadwidth>$bad</roadwidth></extensions>"))
                    .firstTrackAsPath()
            assertTrue(path.roadWidth(0).isNaN(), "width '$bad' should be rejected, got ${path.roadWidth(0)}")
        }
        for (good in listOf("2.5", "6", "20")) {
            val path =
                GpxParser
                    .parse(gpx(pointExtensions = "<extensions><roadwidth>$good</roadwidth></extensions>"))
                    .firstTrackAsPath()
            assertTrue(!path.roadWidth(0).isNaN(), "width '$good' should be accepted")
        }
    }

    @Test
    fun `roadWidth survives a writer round-trip`() {
        val doc = GpxParser.parse(gpx(pointExtensions = "<extensions><roadwidth>4.5</roadwidth></extensions>"))
        val xml = GpxWriter.write(doc)
        assertTrue(xml.contains("roadWidth"), "writer should emit the width: $xml")
        val reparsed = GpxParser.parse(xml)
        assertEquals(4.5, reparsed.tracks[0].points[0].roadWidthM)
        assertEquals(4.5, reparsed.firstTrackAsPath().roadWidth(0))
    }

    /**
     * A file with no widths must not gain the `vc` namespace declaration — otherwise every
     * existing output changes for a feature nobody used.
     */
    @Test
    fun `a document without widths declares no vcyclist namespace`() {
        val xml = GpxWriter.write(GpxParser.parse(gpx()))
        // Matched on the declaration, not on "vcyclist": the creator attribute is
        // `@glandais/vcyclist` in every file this writer produces.
        assertTrue(!xml.contains("xmlns:vc="), "unexpected vc namespace in a width-free file: $xml")
        assertTrue(!xml.contains("roadWidth"))
    }

    @Test
    fun `segmentsAsPaths also inherits the track default`() {
        val doc = GpxParser.parse(gpx(trackExtensions = "<extensions><roadwidth>7</roadwidth></extensions>"))
        val paths = doc.segmentsAsPaths()
        assertEquals(1, paths.size)
        assertEquals(7.0, paths[0].roadWidth(0), "a per-segment path must still see the track default")
    }
}
