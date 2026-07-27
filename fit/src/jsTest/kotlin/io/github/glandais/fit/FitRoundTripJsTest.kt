package io.github.glandais.fit

import io.github.glandais.elevation.MathConstants
import io.github.glandais.engine.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * `Path` → FIT → decode, on Kotlin/JS. The conversion itself is commonMain and covered by
 * `PathToFitTest`; what this adds is the proof that the whole chain works through the
 * JavaScript SDK, in Node and in headless Chrome, not only through the Java one.
 */
class FitRoundTripJsTest {
    private val start = Instant.parse("2026-07-28T08:00:00Z")

    private fun path(n: Int): Path {
        val p = Path(n)
        for (i in 0 until n) {
            p.setLatitude(i, (45.0 + i * 6.3e-5) * MathConstants.DEG_TO_RAD)
            p.setLongitude(i, (6.0 + i * 1e-5) * MathConstants.DEG_TO_RAD)
            p.setElevation(i, 350.0 + i * 0.8)
            p.setTime(i, i * 1000.0)
            p.setPComputedPower(i, 220.0 + i)
        }
        p.computeDerivedData()
        return p
    }

    private fun decode(bytes: ByteArray): dynamic {
        val decoder = FitSdkTestApi.Decoder(FitSdkTestApi.Stream.fromByteArray(bytes.toTypedArray()))
        assertTrue(decoder.checkIntegrity(), "the SDK decoder rejected the file's integrity check")
        return decoder.read()
    }

    @Test
    fun `a Path round-trips through FIT on Kotlin_JS`() {
        val p = path(25)
        val result = decode(p.toFitBytes("js round trip", start))
        assertEquals(0, result.errors.length as Int, "decoder reported errors")

        val records = result.messages.recordMesgs
        assertEquals(p.size, records.length as Int)
        for (i in 0 until p.size) {
            // Same FIT-scale-derived tolerances as the JVM round-trip: a semicircle for
            // position, 1/100 m for distance, 1/5 m for altitude.
            assertEquals(
                p.latitudeDeg(i),
                FitUnits.semicirclesToDegrees(records[i].positionLat as Int),
                1e-5,
                "record $i latitude",
            )
            assertEquals(p.distance(i), records[i].distance as Double, 0.01, "record $i distance")
            assertEquals(p.elevation(i), records[i].altitude as Double, 0.2, "record $i altitude")
            assertEquals(p.pComputedPower(i).toInt(), records[i].power as Int, "record $i power")
        }
    }

    @Test
    fun `lap aggregates survive the round-trip on Kotlin_JS`() {
        val p = path(40)
        val lap = decode(p.toFitBytes("js lap", start)).messages.lapMesgs[0]
        assertEquals(p.totalDistance, lap.totalDistance as Double, 0.01)
        assertEquals(p.durationMs / 1000.0, lap.totalElapsedTime as Double, 0.01)
    }

    @Test
    fun `an empty Path raises rather than producing a file`() {
        val failed =
            try {
                Path(0).toFitBytes("empty", start)
                false
            } catch (e: IllegalArgumentException) {
                true
            }
        assertTrue(failed, "an empty Path must not encode to a FIT file")
    }
}
