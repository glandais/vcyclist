package io.github.glandais.engine.gpx

import io.github.glandais.elevation.MathConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GpxParserTest {
    // --- 1. Parse error handling --------------------------------------------------------

    @Test
    fun `case 01 — malformed XML throws IllegalArgumentException`() {
        // Use unambiguously broken markup ("<gpx ...<" with an unterminated attribute) so every
        // xmlutil backend rejects it ; the previously tested "<gpx><trk><trkseg></trk></gpx>"
        // (closing-tag mismatch) is silently auto-repaired by Chrome's DOMParser used under
        // Karma/jsBrowserTest, see commit history for context.
        assertFailsWith<IllegalArgumentException> {
            GpxParser.parse("<gpx version=\"1.1\"")
        }
    }

    @Test
    fun `case 02 — empty gpx without tracks parses to empty tracks list`() {
        val doc =
            GpxParser.parse(
                """<?xml version="1.0"?><gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1"></gpx>""",
            )
        assertEquals("noname", doc.name)
        assertTrue(doc.tracks.isEmpty(), "expected no tracks, got ${doc.tracks.size}")
    }

    @Test
    fun `case 03 — gpx with empty trkseg yields one track with zero points`() {
        val doc =
            GpxParser.parse(
                """
                <?xml version="1.0"?>
                <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
                  <trk><trkseg/></trk>
                </gpx>
                """.trimIndent(),
            )
        assertEquals(1, doc.tracks.size)
        assertTrue(doc.tracks[0].points.isEmpty())
    }

    @Test
    fun `case 04 — trkpt missing lat lon throws`() {
        assertFailsWith<IllegalArgumentException> {
            GpxParser.parse(
                """
                <?xml version="1.0"?>
                <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
                  <trk><trkseg><trkpt><ele>100</ele></trkpt></trkseg></trk>
                </gpx>
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `case 05 — trkpt with non-numeric lat lon throws`() {
        assertFailsWith<IllegalArgumentException> {
            GpxParser.parse(
                """
                <?xml version="1.0"?>
                <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
                  <trk><trkseg><trkpt lat="X" lon="Y"><ele>100</ele></trkpt></trkseg></trk>
                </gpx>
                """.trimIndent(),
            )
        }
    }

    // --- 2. Minimal point + optional fields ---------------------------------------------

    @Test
    fun `case 06 — minimal trkpt has only lat lon, all other fields null`() {
        val doc =
            GpxParser.parse(
                """
                <?xml version="1.0"?>
                <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
                  <trk><trkseg><trkpt lat="45.5" lon="6.5"/></trkseg></trk>
                </gpx>
                """.trimIndent(),
            )
        val p =
            doc.tracks
                .single()
                .points
                .single()
        assertEquals(45.5, p.latitudeDeg)
        assertEquals(6.5, p.longitudeDeg)
        assertNull(p.elevationM)
        assertNull(p.timeEpochMs)
        assertNull(p.heartRate)
        assertNull(p.cadence)
        assertNull(p.temperatureC)
        assertNull(p.powerW)
    }

    @Test
    fun `case 07 — trkpt with ele parses elevation`() {
        val doc =
            GpxParser.parse(
                """
                <?xml version="1.0"?>
                <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
                  <trk><trkseg><trkpt lat="45.5" lon="6.5"><ele>1234.5</ele></trkpt></trkseg></trk>
                </gpx>
                """.trimIndent(),
            )
        assertEquals(
            1234.5,
            doc.tracks
                .single()
                .points
                .single()
                .elevationM,
        )
    }

    @Test
    fun `case 08 — trkpt with ISO 8601 time parses to correct epoch ms`() {
        val doc =
            GpxParser.parse(
                """
                <?xml version="1.0"?>
                <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
                  <trk><trkseg>
                    <trkpt lat="45.680697" lon="6.396115"><time>2024-11-03T14:25:22.000Z</time></trkpt>
                  </trkseg></trk>
                </gpx>
                """.trimIndent(),
            )
        // 2024-11-03T14:25:22Z → epoch ms
        assertEquals(
            1_730_643_922_000L,
            doc.tracks
                .single()
                .points
                .single()
                .timeEpochMs,
        )
    }

    @Test
    fun `case 09 — invalid time tag is swallowed, timeEpochMs stays null`() {
        val doc =
            GpxParser.parse(
                """
                <?xml version="1.0"?>
                <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
                  <trk><trkseg>
                    <trkpt lat="1.0" lon="2.0"><time>not-a-date</time></trkpt>
                  </trkseg></trk>
                </gpx>
                """.trimIndent(),
            )
        assertNull(
            doc.tracks
                .single()
                .points
                .single()
                .timeEpochMs,
        )
    }

    // --- 3. Real fixtures ---------------------------------------------------------------

    @Test
    fun `case 10 — sample gpx parses 6+ points, first has powerW = 45_0`() {
        val doc = GpxParser.parse(GpxFixtures.SAMPLE_GPX)
        val points = doc.tracks.single().points
        assertTrue(points.size >= 6, "expected ≥ 6 points, got ${points.size}")
        assertEquals(45.0, points[0].powerW)
        assertEquals(26.0, points[1].powerW)
    }

    @Test
    fun `case 11 — sample gpx metadata name and track name are populated`() {
        val doc = GpxParser.parse(GpxFixtures.SAMPLE_GPX)
        assertEquals("sample", doc.name)
        assertEquals("L'étape du Tour 2025", doc.tracks.single().name)
        assertEquals("cycling", doc.tracks.single().type)
    }

    @Test
    fun `case 12 — garmin gpx yields heart rate and cadence on every point`() {
        val doc = GpxParser.parse(GpxFixtures.GARMIN_GPX)
        val points = doc.tracks.single().points
        assertTrue(points.size >= 3)
        assertEquals(61, points[0].heartRate)
        assertEquals(0, points[0].cadence)
        assertEquals(53, points[1].heartRate)
        assertEquals(81, points[1].cadence)
        // garmin.gpx in this fixture set has no <atemp>; temperature stays null.
        assertNull(points[0].temperatureC)
    }

    @Test
    fun `case 13 — amazfit gpx yields heart rate via heartrate tag`() {
        val doc = GpxParser.parse(GpxFixtures.AMAZFIT_GPX)
        val points = doc.tracks.single().points
        assertTrue(points.size >= 3)
        assertEquals(89, points[0].heartRate)
        assertEquals(88, points[1].heartRate)
        assertEquals(87, points[2].heartRate)
    }

    @Test
    fun `case 14 — movescount gpx yields cadence and temp via gpxdata namespace`() {
        val doc = GpxParser.parse(GpxFixtures.MOVESCOUNT_GPX)
        val points = doc.tracks.single().points
        // The first two points have empty <extensions/> ; cadence/temp arrive on point 3.
        assertNull(points[0].cadence)
        assertEquals(87, points[2].cadence)
        assertEquals(98, points[2].heartRate)
        // Temperature comes from <gpxdata:temp>20.7999...</gpxdata:temp>.
        assertNotNull(points[2].temperatureC)
        assertTrue(points[2].temperatureC!! > 20.0 && points[2].temperatureC!! < 21.0)
    }

    // --- 4. Bridge to Path --------------------------------------------------------------

    @Test
    fun `case 15 — firstTrackAsPath produces a Path of the right size`() {
        val doc = GpxParser.parse(GpxFixtures.SAMPLE_GPX)
        val path = doc.firstTrackAsPath()
        assertEquals(
            doc.tracks
                .first()
                .points.size,
            path.size,
        )
    }

    @Test
    fun `case 16 — Path built from sample gpx has elevation 350_1 at index 0`() {
        val doc = GpxParser.parse(GpxFixtures.SAMPLE_GPX)
        val path = doc.firstTrackAsPath()
        assertEquals(350.1, path.elevation(0), absoluteTolerance = 1e-9)
        // Lat/lon stored in radians ; converted back to degrees should match the fixture.
        assertEquals(45.680697, path.latitude(0) * MathConstants.RAD_TO_DEG, absoluteTolerance = 1e-9)
        assertEquals(6.396115, path.longitude(0) * MathConstants.RAD_TO_DEG, absoluteTolerance = 1e-9)
        // Power copied through.
        assertEquals(45.0, path.pInputPower(0))
    }

    @Test
    fun `case 17 — Path totalDistance is positive after computeDerivedData`() {
        val doc = GpxParser.parse(GpxFixtures.SAMPLE_GPX)
        val path = doc.firstTrackAsPath()
        assertTrue(path.totalDistance > 0.0, "expected totalDistance > 0, got ${path.totalDistance}")
        // 7 points spanning ~400m of mountain road : sanity-check upper bound.
        assertTrue(path.totalDistance < 5_000.0, "totalDistance unexpectedly large: ${path.totalDistance}")
    }

    @Test
    fun `case 18 — multi-track document yields one Path for the first track only`() {
        val doc = GpxParser.parse(GpxFixtures.MULTI_TRACK_GPX)
        assertEquals(2, doc.tracks.size, "expected exactly 2 tracks in the fixture")
        val path = doc.firstTrackAsPath()
        // Only the first track (3 points) is materialised. Second track (2 points) is ignored.
        assertEquals(3, path.size)
        assertEquals(100.0, path.elevation(0))
        // Sanity : second track has lat 46°, untouched by firstTrackAsPath.
        assertEquals("first", doc.tracks[0].name)
        assertEquals("second", doc.tracks[1].name)
    }
}
