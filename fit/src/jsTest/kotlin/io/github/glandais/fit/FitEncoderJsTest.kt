package io.github.glandais.fit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Decoder half of `@garmin/fitsdk`, needed only to replay what the encoder produced. */
@JsModule("@garmin/fitsdk")
@JsNonModule
external object FitSdkTestApi {
    object Stream {
        fun fromByteArray(bytes: Array<Byte>): dynamic
    }

    class Decoder(
        stream: dynamic,
    ) {
        fun checkIntegrity(): Boolean

        fun read(): dynamic
    }
}

/**
 * Vendor cross-check: what [FitEncoder] produces, read back by Garmin's own JavaScript SDK.
 *
 * Since w12 the encoder is pure Kotlin in `commonMain` and every assertion about its output is
 * in `commonTest`, replayed through the multiplatform SDK's decoder. That proves the port is
 * self-consistent, not that it emits FIT as an outside implementation understands it. This file
 * is what closes that gap, and it is the only place `@garmin/fitsdk` still appears — as a
 * **test** dependency, no longer shipped to consumers of `@glandais/vcyclist-fit`.
 *
 * Runs under Node **and**, through Karma, in headless Chrome.
 */
class FitEncoderJsTest {
    private fun decode(bytes: ByteArray): dynamic {
        val stream = FitSdkTestApi.Stream.fromByteArray(bytes.toTypedArray())
        val decoder = FitSdkTestApi.Decoder(stream)
        assertTrue(decoder.checkIntegrity(), "the SDK decoder rejected the file's integrity check")
        return decoder.read()
    }

    @Test
    fun `encodes a course the SDK decoder accepts`() {
        val bytes = FitEncoder.encode(FitReferenceCourse.build())
        assertTrue(bytes.size > 100, "suspiciously small FIT file: ${bytes.size} bytes")
        assertEquals(".FIT", bytes.copyOfRange(8, 12).decodeToString())

        val result = decode(bytes)
        assertEquals(0, result.errors.length as Int, "decoder reported errors")
    }

    @Test
    fun `records round-trip with their positions and sensor fields`() {
        val src = FitReferenceCourse.build()
        val result = decode(FitEncoder.encode(src))
        val records = result.messages.recordMesgs

        assertEquals(src.records.size, records.length as Int)
        src.records.forEachIndexed { i, expected ->
            val actual = records[i]
            assertEquals(
                expected.latitudeDeg,
                FitUnits.semicirclesToDegrees(actual.positionLat as Int),
                1e-6,
                "record $i latitude",
            )
            assertEquals(
                expected.longitudeDeg,
                FitUnits.semicirclesToDegrees(actual.positionLong as Int),
                1e-6,
                "record $i longitude",
            )
            assertEquals(expected.altitudeM!!, actual.altitude as Double, 0.2, "record $i altitude")
            assertEquals(expected.distanceM, actual.distance as Double, 0.01, "record $i distance")
            assertEquals(expected.speedMs!!, actual.speed as Double, 0.001, "record $i speed")
            assertEquals(expected.powerW, actual.power as Int, "record $i power")
        }
    }

    @Test
    fun `the course message carries the name and sport`() {
        val result = decode(FitEncoder.encode(FitReferenceCourse.build()))
        val course = result.messages.courseMesgs[0]
        assertEquals(FitReferenceCourse.NAME, course.name as String)
        // The decoder resolves enum values back to their profile names.
        assertEquals("cycling", course.sport as String)
    }

    @Test
    fun `encoding is deterministic`() {
        val a = FitEncoder.encode(FitReferenceCourse.build())
        val b = FitEncoder.encode(FitReferenceCourse.build())
        assertTrue(a.contentEquals(b), "two encodes of the same course must produce identical bytes")
    }

    @Test
    fun `bytes above 0x7F survive the Uint8Array to ByteArray conversion`() {
        // A FIT file always contains high bytes (CRC, semicircle positions). Were the
        // unsigned-to-signed reinterpretation wrong, they would be mangled and the SDK's own
        // integrity check — asserted inside `decode` — would fail. This makes the intent explicit.
        val bytes = FitEncoder.encode(FitReferenceCourse.build())
        assertTrue(bytes.any { it < 0 }, "expected at least one byte above 0x7F in a FIT file")
        decode(bytes)
    }

    @Test
    fun `the vendor SDK reads the committed reference bytes`() {
        // The bytes `FitEncoderTest` pins on every target, decoded by an implementation that
        // knows nothing of this port: the interoperability claim, verified from the outside.
        val result = decode(FitReferenceBytes.REFERENCE)
        assertEquals(0, result.errors.length as Int, "decoder reported errors")
        assertEquals(FitReferenceCourse.NAME, result.messages.courseMesgs[0].name as String)
        assertEquals(3, result.messages.recordMesgs.length as Int)
    }
}
