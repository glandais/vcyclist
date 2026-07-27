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
 * Kotlin/JS encoder tests. Run under Node **and**, through Karma, in headless Chrome. The
 * browser run is the whole point of task g09, so it is not optional coverage — if
 * `@garmin/fitsdk` failed to load in a browser bundle, these tests are what would say so.
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
    fun `Kotlin_JS reproduces the committed web reference bytes`() {
        // Same fixture the Wasm target asserts against — that shared expectation is what keeps
        // the two hand-written `actual`s from drifting in field order or units.
        assertTrue(
            FitEncoder.encode(FitReferenceCourse.build()).contentEquals(FitReferenceBytes.WEB),
            "Kotlin/JS output diverged from the committed reference bytes",
        )
    }

    @Test
    fun `the JS SDK decodes what the JVM encoder produces`() {
        // Interoperability, checked from this side: the Java SDK writes big-endian FIT, and this
        // decoder must read it and agree with our own little-endian output field for field.
        val fromJvm = decode(FitReferenceBytes.JVM)
        val fromWeb = decode(FitReferenceBytes.WEB)

        val a = fromJvm.messages.recordMesgs
        val b = fromWeb.messages.recordMesgs
        assertEquals(a.length as Int, b.length as Int)
        for (i in 0 until (a.length as Int)) {
            assertEquals(a[i].positionLat as Int, b[i].positionLat as Int, "record $i latitude")
            assertEquals(a[i].positionLong as Int, b[i].positionLong as Int, "record $i longitude")
            assertEquals(a[i].distance as Double, b[i].distance as Double, 1e-9, "record $i distance")
            assertEquals(a[i].speed as Double, b[i].speed as Double, 1e-9, "record $i speed")
            assertEquals(a[i].power as Int, b[i].power as Int, "record $i power")
        }
        assertEquals(
            fromJvm.messages.courseMesgs[0].name as String,
            fromWeb.messages.courseMesgs[0].name as String,
        )
    }
}
