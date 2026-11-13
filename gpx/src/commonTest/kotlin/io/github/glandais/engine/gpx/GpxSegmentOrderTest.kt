package io.github.glandais.engine.gpx

import io.github.glandais.elevation.Distance
import io.github.glandais.elevation.LatLon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GpxSegmentOrderTest {
    /** A straight piece heading north from [startLat], [count] points 100 m apart (≈ 0.0009°). */
    private fun piece(
        startLat: Double,
        count: Int = 5,
    ): List<GpxTrackPoint> = List(count) { GpxTrackPoint(startLat + it * 0.0009, 6.0) }

    /** Five pieces end to end: piece k covers [45 + 0.0036 k, 45 + 0.0036 (k + 1)], ≈ 400 m each. */
    private val chain: List<List<GpxTrackPoint>> = List(5) { piece(45.0 + it * 0.0036) }

    private fun trackOf(segments: List<List<GpxTrackPoint>>) = GpxTrack(name = "t", segments = segments.map { GpxSegment(it) })

    private fun distance(
        a: GpxTrackPoint,
        b: GpxTrackPoint,
    ) = Distance.haversine(LatLon(a.latitudeDeg, a.longitudeDeg), LatLon(b.latitudeDeg, b.longitudeDeg))

    private fun maxJumpM(points: List<GpxTrackPoint>): Double = points.zipWithNext { a, b -> distance(a, b) }.maxOrNull() ?: 0.0

    @Test
    fun `segments already in order come back as the same instance`() {
        val track = trackOf(chain)
        assertSame(track, track.orderedSegments())
        val doc = GpxDocument(tracks = listOf(track))
        assertSame(doc, doc.withOrderedSegments())
    }

    @Test
    fun `the planner keeps pieces already end to end in their order and direction`() {
        val plan = planSegmentOrder(chain)
        assertEquals(listOf(0, 1, 2, 3, 4), plan.order)
        assertEquals(List(5) { false }, plan.reversed)
        assertTrue(plan.gapM < 1.0, "gap ${plan.gapM}")
    }

    @Test
    fun `shuffled and reversed segments are chained with no jump`() {
        val shuffled = listOf(chain[3], chain[0].reversed(), chain[4], chain[2].reversed(), chain[1])
        val track = trackOf(shuffled)
        assertTrue(maxJumpM(track.points) > 1000.0)

        val ordered = track.orderedSegments()
        assertEquals(5, ordered.segments.size, "each segment stays a segment")
        assertEquals(track.points.size, ordered.points.size)
        assertTrue(maxJumpM(ordered.points) < 101.0, "max jump ${maxJumpM(ordered.points)}")
        assertEquals("t", ordered.name)
    }

    @Test
    fun `a small gain leaves the track unchanged`() {
        // Three 11 m pieces 11 m apart, the last two swapped: the reorder saves ≈ 44 m only — the
        // shape of a ride recorded with pauses, not of a shuffled export.
        fun short(lat: Double) = listOf(GpxTrackPoint(lat, 6.0), GpxTrackPoint(lat + 0.0001, 6.0))
        val a = short(45.0)
        val b = short(45.0002)
        val c = short(45.0004)
        val track = trackOf(listOf(a, c, b))
        val plan = planSegmentOrder(listOf(a, c, b))
        val fileGap = segmentOrderGapM(listOf(a, c, b), listOf(0, 1, 2), listOf(false, false, false))
        assertTrue(fileGap - plan.gapM in 1.0..200.0, "gain ${fileGap - plan.gapM}")
        assertSame(track, track.orderedSegments())
        // …but a lower threshold lets it through.
        val ordered = track.orderedSegments(SegmentOrderOptions(minGainM = 0.0))
        assertEquals(listOf(a, b, c), ordered.segments.map { it.points })
    }

    @Test
    fun `more segments than the limit are left alone`() {
        val shuffled = listOf(chain[3], chain[0], chain[4], chain[2], chain[1])
        val track = trackOf(shuffled)
        assertSame(track, track.orderedSegments(SegmentOrderOptions(maxSegments = 4)))
    }

    @Test
    fun `empty segments are ignored, and dropped from a rewritten track`() {
        val track = trackOf(listOf(emptyList(), chain[1], emptyList(), chain[0]))
        val ordered = track.orderedSegments()
        assertEquals(listOf(chain[0], chain[1]), ordered.segments.map { it.points })

        val inOrder = trackOf(listOf(chain[0], emptyList(), chain[1]))
        assertSame(inOrder, inOrder.orderedSegments())
    }

    @Test
    fun `routes and single segments are untouched`() {
        val route = GpxTrack(points = chain[0], kind = GpxPathKind.ROUTE)
        assertSame(route, route.orderedSegments())
        assertEquals(SegmentOrderPlan(listOf(0), listOf(false), 0.0), planSegmentOrder(listOf(chain[0])))
    }

    @Test
    fun `the planner refuses what it cannot plan`() {
        assertFailsWith<IllegalArgumentException> { planSegmentOrder(emptyList()) }
        assertFailsWith<IllegalArgumentException> { planSegmentOrder(listOf(chain[0], emptyList())) }
        assertFailsWith<IllegalArgumentException> { planSegmentOrder(chain, maxPieces = 4) }
        assertFailsWith<IllegalArgumentException> { planSegmentOrder(chain, maxPieces = 21) }
        assertFailsWith<IllegalArgumentException> { SegmentOrderOptions(maxSegments = 0) }
        assertFailsWith<IllegalArgumentException> { SegmentOrderOptions(minGainM = -1.0) }
    }

    @Test
    fun `the AF3V export is put back end to end`() {
        val doc = GpxParser.parse(GpxFixtures.UNORDERED_SEGMENTS_GPX)
        val track = doc.tracks.single()
        assertEquals(9, track.segments.size)
        assertEquals(511, track.points.size)
        assertTrue(maxJumpM(track.points) > 5_000.0)

        val ordered = doc.withOrderedSegments()
        val orderedTrack = ordered.tracks.single()
        assertEquals(9, orderedTrack.segments.size)
        assertEquals(511, orderedTrack.points.size)
        // The file itself spaces some points up to ≈ 550 m apart inside a segment, so the property
        // is about the joins: every one short, and no jump left that a segment does not already hold.
        val joins = orderedTrack.segments.zipWithNext { x, y -> distance(x.points.last(), y.points.first()) }
        assertTrue(joins.all { it <= 100.0 }, "joins $joins")
        val worstInside = orderedTrack.segments.maxOf { maxJumpM(it.points) }
        assertEquals(worstInside, maxJumpM(orderedTrack.points), 1e-9)
        assertEquals(track.name, orderedTrack.name)
        assertEquals(doc.name, ordered.name)

        val pieces = track.segments.map { it.points }
        val plan = planSegmentOrder(pieces)
        // Piece 0 is pinned forward, so the chain runs Le Puy → Langogne-wards from the file's
        // first segment: the 4–3–8 head and the 5–6–7 tail come out reversed.
        assertEquals(listOf(4, 3, 8, 0, 1, 2, 5, 6, 7), plan.order)
        assertEquals(listOf(true, true, true, false, false, false, true, true, true), plan.reversed)
        val fileGap = segmentOrderGapM(pieces, pieces.indices.toList(), List(pieces.size) { false })
        assertTrue(fileGap > 10_000.0, "file gap $fileGap")
        assertTrue(plan.gapM < 100.0, "planned gap ${plan.gapM}")
    }

    @Test
    fun `ordering is idempotent`() {
        val doc = GpxParser.parse(GpxFixtures.UNORDERED_SEGMENTS_GPX)
        val once = doc.withOrderedSegments()
        assertSame(once, once.withOrderedSegments())
        val shuffled = trackOf(listOf(chain[2], chain[0].reversed(), chain[1]))
        val ordered = shuffled.orderedSegments()
        assertSame(ordered, ordered.orderedSegments())
    }

    @Test
    fun `paths follow the option, off by default`() {
        val doc = GpxParser.parse(GpxFixtures.UNORDERED_SEGMENTS_GPX)
        val raw = doc.tracksAsPaths().single()
        val ordered = doc.tracksAsPaths(orderSegments = true).single()
        assertEquals(raw.size, ordered.size)
        assertTrue(
            ordered.totalDistance < raw.totalDistance - 10_000.0,
            "ordered ${ordered.totalDistance} vs raw ${raw.totalDistance}",
        )
        assertEquals(raw.totalDistance, doc.tracksAsPaths(orderSegments = false).single().totalDistance, 1e-9)
        assertEquals(ordered.totalDistance, doc.firstTrackAsPath(orderSegments = true).totalDistance, 1e-9)
        assertEquals(raw.totalDistance, doc.firstTrackAsPath().totalDistance, 1e-9)
        assertEquals(9, doc.segmentsAsPaths(orderSegments = true).size)
    }
}
