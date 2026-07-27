package io.github.glandais.fit

import com.garmin.fit.FitDecoder
import io.github.glandais.elevation.MathConstants
import io.github.glandais.engine.path.Path
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * End-to-end round-trips: build a [Path], encode it, and read the result back with the Garmin
 * SDK's own decoder — the reference implementation for this format.
 *
 * Tolerances here are not arbitrary. They follow the FIT scales pinned in `FitUnitsTest`:
 * position is quantised to a semicircle (~1.2 cm at the equator), altitude to 1/5 m, distance
 * to 1/100 m, speed to 1/1000 m/s. Each assertion states which one it is relying on.
 */
class FitRoundTripTest {
    private val start = Instant.parse("2026-07-28T08:00:00Z")

    private fun decode(bytes: ByteArray) = FitDecoder().decode(ByteArrayInputStream(bytes))

    /**
     * A synthetic climb of [n] points: 1 s apart, ~7 m apart, rising then falling, with power
     * and heart rate. Stands in for an enhanced GPX without needing the whole engine pipeline
     * in `:fit`'s test classpath.
     */
    private fun syntheticPath(n: Int): Path {
        val p = Path(n)
        for (i in 0 until n) {
            p.setLatitude(i, (45.0 + i * 6.3e-5) * MathConstants.DEG_TO_RAD)
            p.setLongitude(i, (6.0 + i * 1e-5) * MathConstants.DEG_TO_RAD)
            // Up for the first half, down for the second — exercises both ascent and descent.
            val up = if (i <= n / 2) i else n - i
            p.setElevation(i, 350.0 + up * 0.8)
            p.setTime(i, i * 1000.0)
            p.setPComputedPower(i, 220.0 + (i % 40))
            p.setHeartRate(i, 140.0 + (i % 15))
        }
        p.computeDerivedData()
        return p
    }

    @Test
    fun `case 01 — every point becomes a record with its position preserved`() {
        val path = syntheticPath(3)
        val messages = decode(path.toFitBytes("three", start))
        val records = messages.recordMesgs

        assertEquals(3, records.size)
        for (i in 0 until 3) {
            // 1e-5 deg is far above the semicircle quantum; it is sized to also cover the
            // degrees -> radians -> degrees round-trip Path imposes.
            assertEquals(
                path.latitudeDeg(i),
                FitUnits.semicirclesToDegrees(records[i].positionLat),
                1e-5,
                "record $i latitude",
            )
            assertEquals(
                path.longitudeDeg(i),
                FitUnits.semicirclesToDegrees(records[i].positionLong),
                1e-5,
                "record $i longitude",
            )
        }
    }

    @Test
    fun `case 02 — record timestamps are startTime plus the path's relative clock`() {
        val path = syntheticPath(5)
        val records = decode(path.toFitBytes("clock", start)).recordMesgs
        for (i in records.indices) {
            val decoded = FitUnits.fromFitTimestamp(records[i].timestamp.timestamp)
            val offsetS = (decoded.toEpochMilliseconds() - start.toEpochMilliseconds()) / 1000.0
            // FIT timestamps have 1 s resolution, hence the whole-second comparison.
            assertEquals(path.time(i) / 1000.0, offsetS, 1.0, "record $i timestamp")
        }
    }

    @Test
    fun `case 03 — distance is monotonic and survives the 1-100 m scale`() {
        val path = syntheticPath(20)
        val records = decode(path.toFitBytes("dist", start)).recordMesgs
        for (i in records.indices) {
            assertEquals(path.distance(i), records[i].distance.toDouble(), 0.01, "record $i distance")
        }
        assertTrue(
            records.zipWithNext().all { (a, b) -> b.distance >= a.distance },
            "decoded distance must be non-decreasing",
        )
    }

    @Test
    fun `case 04 — altitude survives the 1-5 m scale`() {
        val path = syntheticPath(20)
        val records = decode(path.toFitBytes("alt", start)).recordMesgs
        for (i in records.indices) {
            // Altitude is stored as (m + 500) * 5, so 0.2 m is exactly one quantum.
            assertEquals(path.elevation(i), records[i].altitude.toDouble(), 0.2, "record $i altitude")
        }
    }

    @Test
    fun `case 05 — a path with no sensor data omits those fields entirely`() {
        val p = Path(3)
        for (i in 0 until 3) {
            p.setLatitude(i, (45.0 + i * 1e-4) * MathConstants.DEG_TO_RAD)
            p.setLongitude(i, 6.0 * MathConstants.DEG_TO_RAD)
            p.setElevation(i, 100.0)
            p.setTime(i, i * 1000.0)
        }
        p.computeDerivedData()

        val record = decode(p.toFitBytes("bare", start)).recordMesgs.first()
        assertNull(record.power, "power must be absent, not 0 W")
        assertNull(record.heartRate, "heart rate must be absent, not 0 bpm")
        assertNull(record.cadence, "cadence must be absent, not 0 rpm")
    }

    @Test
    fun `case 06 — lap aggregates match the path`() {
        val path = syntheticPath(50)
        val messages = decode(path.toFitBytes("lap", start))
        val lap = messages.lapMesgs.single()

        assertEquals(path.totalDistance, lap.totalDistance.toDouble(), 0.01)
        assertEquals(path.durationMs / 1000.0, lap.totalElapsedTime.toDouble(), 0.01)
        // Ascent/descent are whole meters in FIT and rounded on the way in, so allow 1 m.
        assertEquals(path.elevationGain, lap.totalAscent.toDouble(), 1.0)
        // FIT descent is a positive magnitude while Path.elevationLoss is negative.
        assertEquals(-path.elevationLoss, lap.totalDescent.toDouble(), 1.0)
        // The lap total must agree with the final record or head units reject the course.
        assertEquals(
            messages.recordMesgs
                .last()
                .distance
                .toDouble(),
            lap.totalDistance.toDouble(),
            0.01,
        )
    }

    @Test
    fun `case 07 — a thousand-point course decodes cleanly and is plausibly sized`() {
        val path = syntheticPath(1_000)
        val bytes = path.toFitBytes("long", start)
        val messages = decode(bytes)
        assertEquals(1_000, messages.recordMesgs.size)
        // ~22 bytes of record payload each, plus definitions and header.
        assertTrue(bytes.size in 20_000..40_000, "unexpected file size: ${bytes.size} bytes")
    }

    @Test
    fun `case 08 — a ten-thousand-point course still round-trips`() {
        val path = syntheticPath(10_000)
        val messages = decode(path.toFitBytes("very long", start))
        assertEquals(10_000, messages.recordMesgs.size)
        assertEquals(
            path.totalDistance,
            messages.lapMesgs
                .single()
                .totalDistance
                .toDouble(),
            0.01,
        )
    }

    @Test
    fun `case 09 — an empty path raises instead of writing an unusable file`() {
        val failure = runCatching { Path(0).toFitBytes("empty", start) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException, "expected IllegalArgumentException, got $failure")
    }

    @Test
    fun `case 10 — the course carries its name and sport`() {
        val messages = decode(syntheticPath(3).toFitBytes("Col du Galibier", start))
        val course = messages.courseMesgs.single()
        assertEquals("Col du Galibier", course.name)
        assertEquals(com.garmin.fit.Sport.CYCLING, course.sport)
    }

    @Test
    fun `case 11 — power and heart rate round-trip`() {
        val path = syntheticPath(10)
        val records = decode(path.toFitBytes("sensors", start)).recordMesgs
        for (i in records.indices) {
            assertEquals(path.pComputedPower(i).toInt(), records[i].power, "record $i power")
            assertEquals(path.heartRate(i).toInt(), records[i].heartRate.toInt(), "record $i heart rate")
        }
    }

    @Test
    fun `case 12 — encoding the same path twice yields the same bytes`() {
        val path = syntheticPath(20)
        assertTrue(
            path.toFitBytes("determinism", start).contentEquals(path.toFitBytes("determinism", start)),
        )
    }
}
