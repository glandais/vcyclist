package io.github.glandais.engine.gpx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Covers [GpxDocument.startTime] (task g05) : recovering the source document's absolute start
 * instant, and reusing it on a write round-trip.
 */
class GpxToPathTest {
    @Test
    fun `case 06 — round-trip parse timestamped GPX, recover startTime, rebase, write, timestamps identical to the ms`() {
        // `GpxDocument.startTime` recovers the *source* file's absolute start instant. Reusing it
        // through `GpxWriter.write(..., startTime = ...)` is meant for a Path whose `time` field is
        // the engine's *relative* clock (time(0) == 0, as `VirtualizeService` produces) — that is
        // the shape the CLI / JS façade round-trip through `enhance` operate on. A path built
        // straight from `GpxToPath` (no `enhance` in between) still carries the *absolute* epoch ms
        // verbatim (see `GpxToPath.pointsToPath`), so this test rebases it to relative first, the
        // same transformation `VirtualizeService` performs, to exercise the actual g05 contract :
        // startTime (recovered) + relative time(i) == original absolute timestamp.
        val parsed = GpxParser.parse(GpxFixtures.SAMPLE_GPX)
        val recoveredStartTime = parsed.startTime
        // sample.gpx is a real, timestamped export : it must have a recoverable start time.
        assertNotNull(recoveredStartTime)
        val startTimeMs = recoveredStartTime.toEpochMilliseconds()

        val absolutePath = parsed.firstTrackAsPath()
        for (i in 0 until absolutePath.size) {
            absolutePath.setTime(i, absolutePath.time(i) - startTimeMs)
        }

        val xml = GpxWriter.write(absolutePath, name = "roundtrip", startTime = recoveredStartTime)
        val reparsed = GpxParser.parse(xml)
        val originalPoints = parsed.tracks.first().points
        val roundTripPoints = reparsed.tracks.first().points
        assertEquals(originalPoints.size, roundTripPoints.size)
        for ((i, original) in originalPoints.withIndex()) {
            assertEquals(original.timeEpochMs, roundTripPoints[i].timeEpochMs, "point $i timestamp drifted")
        }
    }

    @Test
    fun `case 07 — GpxDocument startTime is null when no trkpt is timestamped`() {
        val doc =
            GpxDocument(
                name = "n",
                tracks =
                    listOf(
                        GpxTrack(
                            name = "t",
                            points =
                                listOf(
                                    GpxTrackPoint(latitudeDeg = 45.0, longitudeDeg = 6.0),
                                    GpxTrackPoint(latitudeDeg = 45.1, longitudeDeg = 6.1),
                                ),
                        ),
                    ),
            )
        assertNull(doc.startTime)
    }

    @Test
    fun `GpxDocument startTime picks the first timestamped point even if earlier points are untimed`() {
        val doc =
            GpxDocument(
                name = "n",
                tracks =
                    listOf(
                        GpxTrack(
                            name = "t",
                            points =
                                listOf(
                                    GpxTrackPoint(latitudeDeg = 45.0, longitudeDeg = 6.0),
                                    GpxTrackPoint(latitudeDeg = 45.1, longitudeDeg = 6.1, timeEpochMs = 1_700_000_000_000L),
                                    GpxTrackPoint(latitudeDeg = 45.2, longitudeDeg = 6.2, timeEpochMs = 1_700_000_001_000L),
                                ),
                        ),
                    ),
            )
        assertEquals(1_700_000_000_000L, doc.startTime?.toEpochMilliseconds())
    }

    @Test
    fun `empty document has null startTime`() {
        assertNull(GpxDocument(name = "n", tracks = emptyList()).startTime)
    }
}
