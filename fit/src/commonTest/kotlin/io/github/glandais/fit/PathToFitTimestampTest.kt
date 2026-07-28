package io.github.glandais.fit

import io.github.glandais.elevation.MathConstants
import io.github.glandais.engine.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Task g25a: FIT timestamps are computed relative to the path's **own** first point.
 *
 * Before this, `toFitCourse` added [startTime] to the raw `TIME` value. That is correct for a
 * virtualized path (`time(0) == 0`) and badly wrong for one merely parsed from a timestamped
 * GPX, since `GpxToPath` copies epoch milliseconds through verbatim — the resulting file was
 * dated some 57 years in the future. No test caught it because every FIT fixture was
 * virtualized.
 */
class PathToFitTimestampTest {
    private val start = Instant.parse("2026-07-28T08:00:00Z")

    /** [t0] lets the same geometry be presented with a relative or an absolute clock. */
    private fun path(
        t0: Double,
        stepMs: Double = 10_000.0,
    ): Path {
        val p = Path(3)
        for (i in 0 until 3) {
            p.setLatitude(i, (45.68 + i * 0.001) * MathConstants.DEG_TO_RAD)
            p.setLongitude(i, (6.39 + i * 0.001) * MathConstants.DEG_TO_RAD)
            p.setElevation(i, 350.0 + i)
            p.setTime(i, t0 + i * stepMs)
        }
        p.computeDerivedData()
        return p
    }

    @Test
    fun `case 01 — a virtualized path is unaffected by the rebase`() {
        val course = path(t0 = 0.0).toFitCourse("test", start)

        assertEquals(start, course.records[0].timestamp)
        assertEquals(start.plusMs(10_000), course.records[1].timestamp)
        assertEquals(start.plusMs(20_000), course.records[2].timestamp)
    }

    @Test
    fun `case 02 — a path parsed from a timestamped GPX starts exactly at startTime`() {
        // What GpxToPath produces for a 2024 GPX: absolute epoch milliseconds in TIME.
        val epoch2024 = 1_714_550_400_000.0

        val course = path(t0 = epoch2024).toFitCourse("test", start)

        assertEquals(start, course.records[0].timestamp, "startTime must mean what its name says")
        assertEquals(start.plusMs(20_000), course.records[2].timestamp)
        assertTrue(
            course.records[0].timestamp.toEpochMilliseconds() < 2_000_000_000_000L,
            "pre-g25 this landed in 2083: ${course.records[0].timestamp}",
        )
    }

    @Test
    fun `case 03 — the two clocks produce the same file`() {
        val relative = path(t0 = 0.0).toFitCourse("test", start)
        val absolute = path(t0 = 1_714_550_400_000.0).toFitCourse("test", start)

        assertEquals(relative.records.map { it.timestamp }, absolute.records.map { it.timestamp })
        assertEquals(relative.lap.startTime, absolute.lap.startTime)
        assertContentEqualsBytes(FitEncoder.encode(relative), FitEncoder.encode(absolute))
    }

    @Test
    fun `case 04 — conversion is idempotent on an already-rebased path`() {
        val once = path(t0 = 5_000.0).toFitCourse("test", start)
        val twice = path(t0 = 5_000.0).toFitCourse("test", start)

        assertEquals(once.records.map { it.timestamp }, twice.records.map { it.timestamp })
        assertEquals(start, once.records[0].timestamp)
    }

    @Test
    fun `case 05 — a non-monotonic path is refused, not silently encoded`() {
        val p = Path(3)
        for (i in 0 until 3) {
            p.setLatitude(i, 45.68 * MathConstants.DEG_TO_RAD)
            p.setLongitude(i, 6.39 * MathConstants.DEG_TO_RAD)
            p.setElevation(i, 350.0)
        }
        p.setTime(0, 0.0)
        p.setTime(1, 20_000.0)
        p.setTime(2, 10_000.0) // goes backwards
        p.computeDerivedData()

        val thrown = assertFailsWith<IllegalArgumentException> { p.toFitCourse("test", start) }
        assertTrue(thrown.message!!.contains("monotonic"), thrown.message!!)
    }

    @Test
    fun `case 06 — the lap start matches the first record`() {
        val course = path(t0 = 1_714_550_400_000.0).toFitCourse("test", start)

        assertEquals(course.records.first().timestamp, course.lap.startTime)
    }

    private fun Instant.plusMs(ms: Long): Instant = Instant.fromEpochMilliseconds(toEpochMilliseconds() + ms)

    private fun assertContentEqualsBytes(
        expected: ByteArray,
        actual: ByteArray,
    ) {
        assertEquals(expected.size, actual.size, "encoded size")
        for (i in expected.indices) {
            assertEquals(expected[i], actual[i], "byte $i")
        }
    }
}
