package io.github.glandais.engine.gpx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Counterexamples found by model-checking GPX repair and writing (TLC model `GpxXmlRepair.tla`),
 * each confirmed here on the real code.
 *
 * Contracts under test:
 * - [GpxXmlRepair] KDoc: "Every repair is independent and a no-op on an already-valid document:
 *   `repair(validXml) == validXml`", and each repair "fixes syntax that is *always* illegal XML 1.0,
 *   never a legitimate character". (Idempotence `repair(repair(x)) == repair(x)` HELD in TLC.)
 * - [GpxWriter] KDoc: emits GPX 1.1, and cares about what strict parsers reject (waypoints first).
 *   GPX 1.1 types `lat`/`lon`/`ele` as `xsd:decimal` (no exponent) and `wptType` is an ordered
 *   `xsd:sequence` starting `ele, time, …`.
 * - Parser comment on `roadwidth`: "files this project writes still round-trip".
 */
class FormalModelGpxIoTest {
    private fun doc(body: String): String =
        """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
  <trk>
    <name>t</name>$body
    <trkseg>
      <trkpt lat="45.0" lon="6.0"><ele>100</ele></trkpt>
    </trkseg>
  </trk>
</gpx>
"""

    /**
     * TLC counterexample to `ValidUnchanged`: `<<"<", "&", ">">>`, i.e. `<![CDATA[ & ]]>`.
     * Inside a CDATA section `&` is literal character data (XML 1.0 §2.7), so the document is
     * valid, yet `escapeBareAmpersands` scans the raw text without tracking CDATA and rewrites
     * it to `&amp;` — which inside CDATA is *five literal characters*, so the text content
     * changes from "Café & Croissant" to "Café &amp; Croissant". GPX exporters routinely wrap
     * `<desc>`/`<name>` in CDATA.
     */
    @Test
    fun repairLeavesAmpersandInsideCdataUntouched() {
        val valid = doc("\n    <desc><![CDATA[Café & Croissant]]></desc>")
        assertEquals(valid, GpxXmlRepair.repair(valid), "repair must be a no-op on a valid document")
    }

    /**
     * `GpxWriter` serialises coordinates with `Double.toString()`, which on the JVM switches to
     * scientific notation below 1e-3. A point within ~110 m of the prime meridian (London,
     * Le Havre…) or the equator is written `lon="-5.0E-4"`, which is not an `xsd:decimal`
     * (GPX 1.1 `longitudeType`), so schema-validating consumers reject the file. `CsvNumberFormat`
     * exists in this module precisely because "`Double.toString()` renders differently … and
     * switches to exponential notation".
     */
    @Test
    fun writerEmitsCoordinatesAndElevationAsXsdDecimal() {
        val p = GpxTrackPoint(latitudeDeg = 51.4778, longitudeDeg = -0.0005, elevationM = 0.0004)
        val xml = GpxWriter.write(GpxDocument(tracks = listOf(GpxTrack(points = listOf(p)))))
        val decimal = Regex("^[+-]?([0-9]+(\\.[0-9]*)?|\\.[0-9]+)$")
        val lon = Regex("lon=\"([^\"]*)\"").find(xml)!!.groupValues[1]
        val ele = Regex("<ele>([^<]*)</ele>").find(xml)!!.groupValues[1]
        assertTrue(decimal.matches(lon), "lon attribute '$lon' is not an xsd:decimal")
        assertTrue(decimal.matches(ele), "<ele> '$ele' is not an xsd:decimal")
    }

    /**
     * GPX 1.1 `wptType` (shared by `<wpt>`, `<rtept>`, `<trkpt>`) is an `xsd:sequence` whose first
     * two children are `ele` then `time`. `writeWaypoint` respects it; `writeTrackPoint` writes
     * `<time>` **before** `<ele>`, so every timestamped track the writer produces fails schema
     * validation — the very "strict parser" failure the writer reorders `<wpt>` to avoid.
     */
    @Test
    fun writerEmitsEleBeforeTimeInTrackPoints() {
        val p = GpxTrackPoint(latitudeDeg = 45.0, longitudeDeg = 6.0, elevationM = 100.0, timeEpochMs = 1_700_000_000_000L)
        val xml = GpxWriter.write(GpxDocument(tracks = listOf(GpxTrack(points = listOf(p)))))
        val ele = xml.indexOf("<ele>")
        val time = xml.indexOf("<time>")
        assertTrue(ele in 0 until time, "trkpt children out of GPX 1.1 order (ele must precede time): $xml")
    }

    /**
     * Parse → write → parse must keep the road width a file asserted. A track-level
     * `<trk><extensions><roadwidth>` is parsed into [GpxTrack.roadWidthM] and applied to every
     * point by `pointsToPath`, but `GpxWriter` only writes per-point widths (`hasRoadWidth` looks
     * at points only) and never the track default, so the re-parsed path's widths fall from 7 m to
     * NaN (engine default) — the racing-line corridor changes after a mere re-save.
     */
    @Test
    fun trackLevelRoadWidthSurvivesWriteRoundTrip() {
        val parsed = GpxParser.parse(doc("\n    <extensions><roadwidth>7</roadwidth></extensions>"))
        assertEquals(7.0, parsed.firstTrackAsPath().roadWidth(0))
        val reparsed = GpxParser.parse(GpxWriter.write(parsed))
        assertEquals(7.0, reparsed.firstTrackAsPath().roadWidth(0), "track-level road width lost by GpxWriter")
    }
}
