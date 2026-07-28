package io.github.glandais.engine.gpx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Task g23: `writeExtensions` on every [GpxWriter] entry point.
 *
 * The load-bearing case is the first one — with the flag at its default the output must be
 * identical **to the byte**, because that is the promise made to every existing caller.
 */
class GpxWriterExtensionsTest {
    private fun richDocument(): GpxDocument =
        GpxDocument(
            name = "rich",
            tracks =
                listOf(
                    GpxTrack(
                        name = "t",
                        points =
                            listOf(
                                GpxTrackPoint(
                                    latitudeDeg = 45.0,
                                    longitudeDeg = 6.0,
                                    elevationM = 1000.0,
                                    timeEpochMs = 1_714_550_400_000L,
                                    heartRate = 142,
                                    cadence = 88,
                                    temperatureC = 17.5,
                                    powerW = 220.0,
                                ),
                                GpxTrackPoint(
                                    latitudeDeg = 45.001,
                                    longitudeDeg = 6.001,
                                    elevationM = 1010.0,
                                    timeEpochMs = 1_714_550_430_000L,
                                    powerW = 235.0,
                                ),
                            ),
                    ),
                ),
            waypoints =
                listOf(
                    GpxWaypoint(
                        latitudeDeg = 45.002,
                        longitudeDeg = 6.002,
                        elevationM = 1020.0,
                        name = "col",
                        symbol = "Summit",
                        type = "waypoint",
                    ),
                ),
        )

    @Test
    fun `case 01 — the default is byte-identical to the implicit call`() {
        val document = richDocument()
        assertEquals(GpxWriter.write(document), GpxWriter.write(document, writeExtensions = true))
    }

    @Test
    fun `case 02 — writeExtensions false drops extensions and the gpxtpx namespace`() {
        val xml = GpxWriter.write(richDocument(), writeExtensions = false)

        assertFalse(xml.contains("<extensions>"), "unexpected <extensions>: $xml")
        assertFalse(xml.contains("gpxtpx"), "unexpected gpxtpx namespace: $xml")
        assertFalse(xml.contains("TrackPointExtension"), xml)
        // xsi stays: it carries schemaLocation, which is about GPX itself, not extensions.
        assertTrue(xml.contains("xsi:schemaLocation"), "schemaLocation must survive: $xml")
    }

    @Test
    fun `case 03 — the four sensor values disappear, nothing else does`() {
        val xml = GpxWriter.write(richDocument(), writeExtensions = false)

        for (dropped in listOf("<power>", "220", "142", "88", "17.5")) {
            assertFalse(xml.contains(dropped), "'$dropped' should be gone: $xml")
        }
        // Geometry is untouched. Compared through the parser, not as text: `Double.toString`
        // renders 45.0 as "45" on Kotlin/JS and "45.0" on the JVM.
        val point = GpxParser.parse(xml).tracks[0].points[0]
        assertEquals(45.0, point.latitudeDeg, 1e-9)
        assertEquals(6.0, point.longitudeDeg, 1e-9)
    }

    @Test
    fun `case 04 — ele and time are standard GPX and survive`() {
        val xml = GpxWriter.write(richDocument(), writeExtensions = false)

        assertTrue(xml.contains("<ele>"), xml)
        assertTrue(xml.contains("<time>2024-05-01T08:00:00Z</time>"), xml)
        // Value via the parser — see the note in case 03 on Double.toString across targets.
        assertEquals(
            1000.0,
            GpxParser
                .parse(xml)
                .tracks[0]
                .points[0]
                .elevationM!!,
            1e-9,
        )
    }

    @Test
    fun `case 05 — waypoint sym and type are standard GPX and survive`() {
        val xml = GpxWriter.write(richDocument(), writeExtensions = false)

        assertTrue(xml.contains("<sym>Summit</sym>"), xml)
        assertTrue(xml.contains("<type>waypoint</type>"), xml)
        assertTrue(xml.contains("<name>col</name>"), xml)
    }

    @Test
    fun `case 06 — round-trip keeps geometry and time, loses only the sensors`() {
        val xml = GpxWriter.write(richDocument(), writeExtensions = false)
        val reparsed = GpxParser.parse(xml)
        val source = richDocument().tracks[0].points
        val points = reparsed.tracks[0].points

        assertEquals(source.size, points.size)
        for (i in points.indices) {
            assertEquals(source[i].latitudeDeg, points[i].latitudeDeg, 1e-9, "lat $i")
            assertEquals(source[i].longitudeDeg, points[i].longitudeDeg, 1e-9, "lon $i")
            assertEquals(source[i].elevationM, points[i].elevationM, "ele $i")
            assertEquals(source[i].timeEpochMs, points[i].timeEpochMs, "time $i")
            assertEquals(null, points[i].powerW, "power $i")
            assertEquals(null, points[i].heartRate, "hr $i")
            assertEquals(null, points[i].cadence, "cad $i")
            assertEquals(null, points[i].temperatureC, "temp $i")
        }
    }

    @Test
    fun `case 07 — the bare output is still well-formed GPX`() {
        val xml = GpxWriter.write(richDocument(), writeExtensions = false)
        val reparsed = GpxParser.parse(xml)

        assertEquals("rich", reparsed.name)
        assertEquals(1, reparsed.tracks.size)
        assertEquals(1, reparsed.waypoints.size)
    }

    @Test
    fun `case 08 — the flag reaches the Path and List Path overloads`() {
        val document = richDocument()
        val path = document.tracks[0].toPath()

        val withExt = GpxWriter.write(path, name = "n", trackName = "t")
        val without = GpxWriter.write(path, name = "n", trackName = "t", writeExtensions = false)
        val withoutMulti = GpxWriter.write(listOf(path), name = "n", writeExtensions = false)

        assertTrue(withExt.contains("gpxtpx"), "the Path overload must default to extensions: $withExt")
        assertFalse(without.contains("gpxtpx"), without)
        assertFalse(withoutMulti.contains("<extensions>"), withoutMulti)
    }

    @Test
    fun `case 09 — a bare file is smaller than the same file with extensions`() {
        val document = richDocument()

        val full = GpxWriter.write(document)
        val bare = GpxWriter.write(document, writeExtensions = false)

        assertTrue(bare.length < full.length, "bare=${bare.length} full=${full.length}")
    }

    @Test
    fun `case 10 — a document with no sensor data is unchanged by the flag except for the namespace`() {
        val plain =
            GpxDocument(
                name = "plain",
                tracks =
                    listOf(
                        GpxTrack(
                            name = "t",
                            points = listOf(GpxTrackPoint(latitudeDeg = 45.0, longitudeDeg = 6.0, elevationM = 1000.0)),
                        ),
                    ),
            )

        val full = GpxWriter.write(plain)
        val bare = GpxWriter.write(plain, writeExtensions = false)

        // Same content; the only difference is the now-pointless namespace declaration.
        assertEquals(full.replace(" xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\"", ""), bare)
    }
}
