package io.github.glandais.engine.gpx

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * End-to-end consequence of the CDATA counterexample of `GpxXmlRepair.tla` through
 * [GpxParser.parse] (JVM only: like `GpxXmlRepairParseTest`, it needs the strict JVM reader to
 * reject the bare `&` so that the repair retry runs).
 *
 * A file with one bare `&` somewhere (the case the repair exists for) and a CDATA description
 * elsewhere: the retry escapes the CDATA `&` too, and the waypoint description comes back as
 * "Ravito &amp; eau" — data corruption on a file the repair was meant to rescue.
 */
class FormalModelGpxRepairParseTest {
    @Test
    fun repairRetryKeepsCdataTextVerbatim() {
        val xml =
            """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
  <wpt lat="45.0" lon="6.0"><name>Col</name><desc><![CDATA[Ravito & eau]]></desc></wpt>
  <trk>
    <name>Café & Croissant</name>
    <trkseg><trkpt lat="45.0" lon="6.0"><ele>100</ele></trkpt></trkseg>
  </trk>
</gpx>
"""
        val doc = GpxParser.parse(xml)
        assertEquals("Café & Croissant", doc.tracks[0].name)
        assertEquals("Ravito & eau", doc.waypoints[0].description)
    }
}
