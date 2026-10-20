package io.github.glandais.fit

import com.garmin.fit.FitDecoder
import com.garmin.fit.FitMessages
import io.github.glandais.elevation.MathConstants
import io.github.glandais.engine.path.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Course points: snapping onto records, ordering in the file, validation and byte stability. */
class PathToFitCoursePointTest {
    private val start = Instant.parse("2026-07-28T08:00:00Z")

    /** [n] points about 10 m apart, 1 s apart, going north from [latBase]. */
    private fun path(
        n: Int = 21,
        latBase: Double = 45.0,
    ): Path {
        val p = Path(n)
        for (i in 0 until n) {
            p.setLatitude(i, (latBase + i * 9e-5) * MathConstants.DEG_TO_RAD)
            p.setLongitude(i, 6.0 * MathConstants.DEG_TO_RAD)
            p.setElevation(i, 350.0 + i)
            p.setTime(i, i * 1000.0)
        }
        p.computeDerivedData()
        return p
    }

    private fun decode(bytes: ByteArray): FitMessages {
        val decoder = FitDecoder(bytes)
        assertTrue(decoder.checkIntegrity(), "the decoder rejected the file's integrity check")
        val result = decoder.decode()
        assertEquals(emptyList(), result.errors.map { it.message }, "decoder reported errors")
        return result.messages
    }

    private fun spec(
        d: Double,
        type: FitCoursePointType = FitCoursePointType.WATER,
        name: String? = null,
    ) = FitCoursePointSpec(d, type, name)

    private fun mesgNums(bytes: ByteArray): List<Int> = FitDecoder(bytes).asSequence().map { it.globalMesgNum.toInt() }.toList()

    @Test
    fun `case 01 — a point lands on its nearest record`() {
        val p = path()
        val course = p.toFitCourse("c", start, listOf(spec(47.0)))
        val records = course.records
        val expected = records.minBy { kotlin.math.abs(it.distanceM - 47.0) }

        val point = course.coursePoints.single()
        assertEquals(
            FitCoursePoint(expected.timestamp, expected.latitudeDeg, expected.longitudeDeg, expected.distanceM, FitCoursePointType.WATER),
            point,
        )

        val messages = decode(FitEncoder.encode(course))
        val mesg = messages.coursePointMesgs.single()
        val rec = messages.recordMesgs[records.indexOf(expected)]
        assertEquals(rec.positionLat, mesg.positionLat)
        assertEquals(rec.positionLong, mesg.positionLong)
        assertEquals(rec.distance, mesg.distance)
        assertEquals(rec.timestamp, mesg.timestamp)
    }

    @Test
    fun `case 02 — a tie goes to the record below`() {
        // Distances overwritten with exact multiples of 10 so that 35.0 is a true tie.
        val p = path()
        for (i in 0 until p.size) p.setDistance(i, i * 10.0)
        val point = p.toFitCourse("c", start, listOf(spec(35.0))).coursePoints.single()
        assertEquals(30.0, point.distanceM)
        assertEquals(start + 3.seconds, point.timestamp)
    }

    @Test
    fun `case 03 — a stop snaps to its first record`() {
        // Records 5 and 6 repeat the position of record 4: the distance stands still.
        val p = Path(10)
        for (i in 0 until 10) {
            val k =
                if (i in 5..6) {
                    4
                } else if (i > 6) {
                    i - 2
                } else {
                    i
                }
            p.setLatitude(i, (45.0 + k * 9e-5) * MathConstants.DEG_TO_RAD)
            p.setLongitude(i, 6.0 * MathConstants.DEG_TO_RAD)
            p.setElevation(i, 350.0)
            p.setTime(i, i * 1000.0)
        }
        p.computeDerivedData()
        val course = p.toFitCourse("c", start, listOf(spec(p.distance(4) + 0.1)))
        val point = course.coursePoints.single()
        assertEquals(course.records[4].timestamp, point.timestamp)
    }

    @Test
    fun `case 04 — distances outside the course are clamped`() {
        val course = path().toFitCourse("c", start, listOf(spec(-5.0), spec(1e9)))
        val records = course.records
        assertEquals(records.first().timestamp, course.coursePoints[0].timestamp)
        assertEquals(0.0, course.coursePoints[0].distanceM)
        assertEquals(records.last().timestamp, course.coursePoints[1].timestamp)
        assertEquals(records.last().distanceM, course.coursePoints[1].distanceM)
    }

    @Test
    fun `case 05 — non-finite distances are rejected`() {
        for (d in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { FitCoursePointSpec(d, FitCoursePointType.INFO) }
        }
    }

    @Test
    fun `case 06 — points are sorted stably and indexed from zero`() {
        val specs =
            listOf(
                spec(300.0, name = "c"),
                spec(100.0, name = "a1"),
                spec(200.0, name = "b"),
                spec(100.0, name = "a2"),
            )
        val course = path().toFitCourse("c", start, specs)
        assertEquals(listOf("a1", "a2", "b", "c"), course.coursePoints.map { it.name })
        assertEquals(course.coursePoints.sortedBy { it.distanceM }, course.coursePoints)

        val mesgs = decode(FitEncoder.encode(course)).coursePointMesgs
        assertEquals(listOf(0, 1, 2, 3), mesgs.map { it.messageIndex!!.toInt() })
        assertEquals(listOf("a1", "a2", "b", "c"), mesgs.map { it.name })
    }

    @Test
    fun `case 06b — two points on the same record stay two messages`() {
        val course = path().toFitCourse("c", start, listOf(spec(50.0), spec(50.2)))
        assertEquals(2, course.coursePoints.size)
        assertEquals(course.coursePoints[0].distanceM, course.coursePoints[1].distanceM)
    }

    @Test
    fun `case 07 — multi-segment points carry the cumulative distance and the gap`() {
        val a = path(11)
        val b = path(11, latBase = 46.0)
        val gap = 5.minutes
        val course = listOf(a, b).toFitCourse("m", start, listOf(spec(a.totalDistance + 40.0), spec(a.totalDistance)), interPathGap = gap)

        val junctionPoint = course.coursePoints[0]
        val inB = course.coursePoints[1]
        val lastOfA = course.segments[0].records.last()
        assertEquals(lastOfA.timestamp, junctionPoint.timestamp, "a point on the junction goes to the last record of A")
        assertEquals(lastOfA.distanceM, junctionPoint.distanceM)

        assertTrue(inB.distanceM > a.totalDistance)
        val bRecords = course.segments[1].records
        assertTrue(inB.timestamp >= bRecords.first().timestamp)
        assertTrue(inB.timestamp - lastOfA.timestamp >= gap)
        assertTrue(bRecords.any { it.timestamp == inB.timestamp && it.distanceM == inB.distanceM })
    }

    @Test
    fun `case 08 — message order, one segment`() {
        val bytes = path().toFitBytes("c", start, listOf(spec(50.0), spec(100.0)))
        val nums = mesgNums(bytes)
        // file_id, course, lap, event(START), 21 records, 2 course points, event(STOP_ALL)
        val expected = listOf(0, 31, 19, 21) + List(21) { 20 } + List(2) { 32 } + listOf(21)
        assertEquals(expected, nums)
    }

    @Test
    fun `case 08b — message order, two segments`() {
        val bytes = listOf(path(5), path(5, latBase = 46.0)).toFitBytes("c", start, listOf(spec(10.0), spec(60.0)))
        val nums = mesgNums(bytes)
        // file_id, course, 2 laps, START, 5 records, STOP, START, 5 records, 2 points, STOP_ALL
        val expected =
            listOf(0, 31, 19, 19) + listOf(21) + List(5) { 20 } + listOf(21) + listOf(21) + List(5) { 20 } + List(2) { 32 } + listOf(21)
        assertEquals(expected, nums)
    }

    @Test
    fun `case 09 — types and names come back`() {
        val course =
            path().toFitCourse(
                "c",
                start,
                listOf(
                    spec(20.0, FitCoursePointType.WATER, "Fontaine"),
                    spec(60.0, FitCoursePointType.MEETING_SPOT, ""),
                    spec(100.0, FitCoursePointType.AID_STATION, null),
                    spec(150.0, FitCoursePointType.CHECKPOINT, "Café"),
                ),
            )
        val mesgs = decode(FitEncoder.encode(course)).coursePointMesgs
        assertEquals(
            listOf("WATER", "MEETING_SPOT", "AID_STATION", "CHECKPOINT"),
            mesgs.map { it.type!!.name },
        )
        assertEquals(listOf("Fontaine", null, null, "Café"), mesgs.map { it.name })
        assertNull(course.coursePoints[1].name)
    }

    @Test
    fun `case 10 — no course points, same bytes`() {
        val a = path()
        val b = path(latBase = 46.0)
        assertContentEquals(listOf(a, b).toFitBytes("n", start), listOf(a, b).toFitBytes("n", start, emptyList()))
        assertContentEquals(a.toFitBytes("n", start), a.toFitBytes("n", start, emptyList()))
    }

    @Test
    fun `case 11 — FitCoursePoint and FitCourse validate`() {
        fun point(
            d: Double = 1.0,
            name: String? = null,
        ) = FitCoursePoint(start, 45.0, 6.0, d, FitCoursePointType.INFO, name)

        point(name = "x".repeat(254))
        assertFailsWith<IllegalArgumentException> { point(name = "x".repeat(255)) }
        assertFailsWith<IllegalArgumentException> { point(name = "a\u0000b") }
        assertFailsWith<IllegalArgumentException> { point(d = -1.0) }

        val base = path().toFitCourse("c", start)
        assertFailsWith<IllegalArgumentException> { base.copy(coursePoints = listOf(point(5.0), point(1.0))) }
        assertFailsWith<IllegalArgumentException> {
            base.copy(coursePoints = List(FitCourse.MAX_COURSE_POINTS + 1) { point() })
        }
        base.copy(coursePoints = List(FitCourse.MAX_COURSE_POINTS) { point() })
    }

    @Test
    fun `case 12 — many name lengths still decode, STOP_ALL included`() {
        val specs = List(40) { spec(it * 5.0, name = "n".repeat(it + 1)) }
        val bytes = path().toFitBytes("c", start, specs)
        val messages = decode(bytes)
        assertEquals(40, messages.coursePointMesgs.size)
        assertEquals((1..40).map { "n".repeat(it) }, messages.coursePointMesgs.map { it.name })
        val events = messages.eventMesgs
        assertEquals("STOP_ALL", events.last().eventType!!.name)
    }

    @Test
    fun `case 13 — a path without a clock`() {
        val p = path().withoutTime()
        val course = p.toFitCourse("c", start, listOf(spec(30.0), spec(120.0)))
        assertTrue(course.coursePoints.all { it.timestamp == start })
        assertTrue(course.coursePoints[1].distanceM > course.coursePoints[0].distanceM)
        assertEquals(course.records.minBy { kotlin.math.abs(it.distanceM - 120.0) }.distanceM, course.coursePoints[1].distanceM)
    }
}
