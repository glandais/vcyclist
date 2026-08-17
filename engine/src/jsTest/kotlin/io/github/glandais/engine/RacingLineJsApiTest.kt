@file:OptIn(kotlin.js.ExperimentalJsExport::class)

package io.github.glandais.engine

import io.github.glandais.engine.gpx.GpxFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The racing-line report as JavaScript sees it.
 *
 * The point of the export is that a caller can ask what the stage *would* do without applying it,
 * so these tests check the shape and the read-only contract rather than the physics — which
 * `commonTest` already pins.
 */
class RacingLineJsApiTest {
    @Test
    fun `analyzeRacingLine returns arrays sized to the path`() {
        val path = parseGpx(GpxFixtures.MULTI_SEGMENT_GPX)
        val report = analyzeRacingLine(path)
        assertNotNull(report, "fixture should be analysable")
        assertEquals(path.size, report.size)
        assertEquals(path.size, report.lateralOffsetM.size)
        assertEquals(path.size, report.corridorLo.size)
        assertEquals(path.size, report.corridorHi.size)
        assertEquals(path.size, report.centerlineCurvature.size)
        assertEquals(path.size, report.trajectoryCurvature.size)
        assertEquals(path.size, report.roadHalfWidthM.size)
    }

    @Test
    fun `the solved line stays inside the corridor it reports`() {
        val path = parseGpx(GpxFixtures.MULTI_SEGMENT_GPX)
        val report = analyzeRacingLine(path)
        assertNotNull(report)
        for (i in 0 until report.size) {
            val n = report.lateralOffsetM[i]
            assertTrue(
                n >= report.corridorLo[i] - 1e-9 && n <= report.corridorHi[i] + 1e-9,
                "station $i outside its corridor",
            )
        }
    }

    @Test
    fun `corner indices point inside the path`() {
        val path = parseGpx(GpxFixtures.MULTI_SEGMENT_GPX)
        val report = analyzeRacingLine(path)
        assertNotNull(report)
        for (corner in report.corners) {
            assertTrue(corner.fromIndex in 0 until report.size, "fromIndex ${corner.fromIndex}")
            assertTrue(corner.untilIndex in 1..report.size, "untilIndex ${corner.untilIndex}")
            assertTrue(corner.apexIndex in corner.fromIndex until corner.untilIndex, "apex outside span")
            assertTrue(corner.kind in setOf("GENTLE", "CORNER", "HAIRPIN"), "unknown kind ${corner.kind}")
            assertTrue(corner.direction == 1 || corner.direction == -1, "direction ${corner.direction}")
        }
    }

    /** Asking the question must never move anything — that is the whole point of the export. */
    @Test
    fun `analysis does not modify the path`() {
        val path = parseGpx(GpxFixtures.MULTI_SEGMENT_GPX)
        val before = DoubleArray(path.size) { path.latitude(it) }
        analyzeRacingLine(path)
        for (i in 0 until path.size) {
            assertTrue(path.latitude(i) == before[i], "analysis moved station $i")
        }
    }

    /**
     * A path too short to fit a regression window declines rather than guessing — and
     * `SAMPLE_GPX`'s seven points are exactly that, which is why the other tests use a longer
     * fixture. Worth pinning: `null` is the contract, not an error.
     */
    @Test
    fun `a path too short to analyse returns null`() {
        val short = parseGpx(GpxFixtures.SAMPLE_GPX)
        assertTrue(short.size < 8, "fixture unexpectedly grew to ${short.size} points")
        assertTrue(analyzeRacingLine(short) == null, "a 7-point path should decline")
    }

    @Test
    fun `the corridor mode reaches the report`() {
        val path = parseGpx(GpxFixtures.MULTI_SEGMENT_GPX)
        val lane = js("({ racingLineCorridor: 'lane' })").unsafeCast<EnhanceOptionsDto>()
        val full = js("({ racingLineCorridor: 'full-road' })").unsafeCast<EnhanceOptionsDto>()
        val laneReport = analyzeRacingLine(path, lane)
        val fullReport = analyzeRacingLine(path, full)
        assertNotNull(laneReport)
        assertNotNull(fullReport)
        assertTrue(
            fullReport.maxCorridorWidthM > laneReport.maxCorridorWidthM,
            "full-road (${fullReport.maxCorridorWidthM}) should exceed lane (${laneReport.maxCorridorWidthM})",
        )
    }
}
