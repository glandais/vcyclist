package io.github.glandais.fit

import com.garmin.fit.FitDecoder
import com.garmin.fit.FitMessages
import io.github.glandais.elevation.MathConstants
import io.github.glandais.engine.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Counterexamples found by model-checking the FIT course encoder: a TLA+ model of
 * `List<Path>.toFitCourse` + [FitEncoder.encode] + the SDK's local-message allocator
 * (`FitStream.tla`), and a Lean 4 model of the integer scaling (`FitArith.lean`).
 *
 * Each test asserts the contracted behaviour; each failed on the encoder it was found against, and
 * now pins the fix.
 */
class FormalModelFitCourseTest {
    private val start = Instant.parse("2026-07-28T08:00:00Z")

    private fun decode(bytes: ByteArray): FitMessages {
        val decoder = FitDecoder(bytes)
        assertTrue(decoder.checkIntegrity(), "the decoder rejected the file's integrity check")
        val result = decoder.decode()
        assertEquals(emptyList(), result.errors.map { it.message }, "decoder reported errors")
        return result.messages
    }

    private fun path(
        latDeg: DoubleArray,
        lonDeg: DoubleArray,
        timesMs: DoubleArray,
        ele: DoubleArray = DoubleArray(latDeg.size) { 350.0 },
    ): Path {
        val p = Path(latDeg.size)
        for (i in latDeg.indices) {
            p.setLatitude(i, latDeg[i] * MathConstants.DEG_TO_RAD)
            p.setLongitude(i, lonDeg[i] * MathConstants.DEG_TO_RAD)
            p.setElevation(i, ele[i])
            p.setTime(i, timesMs[i])
        }
        p.computeDerivedData()
        return p
    }

    /**
     * Contract: [FitRecord.distanceM] is "Cumulative distance from the course start", and task g10
     * pins "Distance: monotone croissante". A FIT reader (head unit, Garmin Connect) uses the
     * record `distance` as the course's distance axis.
     *
     * TLC counterexample (`DistMonotone`): two paths of two points each. `toFitSegment` copies
     * `distance(i)` of each path verbatim, and `Path.computeDerivedData` restarts that at 0 for
     * every path, so the first record of the second segment goes back to 0 m.
     */
    @Test
    fun multiPathRecordDistanceIsCumulativeFromCourseStart() {
        val a = path(doubleArrayOf(45.0, 45.001), doubleArrayOf(6.0, 6.0), doubleArrayOf(0.0, 10_000.0))
        val b = path(doubleArrayOf(45.001, 45.002), doubleArrayOf(6.0, 6.0), doubleArrayOf(0.0, 10_000.0))

        val records = decode(listOf(a, b).toFitBytes("two", start)).recordMesgs
        val distances = records.map { it.distance!! }

        assertEquals(4, distances.size)
        assertTrue(
            distances.zipWithNext().all { (x, y) -> y >= x },
            "record distances must be monotone across the course, got $distances",
        )
        assertEquals(a.totalDistance + b.totalDistance, distances.last(), 0.02)
    }
}
