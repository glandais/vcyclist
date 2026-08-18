package io.github.glandais.fit

import io.github.glandais.elevation.MathConstants
import io.github.glandais.engine.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Task g25b: several [Path]s in one FIT file — one lap and one `TIMER`/`START`…`STOP` event pair
 * per path, which is how FIT expresses several rides in one file.
 */
class PathToFitMultiTest {
    private val start = Instant.parse("2026-07-28T08:00:00Z")

    private fun path(
        points: Int = 3,
        latBase: Double = 45.68,
        stepMs: Double = 10_000.0,
    ): Path {
        val p = Path(points)
        for (i in 0 until points) {
            p.setLatitude(i, (latBase + i * 0.001) * MathConstants.DEG_TO_RAD)
            p.setLongitude(i, (6.39 + i * 0.001) * MathConstants.DEG_TO_RAD)
            p.setElevation(i, 350.0 + i)
            p.setTime(i, i * stepMs)
        }
        p.computeDerivedData()
        return p
    }

    @Test
    fun `case 01 — one segment, one lap per path`() {
        val course = listOf(path(), path(latBase = 46.0), path(latBase = 47.0)).toFitCourse("multi", start)

        assertEquals(3, course.segments.size)
        assertEquals(9, course.records.size, "records of all segments, in order")
        assertEquals(3, course.segments.map { it.lap }.size)
    }

    @Test
    fun `case 02 — with no gap the segments run straight on`() {
        val course = listOf(path(), path(latBase = 46.0)).toFitCourse("multi", start)

        val firstEnd =
            course.segments[0]
                .records
                .last()
                .timestamp
        val secondStart =
            course.segments[1]
                .records
                .first()
                .timestamp
        assertEquals(firstEnd, secondStart, "interPathGap = ZERO means contiguous")
        assertTrue(
            course.records.zipWithNext().all { (a, b) -> a.timestamp <= b.timestamp },
            "timestamps must stay monotonic across the whole file",
        )
    }

    @Test
    fun `case 03 — interPathGap shifts the following segment`() {
        val course =
            listOf(path(), path(latBase = 46.0)).toFitCourse("multi", start, interPathGap = 5.minutes)

        val firstEnd =
            course.segments[0]
                .records
                .last()
                .timestamp
        val secondStart =
            course.segments[1]
                .records
                .first()
                .timestamp
        assertEquals(300_000L, secondStart.toEpochMilliseconds() - firstEnd.toEpochMilliseconds())
    }

    @Test
    fun `case 04 — each segment is rebased on its own first point`() {
        // Second path carries an absolute clock, as GpxToPath would produce.
        val absolute = path()
        for (i in 0 until absolute.size) absolute.setTime(i, 1_714_550_400_000.0 + i * 10_000.0)

        val course = listOf(path(), absolute).toFitCourse("multi", start)

        val firstEnd =
            course.segments[0]
                .records
                .last()
                .timestamp
        assertEquals(
            firstEnd,
            course.segments[1]
                .records
                .first()
                .timestamp,
        )
        assertEquals(20_000L, course.segments[1].let { it.records.last().timestamp - it.records.first().timestamp }.inWholeMilliseconds)
    }

    @Test
    fun `case 05 — a single path gives exactly the same course as the Path overload`() {
        val p = path()

        val viaList = listOf(p).toFitCourse("one", start)
        val viaPath = p.toFitCourse("one", start)

        assertEquals(1, viaList.segments.size)
        assertEquals(viaPath, viaList)
    }

    @Test
    fun `case 06 — the compatibility accessors still work on a single-segment course`() {
        val course = path().toFitCourse("one", start)

        assertEquals(3, course.records.size)
        assertEquals(course.segments[0].lap, course.lap)
    }

    @Test
    fun `case 07 — lap is refused on a multi-segment course, with a message that says why`() {
        val course = listOf(path(), path(latBase = 46.0)).toFitCourse("multi", start)

        val thrown = assertFailsWith<IllegalStateException> { course.lap }
        assertTrue(thrown.message!!.contains("segments"), thrown.message!!)
    }

    @Test
    fun `case 08 — an empty list or an empty path is refused`() {
        assertFailsWith<IllegalArgumentException> { emptyList<Path>().toFitCourse("x", start) }
        assertFailsWith<IllegalArgumentException> { listOf(path(), Path(0)).toFitCourse("x", start) }
    }

    // `case 09 — the encoded multi-path file is bigger and still deterministic` moved to
    // `src/encodingTest` in task w01: it calls the SDK-backed encoder, which wasmWasi stubs.

    @Test
    fun `case 10 — lap totals describe their own segment, not the whole file`() {
        val short = path(points = 3)
        val long = path(points = 6, latBase = 46.0)

        val course = listOf(short, long).toFitCourse("multi", start)

        assertEquals(short.totalDistance, course.segments[0].lap.totalDistanceM, 1e-6)
        assertEquals(long.totalDistance, course.segments[1].lap.totalDistanceM, 1e-6)
        assertTrue(course.segments[1].lap.totalDistanceM > course.segments[0].lap.totalDistanceM)
    }
}
