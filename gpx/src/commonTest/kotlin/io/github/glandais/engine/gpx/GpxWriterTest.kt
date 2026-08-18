package io.github.glandais.engine.gpx

import io.github.glandais.elevation.MathConstants
import io.github.glandais.engine.path.Path
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Covers the 18 cases listed in `docs/archive/tasks/15-engine-gpx-writer.md` §3.
 *
 * Round-trip tests intentionally compare **semantically** (re-parse the output and compare
 * [GpxDocument] instances with a numeric tolerance) rather than byte-for-byte — formatting of
 * doubles and attribute order are not part of the GPX contract.
 */
class GpxWriterTest {
    private val tol = 1e-9

    // --- 1. Empty document -------------------------------------------------------------

    @Test
    fun `case 01 — empty GpxDocument (zero tracks) emits gpx + metadata but no trk`() {
        val xml = GpxWriter.write(GpxDocument(name = "empty", tracks = emptyList()))
        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertTrue(xml.contains("<gpx"), "missing <gpx root: $xml")
        assertTrue(xml.contains("<metadata>"), "missing <metadata>: $xml")
        assertTrue(xml.contains("<name>empty</name>"))
        assertFalse(xml.contains("<trk>"), "unexpected <trk> in: $xml")
    }

    // --- 2. Track with zero points -----------------------------------------------------

    @Test
    fun `case 02 — one track with zero points emits trk + empty trkseg`() {
        val xml =
            GpxWriter.write(
                GpxDocument(name = "n", tracks = listOf(GpxTrack(name = "t", points = emptyList()))),
            )
        // trkseg must be present and contain no <trkpt>.
        assertTrue(xml.contains("<trkseg"), xml)
        assertFalse(xml.contains("<trkpt"), xml)
        // Round-trip preserves the structure.
        val reparsed = GpxParser.parse(xml)
        assertEquals(1, reparsed.tracks.size)
        assertEquals(0, reparsed.tracks[0].points.size)
        assertEquals("t", reparsed.tracks[0].name)
    }

    // --- 3. Minimal trackpoint (lat/lon only) ------------------------------------------

    @Test
    fun `case 03 — minimal trkpt with lat lon only has no ele time extensions`() {
        val xml =
            GpxWriter.write(
                GpxDocument(
                    name = "n",
                    tracks =
                        listOf(
                            GpxTrack(
                                name = null,
                                type = null,
                                points =
                                    listOf(
                                        GpxTrackPoint(latitudeDeg = 45.0, longitudeDeg = 6.0),
                                    ),
                            ),
                        ),
                ),
            )
        // Double formatting differs between JVM (`45.0`) and JS (`45`) — accept both via regex.
        assertTrue(Regex("lat=\"45(?:\\.0)?\"").containsMatchIn(xml), xml)
        assertTrue(Regex("lon=\"6(?:\\.0)?\"").containsMatchIn(xml), xml)
        assertFalse(xml.contains("<ele"), xml)
        assertFalse(xml.contains("<time"), xml)
        assertFalse(xml.contains("<extensions"), xml)
    }

    // --- 4. trkpt with elevation -------------------------------------------------------

    @Test
    fun `case 04 — trkpt with elevation emits ele tag`() {
        val xml =
            GpxWriter.write(
                singleTrackDoc(
                    GpxTrackPoint(latitudeDeg = 45.0, longitudeDeg = 6.0, elevationM = 350.1),
                ),
            )
        assertTrue(xml.contains("<ele>350.1</ele>"), xml)
        // Round-trip
        val rt = GpxParser.parse(xml).tracks[0].points[0]
        assertEquals(350.1, rt.elevationM!!, tol)
    }

    // --- 5. trkpt with time ------------------------------------------------------------

    @Test
    fun `case 05 — trkpt with timeEpochMs emits ISO-8601 time tag (UTC Z)`() {
        // 2024-11-03T14:25:22.000Z → 1730643922000
        val xml =
            GpxWriter.write(
                singleTrackDoc(
                    GpxTrackPoint(
                        latitudeDeg = 45.0,
                        longitudeDeg = 6.0,
                        timeEpochMs = 1_730_643_922_000L,
                    ),
                ),
            )
        assertTrue(xml.contains("<time>"), xml)
        assertTrue(xml.contains("</time>"), xml)
        assertTrue(xml.contains("Z"), "expected UTC Z marker in: $xml")
        val rt = GpxParser.parse(xml).tracks[0].points[0]
        assertEquals(1_730_643_922_000L, rt.timeEpochMs)
    }

    // --- 6. trkpt with power -----------------------------------------------------------

    @Test
    fun `case 06 — trkpt with powerW emits power inside extensions (non-namespaced)`() {
        val xml =
            GpxWriter.write(
                singleTrackDoc(
                    GpxTrackPoint(latitudeDeg = 45.0, longitudeDeg = 6.0, powerW = 250.0),
                ),
            )
        assertTrue(xml.contains("<extensions>"), xml)
        // Double formatting differs (JVM `250.0`, JS `250`).
        assertTrue(Regex("<power>250(?:\\.0)?</power>").containsMatchIn(xml), xml)
        // Sanity: power not namespaced
        assertFalse(xml.contains("gpxtpx:power"), xml)
        val rt = GpxParser.parse(xml).tracks[0].points[0]
        assertEquals(250.0, rt.powerW!!, tol)
    }

    // --- 7. trkpt with heart rate alone -----------------------------------------------

    @Test
    fun `case 07 — trkpt with heartRate only emits gpxtpx TrackPointExtension wrapper`() {
        val xml =
            GpxWriter.write(
                singleTrackDoc(
                    GpxTrackPoint(latitudeDeg = 45.0, longitudeDeg = 6.0, heartRate = 142),
                ),
            )
        assertTrue(xml.contains("<extensions>"), xml)
        assertTrue(xml.contains("<gpxtpx:TrackPointExtension>"), xml)
        assertTrue(xml.contains("<gpxtpx:hr>142</gpxtpx:hr>"), xml)
        // cad / atemp / power not present
        assertFalse(xml.contains(":cad"), xml)
        assertFalse(xml.contains(":atemp"), xml)
        assertFalse(xml.contains("<power"), xml)
        val rt = GpxParser.parse(xml).tracks[0].points[0]
        assertEquals(142, rt.heartRate)
    }

    // --- 8. Full extensions ------------------------------------------------------------

    @Test
    fun `case 08 — full extensions hr cad atemp power render in correct nesting`() {
        val xml =
            GpxWriter.write(
                singleTrackDoc(
                    GpxTrackPoint(
                        latitudeDeg = 45.0,
                        longitudeDeg = 6.0,
                        heartRate = 150,
                        cadence = 90,
                        temperatureC = 18.5,
                        powerW = 230.0,
                    ),
                ),
            )
        assertTrue(xml.contains("<extensions>"), xml)
        assertTrue(Regex("<power>230(?:\\.0)?</power>").containsMatchIn(xml), xml)
        assertTrue(xml.contains("<gpxtpx:TrackPointExtension>"), xml)
        assertTrue(xml.contains("<gpxtpx:hr>150</gpxtpx:hr>"), xml)
        assertTrue(xml.contains("<gpxtpx:cad>90</gpxtpx:cad>"), xml)
        assertTrue(xml.contains("<gpxtpx:atemp>18.5</gpxtpx:atemp>"), xml)
        // <power> should appear OUTSIDE <gpxtpx:TrackPointExtension>, at the extensions level.
        val powerIdx = xml.indexOf("<power>")
        val tpxIdx = xml.indexOf("<gpxtpx:TrackPointExtension>")
        assertTrue(powerIdx in 0 until tpxIdx, "power must precede TrackPointExtension; xml=$xml")
        val rt = GpxParser.parse(xml).tracks[0].points[0]
        assertEquals(150, rt.heartRate)
        assertEquals(90, rt.cadence)
        assertEquals(18.5, rt.temperatureC!!, tol)
        assertEquals(230.0, rt.powerW!!, tol)
    }

    // --- 9. xmlns:gpxtpx declared on the root -----------------------------------------

    @Test
    fun `case 09 — xmlns gpxtpx and xmlns xsi declared on the gpx root`() {
        val xml = GpxWriter.write(GpxDocument(name = "x", tracks = emptyList()))
        assertTrue(
            xml.contains("xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\""),
            "missing gpxtpx ns declaration: $xml",
        )
        assertTrue(
            xml.contains("xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\""),
            "missing xsi ns declaration: $xml",
        )
        assertTrue(
            xml.contains("xmlns=\"http://www.topografix.com/GPX/1/1\""),
            "missing default ns declaration: $xml",
        )
        assertTrue(xml.contains("version=\"1.1\""), xml)
        assertTrue(xml.contains("creator=\"${GpxWriter.CREATOR}\""), xml)
    }

    // --- 10. Round-trip on a synthetic 3-point path ------------------------------------

    @Test
    fun `case 10 — round-trip parse write parse preserves all numeric fields`() {
        val xml0 =
            GpxWriter.write(
                singleTrackDoc(
                    GpxTrackPoint(
                        latitudeDeg = 45.123456789,
                        longitudeDeg = 6.987654321,
                        elevationM = 1234.5,
                        timeEpochMs = 1_700_000_000_000L,
                        heartRate = 140,
                        cadence = 85,
                        temperatureC = 14.2,
                        powerW = 220.0,
                    ),
                    GpxTrackPoint(
                        latitudeDeg = 45.123466789,
                        longitudeDeg = 6.987664321,
                        elevationM = 1235.0,
                        timeEpochMs = 1_700_000_001_000L,
                    ),
                ),
            )
        val parsed = GpxParser.parse(xml0)
        // Write again, re-parse, compare to the first parse.
        val xml1 = GpxWriter.write(parsed)
        val reparsed = GpxParser.parse(xml1)
        assertGpxDocumentsEqual(parsed, reparsed, tol)
    }

    // --- 11. Round-trip on sample.gpx fixture ------------------------------------------

    @Test
    fun `case 11 — round-trip sample gpx preserves trkpt count and first power`() {
        val parsed = GpxParser.parse(GpxFixtures.SAMPLE_GPX)
        val xml = GpxWriter.write(parsed)
        val reparsed = GpxParser.parse(xml)
        assertEquals(parsed.tracks[0].points.size, reparsed.tracks[0].points.size)
        assertEquals(parsed.tracks[0].points[0].powerW!!, reparsed.tracks[0].points[0].powerW!!, tol)
        assertGpxDocumentsEqual(parsed, reparsed, tol)
    }

    // --- 12. Round-trip on garmin.gpx fixture (hr/cad) --------------------------------

    @Test
    fun `case 12 — round-trip garmin gpx preserves hr and cad`() {
        val parsed = GpxParser.parse(GpxFixtures.GARMIN_GPX)
        val xml = GpxWriter.write(parsed)
        val reparsed = GpxParser.parse(xml)
        // Original has hr=61 cad=0 on point 0. cadence=0 is treated as "real zero" by the
        // parser (the value is present in the XML). It's preserved through the round-trip
        // because we kept it as Int.
        assertEquals(parsed.tracks[0].points[0].heartRate, reparsed.tracks[0].points[0].heartRate)
        assertEquals(parsed.tracks[0].points[0].cadence, reparsed.tracks[0].points[0].cadence)
        assertEquals(parsed.tracks[0].points[1].heartRate, reparsed.tracks[0].points[1].heartRate)
        assertEquals(parsed.tracks[0].points[1].cadence, reparsed.tracks[0].points[1].cadence)
        assertGpxDocumentsEqual(parsed, reparsed, tol)
    }

    // --- 13. Path.toGpxTrack: size + degree conversion --------------------------------

    @Test
    fun `case 13 — Path toGpxTrack preserves size and converts lat lon to degrees`() {
        val p = Path(2)
        p.setLatitude(0, 45.0 * MathConstants.DEG_TO_RAD)
        p.setLongitude(0, 6.0 * MathConstants.DEG_TO_RAD)
        p.setLatitude(1, 46.0 * MathConstants.DEG_TO_RAD)
        p.setLongitude(1, 7.0 * MathConstants.DEG_TO_RAD)
        val track = p.toGpxTrack()
        assertEquals(2, track.points.size)
        assertEquals(45.0, track.points[0].latitudeDeg, tol)
        assertEquals(6.0, track.points[0].longitudeDeg, tol)
        assertEquals(46.0, track.points[1].latitudeDeg, tol)
        assertEquals(7.0, track.points[1].longitudeDeg, tol)
        // Default type
        assertEquals("cycling", track.type)
    }

    // --- 14. Path.toGpxTrack: NaN extension field → null, genuine 0 preserved ---------

    @Test
    fun `case 14 — Path toGpxTrack treats NaN heart rate cadence temperature power as absent`() {
        val p = Path(1)
        p.setLatitude(0, 0.0)
        p.setLongitude(0, 0.0)
        // NaN is what `GpxToPath` writes for an absent sensor — should map to null.
        p.setHeartRate(0, Double.NaN)
        p.setCadence(0, Double.NaN)
        p.setTemperature(0, Double.NaN)
        p.setPInputPower(0, Double.NaN)
        val pt = p.toGpxTrack().points[0]
        assertNull(pt.heartRate, "heartRate should be null for NaN")
        assertNull(pt.cadence, "cadence should be null for NaN")
        assertNull(pt.temperatureC, "temperatureC should be null for NaN")
        assertNull(pt.powerW, "powerW should be null for NaN")
    }

    @Test
    fun `case 14b — Path toGpxTrack preserves a genuine zero reading`() {
        // 0 rpm (freewheeling), 0 W and 0 °C are real measurements, not "missing" — the writer
        // must emit them. Matches `GPXWriter.ts`, which guards on `isNaN` alone.
        val p = Path(1)
        p.setLatitude(0, 0.0)
        p.setLongitude(0, 0.0)
        p.setCadence(0, 0.0)
        p.setTemperature(0, 0.0)
        p.setPInputPower(0, 0.0)
        p.setHeartRate(0, Double.NaN)
        val pt = p.toGpxTrack().points[0]
        assertEquals(0, pt.cadence)
        assertEquals(0.0, pt.temperatureC)
        assertEquals(0.0, pt.powerW)
        assertNull(pt.heartRate)
    }

    // --- 15. Path.toGpxTrack: zero elevation preserved --------------------------------

    @Test
    fun `case 15 — Path toGpxTrack preserves zero elevation as literal 0`() {
        // Distinct from extensions: 0.0 m is a valid elevation (sea level). Preserve it.
        val p = Path(1)
        p.setLatitude(0, 45.0 * MathConstants.DEG_TO_RAD)
        p.setLongitude(0, 6.0 * MathConstants.DEG_TO_RAD)
        p.setElevation(0, 0.0)
        // time(0) == 0.0 → null per parser sentinel.
        val pt = p.toGpxTrack().points[0]
        assertEquals(0.0, pt.elevationM)
        assertNull(pt.timeEpochMs)
    }

    // --- 16. GpxWriter.write(path) produces re-parseable XML --------------------------

    @Test
    fun `case 16 — GpxWriter write Path produces XML re-parseable by GpxParser`() {
        val p = Path(3)
        for (i in 0 until 3) {
            p.setLatitude(i, (45.0 + i * 0.001) * MathConstants.DEG_TO_RAD)
            p.setLongitude(i, (6.0 + i * 0.001) * MathConstants.DEG_TO_RAD)
            p.setElevation(i, 100.0 + i * 5.0)
            p.setTime(i, (1_700_000_000_000.0 + i * 1_000.0))
            p.setHeartRate(i, (130 + i).toDouble())
            p.setPInputPower(i, 200.0 + i)
        }
        val xml = GpxWriter.write(p, name = "auto", trackName = "T")
        val doc = GpxParser.parse(xml)
        assertEquals(1, doc.tracks.size)
        assertEquals("T", doc.tracks[0].name)
        assertEquals(3, doc.tracks[0].points.size)
        assertEquals(130, doc.tracks[0].points[0].heartRate)
        assertEquals(200.0, doc.tracks[0].points[0].powerW!!, tol)
    }

    // --- 17. XML declaration on the first line ----------------------------------------

    @Test
    fun `case 17 — output starts with the XML 1 0 UTF-8 declaration`() {
        val xml = GpxWriter.write(GpxDocument(name = "x", tracks = emptyList()))
        assertTrue(
            xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"),
            "unexpected prolog: ${xml.take(60)}",
        )
    }

    // --- 18. Multi-track document --------------------------------------------------------

    @Test
    fun `case 18 — multi track document emits one trk per track`() {
        val doc =
            GpxDocument(
                name = "multi",
                tracks =
                    listOf(
                        GpxTrack(
                            name = "first",
                            points = listOf(GpxTrackPoint(latitudeDeg = 45.0, longitudeDeg = 6.0)),
                        ),
                        GpxTrack(
                            name = "second",
                            points = listOf(GpxTrackPoint(latitudeDeg = 46.0, longitudeDeg = 7.0)),
                        ),
                    ),
            )
        val xml = GpxWriter.write(doc)
        val count = countOccurrences(xml, "<trk>")
        assertEquals(2, count, "expected 2 <trk> sections, got $count in:\n$xml")
        val reparsed = GpxParser.parse(xml)
        assertEquals(2, reparsed.tracks.size)
        assertEquals("first", reparsed.tracks[0].name)
        assertEquals("second", reparsed.tracks[1].name)
    }

    // --- Bonus round-trips (covered by the spec's "4 fixtures" critère) ---------------

    @Test
    fun `case 19 — round-trip amazfit gpx`() {
        val parsed = GpxParser.parse(GpxFixtures.AMAZFIT_GPX)
        val xml = GpxWriter.write(parsed)
        val reparsed = GpxParser.parse(xml)
        assertGpxDocumentsEqual(parsed, reparsed, tol)
    }

    @Test
    fun `case 20 — round-trip movescount gpx`() {
        val parsed = GpxParser.parse(GpxFixtures.MOVESCOUNT_GPX)
        val xml = GpxWriter.write(parsed)
        val reparsed = GpxParser.parse(xml)
        assertGpxDocumentsEqual(parsed, reparsed, tol)
    }

    // --- 21-24. Waypoints (g03) ---------------------------------------------------------

    @Test
    fun `case 21 — wpt elements are written before trk elements`() {
        val doc =
            GpxDocument(
                name = "n",
                tracks = listOf(GpxTrack(name = "t", points = listOf(GpxTrackPoint(45.0, 6.0)))),
                waypoints = listOf(GpxWaypoint(latitudeDeg = 45.1, longitudeDeg = 6.1, name = "start")),
            )
        val xml = GpxWriter.write(doc)
        val wptIdx = xml.indexOf("<wpt")
        val trkIdx = xml.indexOf("<trk>")
        assertTrue(wptIdx in 0 until trkIdx, "expected <wpt> before <trk>, got:\n$xml")
    }

    @Test
    fun `case 22 — round-trip parse write parse preserves waypoints`() {
        val parsed = GpxParser.parse(GpxFixtures.WAYPOINTS_GPX)
        val xml = GpxWriter.write(parsed)
        val reparsed = GpxParser.parse(xml)
        assertEquals(parsed.waypoints.size, reparsed.waypoints.size)
        for ((a, b) in parsed.waypoints.zip(reparsed.waypoints)) {
            assertEqualsApprox(a.latitudeDeg, b.latitudeDeg, tol, "wpt.lat")
            assertEqualsApprox(a.longitudeDeg, b.longitudeDeg, tol, "wpt.lon")
            assertOptDoubleEquals(a.elevationM, b.elevationM, tol, "wpt.ele")
            assertEquals(a.name, b.name, "wpt.name")
            assertEquals(a.description, b.description, "wpt.desc")
            assertEquals(a.symbol, b.symbol, "wpt.sym")
            assertEquals(a.type, b.type, "wpt.type")
            assertEquals(a.timeEpochMs, b.timeEpochMs, "wpt.time")
        }
    }

    @Test
    fun `case 23 — minimal waypoint has no ele name desc sym type time tags`() {
        val xml =
            GpxWriter.write(
                GpxDocument(
                    name = "n",
                    tracks = emptyList(),
                    waypoints = listOf(GpxWaypoint(latitudeDeg = 45.0, longitudeDeg = 6.0)),
                ),
            )
        assertTrue(xml.contains("<wpt"), xml)
        // <metadata><name>n</name></metadata> is legitimate ; only the waypoint-local tags must
        // be absent.
        val wptBody = xml.substringAfter("<wpt")
        assertFalse(wptBody.contains("<ele"), xml)
        assertFalse(wptBody.contains("<name"), xml)
        assertFalse(wptBody.contains("<desc"), xml)
        assertFalse(wptBody.contains("<sym"), xml)
        assertFalse(wptBody.contains("<type"), xml)
        assertFalse(wptBody.contains("<time"), xml)
    }

    @Test
    fun `case 24 — pathsToGpxDocument carries waypoints through to the written GPX`() {
        val p = Path(1)
        p.setLatitude(0, 45.0 * MathConstants.DEG_TO_RAD)
        p.setLongitude(0, 6.0 * MathConstants.DEG_TO_RAD)
        val waypoints = listOf(GpxWaypoint(latitudeDeg = 45.5, longitudeDeg = 6.5, name = "checkpoint"))
        val doc = pathsToGpxDocument(listOf(p), name = "n", waypoints = waypoints)
        assertEquals(waypoints, doc.waypoints)
        val xml = GpxWriter.write(p.let { listOf(it) }, name = "n", waypoints = waypoints)
        val reparsed = GpxParser.parse(xml)
        assertEquals(1, reparsed.waypoints.size)
        assertEquals("checkpoint", reparsed.waypoints[0].name)
    }

    // --- 25-30. `startTime` (g05) --------------------------------------------------------

    @Test
    fun `case 25 — startTime null keeps the pre-g05 behaviour (no absolute time forced)`() {
        // A raw (non-virtualized) path with elevation but no time field set at all : time(i) stays
        // at the GeneratedPath default (0.0), so the pre-g05 sentinel logic already emits no <time>.
        val p = Path(2)
        p.setLatitude(0, 45.0 * MathConstants.DEG_TO_RAD)
        p.setLongitude(0, 6.0 * MathConstants.DEG_TO_RAD)
        p.setLatitude(1, 45.001 * MathConstants.DEG_TO_RAD)
        p.setLongitude(1, 6.001 * MathConstants.DEG_TO_RAD)
        val withoutStartTime = GpxWriter.write(p, name = "n")
        val withExplicitNull = GpxWriter.write(p, name = "n", startTime = null)
        assertEquals(withoutStartTime, withExplicitNull, "default arg must match an explicit null")
        assertFalse(withoutStartTime.contains("<time"), withoutStartTime)
    }

    @Test
    fun `case 26 — startTime T stamps every point at T plus time i ms`() {
        val p = Path(3)
        for (i in 0 until 3) {
            p.setLatitude(i, (45.0 + i * 0.001) * MathConstants.DEG_TO_RAD)
            p.setLongitude(i, (6.0 + i * 0.001) * MathConstants.DEG_TO_RAD)
            p.setTime(i, i * 1_000.0) // relative clock: 0 ms, 1000 ms, 2000 ms
        }
        val t = Instant.parse("2026-07-27T08:00:00Z")
        val xml = GpxWriter.write(p, name = "n", startTime = t)
        val reparsed = GpxParser.parse(xml)
        val points = reparsed.tracks[0].points
        assertEquals(3, points.size)
        for (i in 0 until 3) {
            assertEquals(
                t.toEpochMilliseconds() + i * 1_000L,
                points[i].timeEpochMs,
                "point $i timestamp mismatch",
            )
        }
    }

    @Test
    fun `case 27 — point 0 gets exactly startTime when time 0 is 0`() {
        val p = Path(1)
        p.setLatitude(0, 45.0 * MathConstants.DEG_TO_RAD)
        p.setLongitude(0, 6.0 * MathConstants.DEG_TO_RAD)
        p.setTime(0, 0.0)
        val t = Instant.parse("2026-07-27T08:00:00Z")
        val pt = p.toGpxTrack(startTime = t).points[0]
        assertEquals(t.toEpochMilliseconds(), pt.timeEpochMs)
    }

    @Test
    fun `case 28 — ISO 8601 UTC time tag format is schema-conformant`() {
        val p = Path(1)
        p.setLatitude(0, 45.0 * MathConstants.DEG_TO_RAD)
        p.setLongitude(0, 6.0 * MathConstants.DEG_TO_RAD)
        val t = Instant.parse("2026-07-27T08:00:00Z")
        val xml = GpxWriter.write(p, name = "n", startTime = t)
        // xs:dateTime with a UTC "Z" designator, e.g. 2026-07-27T08:00:00Z.
        assertTrue(
            Regex("<time>\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z</time>").containsMatchIn(xml),
            xml,
        )
    }

    @Test
    fun `case 29 — non integer time i ms rounds per point without cumulative drift`() {
        val p = Path(3)
        for (i in 0 until 3) {
            p.setLatitude(i, (45.0 + i * 0.001) * MathConstants.DEG_TO_RAD)
            p.setLongitude(i, (6.0 + i * 0.001) * MathConstants.DEG_TO_RAD)
        }
        // Non-integer relative times: each rounds independently (0.5 rounds to nearest even/up per
        // roundToLong, but the point is there is no *cumulative* drift — point 2 is not off by more
        // than half a millisecond from point 1's rounding error).
        p.setTime(0, 0.3)
        p.setTime(1, 1_500.7)
        p.setTime(2, 3_000.2)
        val t = Instant.parse("2026-07-27T08:00:00Z")
        val xml = GpxWriter.write(p, name = "n", startTime = t)
        val points = GpxParser.parse(xml).tracks[0].points
        assertEquals(t.toEpochMilliseconds() + 0L, points[0].timeEpochMs) // 0.3 rounds to 0
        assertEquals(t.toEpochMilliseconds() + 1_501L, points[1].timeEpochMs) // 1500.7 rounds to 1501
        assertEquals(t.toEpochMilliseconds() + 3_000L, points[2].timeEpochMs) // 3000.2 rounds to 3000
    }

    @Test
    fun `case 30 — output time tags are strictly monotonic when path time is`() {
        val p = Path(5)
        for (i in 0 until 5) {
            p.setLatitude(i, (45.0 + i * 0.001) * MathConstants.DEG_TO_RAD)
            p.setLongitude(i, (6.0 + i * 0.001) * MathConstants.DEG_TO_RAD)
            p.setTime(i, i * 1_234.5) // strictly increasing, non-integer step
        }
        val t = Instant.parse("2026-07-27T08:00:00Z")
        val xml = GpxWriter.write(p, name = "n", startTime = t)
        val points = GpxParser.parse(xml).tracks[0].points
        for (i in 1 until points.size) {
            assertTrue(
                points[i].timeEpochMs!! > points[i - 1].timeEpochMs!!,
                "time must strictly increase at $i: ${points[i - 1].timeEpochMs} -> ${points[i].timeEpochMs}",
            )
        }
    }

    // ---------- Helpers --------------------------------------------------------------

    private fun singleTrackDoc(vararg points: GpxTrackPoint): GpxDocument =
        GpxDocument(
            name = "n",
            tracks = listOf(GpxTrack(name = null, type = null, points = points.toList())),
        )

    private fun assertGpxDocumentsEqual(
        a: GpxDocument,
        b: GpxDocument,
        tol: Double,
    ) {
        assertEquals(a.name, b.name, "document name mismatch")
        assertEquals(a.tracks.size, b.tracks.size, "tracks size mismatch")
        for ((i, ta) in a.tracks.withIndex()) {
            val tb = b.tracks[i]
            assertEquals(ta.name, tb.name, "track[$i].name mismatch")
            assertEquals(ta.type, tb.type, "track[$i].type mismatch")
            assertEquals(ta.points.size, tb.points.size, "track[$i].points size mismatch")
            for ((j, pa) in ta.points.withIndex()) {
                val pb = tb.points[j]
                assertEqualsApprox(pa.latitudeDeg, pb.latitudeDeg, tol, "tr$i.pt$j.lat")
                assertEqualsApprox(pa.longitudeDeg, pb.longitudeDeg, tol, "tr$i.pt$j.lon")
                assertOptDoubleEquals(pa.elevationM, pb.elevationM, tol, "tr$i.pt$j.ele")
                assertEquals(pa.timeEpochMs, pb.timeEpochMs, "tr$i.pt$j.time")
                assertEquals(pa.heartRate, pb.heartRate, "tr$i.pt$j.hr")
                assertEquals(pa.cadence, pb.cadence, "tr$i.pt$j.cad")
                assertOptDoubleEquals(pa.temperatureC, pb.temperatureC, tol, "tr$i.pt$j.temp")
                assertOptDoubleEquals(pa.powerW, pb.powerW, tol, "tr$i.pt$j.power")
            }
        }
    }

    private fun assertEqualsApprox(
        expected: Double,
        actual: Double,
        tol: Double,
        msg: String,
    ) {
        assertTrue(
            abs(expected - actual) <= tol,
            "$msg: expected≈$expected got=$actual (Δ=${abs(expected - actual)}, tol=$tol)",
        )
    }

    private fun assertOptDoubleEquals(
        expected: Double?,
        actual: Double?,
        tol: Double,
        msg: String,
    ) {
        if (expected == null) {
            assertNull(actual, "$msg expected null, got $actual")
            return
        }
        assertNotNull(actual, "$msg expected $expected, got null")
        assertEqualsApprox(expected, actual, tol, msg)
    }

    private fun countOccurrences(
        haystack: String,
        needle: String,
    ): Int {
        var count = 0
        var idx = 0
        while (true) {
            val next = haystack.indexOf(needle, idx)
            if (next < 0) return count
            count++
            idx = next + needle.length
        }
    }
}
