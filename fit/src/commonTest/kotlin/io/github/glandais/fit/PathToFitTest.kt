package io.github.glandais.fit

import io.github.glandais.elevation.MathConstants
import io.github.glandais.engine.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The `Path` → [FitCourse] mapping itself, independent of any SDK, so it runs on all four
 * targets — including wasmWasi, where [FitEncoder] itself is a stub. The cases that do call the
 * encoder live in `src/encodingTest` (task w01) ; the SDK-backed round-trips are in
 * `FitRoundTripTest` (JVM) and `FitEncoderJsTest`.
 */
class PathToFitTest {
    private val start = Instant.parse("2026-07-28T08:00:00Z")

    /** Three points, ~70 m apart, 10 s apart, with a full set of sensor values. */
    private fun samplePath(): Path {
        val p = Path(3)
        val lat = doubleArrayOf(45.680697, 45.681335, 45.681565)
        val lon = doubleArrayOf(6.396115, 6.396195, 6.396291)
        val ele = doubleArrayOf(350.1, 349.7, 349.5)
        for (i in 0 until 3) {
            p.setLatitude(i, lat[i] * MathConstants.DEG_TO_RAD)
            p.setLongitude(i, lon[i] * MathConstants.DEG_TO_RAD)
            p.setElevation(i, ele[i])
            p.setTime(i, i * 10_000.0)
            p.setPComputedPower(i, 200.0 + i * 10)
            p.setHeartRate(i, 130.0 + i)
            p.setCadence(i, 85.0)
            p.setTemperature(i, 18.0)
        }
        p.computeDerivedData()
        return p
    }

    @Test
    fun `case 01 — one record per point, coordinates preserved`() {
        val course = samplePath().toFitCourse("test", start)
        assertEquals(3, course.records.size)
        // Tolerance is far above the ~1.2 cm semicircle quantum documented in FitUnits; it is
        // sized to also absorb the radians round-trip Path stores coordinates in.
        assertEquals(45.680697, course.records[0].latitudeDeg, 1e-9)
        assertEquals(6.396115, course.records[0].longitudeDeg, 1e-9)
        assertEquals(45.681565, course.records[2].latitudeDeg, 1e-9)
    }

    @Test
    fun `case 02 — timestamps are startTime plus the path's relative clock`() {
        val course = samplePath().toFitCourse("test", start)
        assertEquals(start, course.records[0].timestamp)
        assertEquals(start.plusSeconds(10), course.records[1].timestamp)
        assertEquals(start.plusSeconds(20), course.records[2].timestamp)
        assertEquals(start, course.startTime)
    }

    @Test
    fun `case 03 — distance is monotonic and matches the path`() {
        val path = samplePath()
        val course = path.toFitCourse("test", start)
        for (i in 0 until path.size) {
            assertEquals(path.distance(i), course.records[i].distanceM, 1e-9, "record $i")
        }
        assertTrue(
            course.records.zipWithNext().all { (a, b) -> b.distanceM >= a.distanceM },
            "distance must be non-decreasing",
        )
    }

    @Test
    fun `case 04 — altitude is carried through unrounded`() {
        val path = samplePath()
        val course = path.toFitCourse("test", start)
        for (i in 0 until path.size) {
            assertEquals(path.elevation(i), course.records[i].altitudeM!!, 1e-9, "record $i")
        }
    }

    @Test
    fun `case 05 — untouched sensor slots are omitted, not encoded as zero`() {
        // `GpxToPath` writes NaN for a sensor the GPX never carried, so a course built from a
        // path that never had a heart-rate monitor must not claim a flat 0 bpm — that draws a
        // line at the bottom of every chart instead of showing an absence.
        val p = Path(2)
        for (i in 0 until 2) {
            p.setLatitude(i, 45.0 * MathConstants.DEG_TO_RAD)
            p.setLongitude(i, 6.0 * MathConstants.DEG_TO_RAD)
            p.setElevation(i, 100.0)
            p.setTime(i, i * 1000.0)
            p.setHeartRate(i, Double.NaN)
            p.setCadence(i, Double.NaN)
            p.setPInputPower(i, Double.NaN)
            p.setTemperature(i, Double.NaN)
        }
        p.computeDerivedData()

        val record = p.toFitCourse("bare", start).records[0]
        assertNull(record.heartRate, "heart rate")
        assertNull(record.cadence, "cadence")
        assertNull(record.powerW, "power")
        assertNull(record.temperatureC, "temperature")
        // Position and altitude are not optional — they stay.
        assertEquals(100.0, record.altitudeM)
    }

    @Test
    fun `case 06 — NaN sensor values are omitted too`() {
        val p = samplePath()
        p.setHeartRate(0, Double.NaN)
        p.setPComputedPower(0, Double.NaN)
        val record = p.toFitCourse("nan", start).records[0]
        assertNull(record.heartRate)
        assertNull(record.powerW)
        // The other points are untouched.
        assertEquals(131, p.toFitCourse("nan", start).records[1].heartRate)
    }

    @Test
    fun `case 07 — lap aggregates are taken from the Path, not recomputed`() {
        val path = samplePath()
        val course = path.toFitCourse("test", start)
        assertEquals(path.totalDistance, course.lap.totalDistanceM, 1e-9)
        assertEquals(path.durationMs / 1000.0, course.lap.totalElapsedTimeS, 1e-9)
        // No pauses in a virtualized course, so elapsed == timer by construction.
        assertEquals(course.lap.totalElapsedTimeS, course.lap.totalTimerTimeS)
        assertEquals(start, course.lap.startTime)
    }

    @Test
    fun `case 08 — ascent and descent are rounded to whole meters`() {
        val p = Path(3)
        val ele = doubleArrayOf(100.0, 101.6, 100.0)
        for (i in 0 until 3) {
            p.setLatitude(i, (45.0 + i * 0.001) * MathConstants.DEG_TO_RAD)
            p.setLongitude(i, 6.0 * MathConstants.DEG_TO_RAD)
            p.setElevation(i, ele[i])
            p.setTime(i, i * 1000.0)
        }
        p.computeDerivedData()
        val lap = p.toFitCourse("round", start).lap
        // gain 1.6 m and loss 1.6 m both round to 2, not truncate to 1.
        assertEquals(2, lap.totalAscentM)
        // And descent is a positive magnitude: Path.elevationLoss is negative, FIT's is not.
        assertEquals(2, lap.totalDescentM)
        assertTrue(p.elevationLoss < 0.0, "precondition: Path stores loss as a negative number")
    }

    @Test
    fun `case 09 — an empty Path fails loudly instead of yielding an empty FIT file`() {
        // A record-less FIT file passes the SDK but is rejected by the platforms, so this must
        // fail at conversion time rather than produce something that only breaks on upload.
        val failure = assertFailsWith<IllegalArgumentException> { Path(0).toFitCourse("empty", start) }
        assertTrue(
            failure.message.orEmpty().contains("empty", ignoreCase = true),
            "unhelpful message: ${failure.message}",
        )
    }

    @Test
    fun `case 10 — lap endpoints and extrema come from the path`() {
        val path = samplePath()
        val lap = path.toFitCourse("test", start).lap
        assertEquals(45.680697, lap.startLatitudeDeg!!, 1e-9)
        assertEquals(45.681565, lap.endLatitudeDeg!!, 1e-9)
        assertEquals(349.5, lap.minAltitudeM!!, 1e-9)
        assertEquals(350.1, lap.maxAltitudeM!!, 1e-9)
    }

    @Test
    fun `case 11 — sport defaults to cycling and can be overridden`() {
        assertEquals(FitSport.CYCLING, samplePath().toFitCourse("t", start).sport)
        assertEquals(FitSport.RUNNING, samplePath().toFitCourse("t", start, FitSport.RUNNING).sport)
    }

    // `case 12 — toFitBytes produces a FIT file` moved to `src/encodingTest` in task w01: it is
    // the only case here that calls the SDK-backed encoder, which wasmWasi stubs.

    private fun Instant.plusSeconds(s: Long): Instant = Instant.fromEpochMilliseconds(toEpochMilliseconds() + s * 1000)
}
