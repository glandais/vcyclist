package io.github.glandais.engine.gpx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Multi-track / multi-segment semantics introduced by gpx2web task g02.
 *
 * The contract under test :
 * - `<trkseg>` boundaries survive parsing as [GpxSegment]s instead of being flattened,
 * - [GpxDocument.tracksAsPaths] yields one Path per `<trk>` (segments concatenated),
 * - [GpxDocument.segmentsAsPaths] yields one Path per `<trkseg>` (never crosses a boundary),
 * - empty segments and point-less tracks never materialise a parasitic `Path(0)`,
 * - [GpxDocument.firstTrackAsPath] behaves exactly as it did before g02.
 */
class GpxMultiTrackTest {
    @Test
    fun `case 01 — single track single segment yields one Path`() {
        val doc = GpxParser.parse(GpxFixtures.SAMPLE_GPX)
        assertEquals(1, doc.tracks.size)
        assertEquals(1, doc.tracks[0].segments.size)
        assertEquals(1, doc.tracksAsPaths().size)
        assertEquals(1, doc.segmentsAsPaths().size)
    }

    @Test
    fun `case 02 — two tracks yield two Paths with the right points each`() {
        val paths = GpxParser.parse(GpxFixtures.MULTI_TRACK_GPX).tracksAsPaths()
        assertEquals(2, paths.size)
        assertEquals(3, paths[0].size, "first track has 3 trkpt")
        assertEquals(2, paths[1].size, "second track has 2 trkpt")
        // Track 1 sits at 45°/6°, track 2 at 46°/7° — no cross-contamination.
        assertEquals(45.0, paths[0].latitudeDeg(0), 1e-9)
        assertEquals(100.0, paths[0].elevation(0), 1e-9)
        assertEquals(46.0, paths[1].latitudeDeg(0), 1e-9)
        assertEquals(200.0, paths[1].elevation(0), 1e-9)
    }

    @Test
    fun `case 03 — one track with three segments yields one track Path and three segment Paths`() {
        val doc = GpxParser.parse(GpxFixtures.MULTI_SEGMENT_GPX)
        assertEquals(1, doc.tracks.size)
        assertEquals(3, doc.tracks[0].segments.size)

        val tracks = doc.tracksAsPaths()
        assertEquals(1, tracks.size)
        assertEquals(9, tracks[0].size, "3 + 2 + 4 points concatenated")

        val segments = doc.segmentsAsPaths()
        assertEquals(3, segments.size)
        assertEquals(listOf(3, 2, 4), segments.map { it.size })
    }

    @Test
    fun `case 04 — firstTrackAsPath is unchanged by g02`() {
        val doc = GpxParser.parse(GpxFixtures.MULTI_TRACK_GPX)
        val first = doc.firstTrackAsPath()
        // Same assertions as the pre-g02 `GpxParserTest case 18`.
        assertEquals(3, first.size)
        assertEquals(100.0, first.elevation(0), 1e-9)
        // And it is exactly the first entry of tracksAsPaths().
        val viaTracks = doc.tracksAsPaths()[0]
        assertEquals(viaTracks.size, first.size)
        assertEquals(viaTracks.totalDistance, first.totalDistance, 1e-9)
    }

    @Test
    fun `case 05 — an empty trkseg is skipped instead of producing a Path of size zero`() {
        val doc = GpxParser.parse(GpxFixtures.EMPTY_PARTS_GPX)
        // The model stays faithful : the empty <trkseg/> IS present after parsing…
        assertEquals(3, doc.tracks[0].segments.size)
        assertTrue(
            doc.tracks[0]
                .segments[1]
                .points
                .isEmpty(),
        )
        // …but the conversion layer drops it.
        val segments = doc.segmentsAsPaths()
        assertEquals(2, segments.size)
        assertEquals(listOf(2, 3), segments.map { it.size })
        assertTrue(segments.none { it.size == 0 }, "no parasitic Path(0)")
    }

    @Test
    fun `case 06 — a track without any point is skipped`() {
        val doc = GpxParser.parse(GpxFixtures.EMPTY_PARTS_GPX)
        assertEquals(2, doc.tracks.size, "the point-less <trk> is still in the model")
        val paths = doc.tracksAsPaths()
        assertEquals(1, paths.size, "…but yields no Path")
        assertEquals(5, paths[0].size, "2 + 3 points from the two non-empty segments")
    }

    @Test
    fun `case 07 — round-trip of two tracks preserves track and point counts`() {
        val doc = GpxParser.parse(GpxFixtures.MULTI_TRACK_GPX)
        val reparsed = GpxParser.parse(GpxWriter.write(doc))
        assertEquals(doc.tracks.size, reparsed.tracks.size)
        assertEquals(
            doc.tracks.map { it.points.size },
            reparsed.tracks.map { it.points.size },
        )
        assertEquals(2, reparsed.tracksAsPaths().size)
    }

    @Test
    fun `case 08 — round-trip preserves track names`() {
        val doc = GpxParser.parse(GpxFixtures.MULTI_TRACK_GPX)
        val reparsed = GpxParser.parse(GpxWriter.write(doc))
        assertEquals(listOf("first", "second"), reparsed.tracks.map { it.name })
    }

    @Test
    fun `case 09 — writing Paths as tracks emits one trk per Path with the given names`() {
        val paths = GpxParser.parse(GpxFixtures.MULTI_TRACK_GPX).tracksAsPaths()
        val xml = GpxWriter.write(paths, name = "doc", trackNames = listOf("a", "b"))
        val reparsed = GpxParser.parse(xml)
        assertEquals("doc", reparsed.name)
        assertEquals(listOf("a", "b"), reparsed.tracks.map { it.name })
        assertEquals(listOf(3, 2), reparsed.tracks.map { it.points.size })
    }

    @Test
    fun `case 10 — concatenating segments folds the inter-segment jump into totalDistance`() {
        val doc = GpxParser.parse(GpxFixtures.MULTI_SEGMENT_GPX)
        val trackDistance = doc.tracksAsPaths()[0].totalDistance
        val segmentsDistance = doc.segmentsAsPaths().sumOf { it.totalDistance }

        // The fixture's own riding distance : 2 + 1 + 3 steps of 0.001° latitude ≈ 667 m.
        assertEquals(667.0, segmentsDistance, 667.0 * 0.01)

        // Concatenation adds the two teleports : 0.018° ≈ 2001 m and 0.009° ≈ 1001 m.
        val jump = trackDistance - segmentsDistance
        assertEquals(3002.0, jump, 3002.0 * 0.01)
        assertTrue(
            trackDistance > segmentsDistance * 5,
            "documented artefact: tracksAsPaths() counts the pause as distance " +
                "($trackDistance m vs $segmentsDistance m of real riding)",
        )
    }

    @Test
    fun `case 11 — the writer emits one trkseg per segment`() {
        val doc = GpxParser.parse(GpxFixtures.MULTI_SEGMENT_GPX)
        val xml = GpxWriter.write(doc)
        assertEquals(3, Regex("<trkseg>").findAll(xml).count())
        val reparsed = GpxParser.parse(xml)
        assertEquals(listOf(3, 2, 4), reparsed.tracks[0].segments.map { it.points.size })
    }

    @Test
    fun `case 12 — GpxTrack points compat accessor flattens the segments in order`() {
        val track =
            GpxTrack(
                name = "t",
                segments =
                    listOf(
                        GpxSegment(listOf(GpxTrackPoint(1.0, 1.0), GpxTrackPoint(2.0, 2.0))),
                        GpxSegment(listOf(GpxTrackPoint(3.0, 3.0))),
                    ),
            )
        assertEquals(3, track.points.size)
        assertEquals(listOf(1.0, 2.0, 3.0), track.points.map { it.latitudeDeg })
        // The pre-g02 single-segment factory still works and round-trips through `points`.
        val single = GpxTrack(name = "t", points = track.points)
        assertEquals(1, single.segments.size)
        assertEquals(track.points, single.points)
    }
}
